package autosalvager.mixin;

import java.util.WeakHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import autosalvager.fx.Debug;
import autosalvager.fx.SalvageUnit;
import crafting.SalvageSystem;
import game.GameUtil;
import game.objects.Asteroid;
import game.objects.SpaceShip;
import illuminatus.core.datastructures.List;
import items.Item;
import items.lists.SlotList;

/**
 * Runs the Auto-Salvager behaviour.
 *
 * <p>The salvaging is driven by the <b>drones themselves</b>. Mirroring the
 * salvager weapon (one drone per mount, which won't relaunch until its drone
 * returns), each installed module launches a <b>formation</b> of drones toward a
 * wreck; each drone salvages on arrival and carries the loot home, depositing it
 * only when it lands. That module's next formation launches only once every drone
 * from its previous one is back — so the round-trip <em>is</em> the harvest cadence.</p>
 *
 * <p><b>Multiple modules run in parallel:</b> a station with N Auto-Salvagers keeps
 * N independent {@link SalvageUnit}s, each with its own in-flight counter and its
 * own wreck. Units claim <b>distinct</b> targets, so two modules won't pile onto the
 * same wreck — you see N formations working N different wrecks (each sized by its own
 * module's tier). A unit finishes its wreck across formations before picking a new one.</p>
 *
 * <p>We hook {@link SalvageSystem#runSalvagers(SpaceShip)} — the game's own
 * per-tick salvager runner, invoked every {@code FPS_2_Timer} tick for every
 * ship/station — purely to launch/relaunch formations when idle. All the
 * wreck wear-down, the {@code prob((tier+1)/hardness)} yield gate, and the cargo
 * deposit live in {@code autosalvager.fx.SalvageDroneFX}.</p>
 *
 * <p>Stats per tier (I-V):</p>
 * <ul>
 *   <li>Drones per formation: 2 / 3 / 4 / 5 / 6 (more, faster drones with tier)</li>
 *   <li>Bins per drone: 1 / 1 / 2 / 2 / 3 items recovered per successful trip</li>
 *   <li>Range: 2500 / 3000 / 3500 / 4000 / 4500 (matches the Auto-Miner)</li>
 *   <li>Yield gate per drone: the game's own {@code prob((tier+1)/hardness)} —
 *       tough wrecks resist, higher tiers extract more reliably</li>
 *   <li>Loot luck: ramps 0.50..0.90 per tier (a bit above the salvager weapons'
 *       ~0.20..0.29), fed into the loot roll for better-quality drops</li>
 * </ul>
 */
@Mixin(value = SalvageSystem.class, remap = false)
public class AutoSalvageMixin {

    /** Per-tier salvage range, matching the Auto-Miner's 2500..4500 ramp. */
    @Unique
    private static final int[] AUTOSALVAGER_RANGE = {2500, 3000, 3500, 4000, 4500};

    /** Per-tier "Salvage Item Bins": items each drone tries to recover per trip. */
    @Unique
    private static final int[] AUTOSALVAGER_BINS = {1, 1, 2, 2, 3};

    /** Per-tier loot "luck" fed to the loot roll: ramps 0.50..0.90, a bit above the salvager weapons. */
    @Unique
    private static final double[] AUTOSALVAGER_LUCK = {0.50, 0.60, 0.70, 0.80, 0.90};

    /** Per-tier formation size: how many drones fly out together. */
    @Unique
    private static final int[] AUTOSALVAGER_DRONES = {2, 3, 4, 5, 6};

    /** Base id of Auto-Salvager I; tiers II-V are the next four ids. */
    @Unique
    private static final int AUTOSALVAGER_BASE_ID = 9006;

    /**
     * Per-station list of {@link SalvageUnit}s — one per installed Auto-Salvager
     * module. Reconciled to the installed modules each pass. WeakHashMap so entries
     * evict on station unload (releasing the units and their target references).
     */
    @Unique
    private static WeakHashMap<SpaceShip, java.util.List<SalvageUnit>> autosalvager$units;

    /** Last status line logged per station, so debug chat only prints when state changes. */
    @Unique
    private static WeakHashMap<SpaceShip, String> autosalvager$lastLog;

    @Inject(method = "runSalvagers", at = @At("HEAD"))
    private static void autosalvager$run(SpaceShip station, CallbackInfo ci) {
        try {
            if (station == null || !station.isStation() || station.cargo == null) {
                return;
            }
            autosalvager$serviceStation(station);
        } catch (Throwable t) {
            Debug.log("salvage pass failed: " + t);
        }
    }

    /**
     * Keep one {@link SalvageUnit} per installed module, then (per unit) validate its
     * current wreck, assign idle unassigned units a fresh <b>distinct</b> wreck, and
     * launch a formation for any idle unit that has a target.
     */
    @Unique
    private static void autosalvager$serviceStation(SpaceShip station) {
        if (autosalvager$units == null) {
            autosalvager$units = new WeakHashMap<SpaceShip, java.util.List<SalvageUnit>>();
        }
        java.util.List<SalvageUnit> units = autosalvager$units.get(station);
        if (units == null) {
            units = new java.util.ArrayList<SalvageUnit>();
            autosalvager$units.put(station, units);
        }

        // Reconcile the unit list to the modules currently installed.
        int[] want = new int[6]; // want[tier] = number of modules of that tier
        autosalvager$scanTiers(station, want);
        for (int t = 1; t <= 5; t++) {
            int have = 0;
            for (int i = 0; i < units.size(); i++) {
                if (units.get(i).tier == t) {
                    have++;
                }
            }
            // Drop surplus IDLE units (busy ones stay until their drones return).
            for (int i = units.size() - 1; i >= 0 && have > want[t]; i--) {
                SalvageUnit u = units.get(i);
                if (u.tier == t && u.inFlight[0] <= 0) {
                    units.remove(i);
                    have--;
                }
            }
            // Add any missing units for newly-installed modules.
            while (have < want[t]) {
                units.add(new SalvageUnit(t));
                have++;
            }
        }
        if (units.isEmpty()) {
            return;
        }

        // Pass 1: keep valid targets (and claim them so others avoid them).
        java.util.ArrayList<Asteroid> claimed = new java.util.ArrayList<Asteroid>();
        for (int i = 0; i < units.size(); i++) {
            SalvageUnit u = units.get(i);
            if (autosalvager$targetValid(station, u.target, AUTOSALVAGER_RANGE[u.tier - 1])) {
                claimed.add(u.target);
            } else {
                u.target = null;
            }
        }
        // Pass 2: idle units without a wreck grab the nearest un-claimed one.
        for (int i = 0; i < units.size(); i++) {
            SalvageUnit u = units.get(i);
            if (u.inFlight[0] > 0 || u.target != null) {
                continue;
            }
            Asteroid w = autosalvager$findNearestWreckExcluding(station, AUTOSALVAGER_RANGE[u.tier - 1], claimed);
            if (w != null) {
                u.target = w;
                claimed.add(w);
            }
        }
        // Pass 3: launch a formation for each idle unit that now has a target.
        for (int i = 0; i < units.size(); i++) {
            SalvageUnit u = units.get(i);
            if (u.inFlight[0] <= 0 && u.target != null) {
                autosalvager$launchFormation(station, u);
            }
        }

        autosalvager$logStatus(station, units, want);
    }

    /** Log a one-line status per station, but only when it has changed since last pass. */
    @Unique
    private static void autosalvager$logStatus(SpaceShip station, java.util.List<SalvageUnit> units, int[] want) {
        if (!Debug.ENABLED) {
            return;
        }
        int modules = want[1] + want[2] + want[3] + want[4] + want[5];
        StringBuilder sb = new StringBuilder();
        sb.append(modules).append(" module(s), ").append(units.size()).append(" unit(s):");
        for (int i = 0; i < units.size(); i++) {
            SalvageUnit u = units.get(i);
            sb.append(" T").append(u.tier).append('=');
            if (u.inFlight[0] > 0) {
                sb.append("flying(").append(u.inFlight[0]).append(')');
            } else if (u.target != null) {
                sb.append("armed");
            } else {
                sb.append("idle/no-wreck");
            }
        }
        String line = sb.toString();
        if (autosalvager$lastLog == null) {
            autosalvager$lastLog = new WeakHashMap<SpaceShip, String>();
        }
        if (!line.equals(autosalvager$lastLog.get(station))) {
            autosalvager$lastLog.put(station, line);
            Debug.log(line);
        }
    }

    /** Count installed Auto-Salvager modules by tier into {@code want[1..5]}. */
    @Unique
    private static void autosalvager$scanTiers(SpaceShip station, int[] want) {
        if (station.hull == null || station.hull.moduleSlots == null) {
            return;
        }
        SlotList slots = station.hull.moduleSlots;
        int n = slots.numberOf();
        for (int i = 0; i < n; i++) {
            Item it = slots.getItem(i);
            if (it == null) {
                continue;
            }
            int base = it.getBaseID();
            if (base >= AUTOSALVAGER_BASE_ID && base <= AUTOSALVAGER_BASE_ID + 4) {
                want[base - AUTOSALVAGER_BASE_ID + 1]++;
            }
        }
    }

    /** A wreck is a usable target if it's an active salvage still within range. */
    @Unique
    private static boolean autosalvager$targetValid(SpaceShip station, Asteroid a, int radius) {
        if (a == null || !a.isActive() || !a.isSalvage()) {
            return false;
        }
        double dx = a.getX() - station.getX();
        double dy = a.getY() - station.getY();
        return dx * dx + dy * dy <= (double) radius * radius;
    }

    /** Nearest active salvage wreck within {@code radius} that isn't already claimed, or null. */
    @Unique
    private static Asteroid autosalvager$findNearestWreckExcluding(SpaceShip station, int radius,
            java.util.ArrayList<Asteroid> claimed) {
        List<Asteroid> nearby = GameUtil.findAllNearbyAsteroids(station.getX(), station.getY(), radius, false);
        if (nearby == null) {
            return null;
        }
        Asteroid target = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < nearby.size(); i++) {
            Asteroid a = nearby.get(i);
            if (a == null || !a.isActive() || !a.isSalvage() || autosalvager$containsRef(claimed, a)) {
                continue;
            }
            double dx = a.getX() - station.getX();
            double dy = a.getY() - station.getY();
            double d2 = dx * dx + dy * dy;
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                target = a;
            }
        }
        return target;
    }

    /** Reference-identity contains (Asteroid may not override equals). */
    @Unique
    private static boolean autosalvager$containsRef(java.util.ArrayList<Asteroid> list, Asteroid a) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == a) {
                return true;
            }
        }
        return false;
    }

    /**
     * Launch one unit's formation of {@code AUTOSALVAGER_DRONES[tier-1]} drones at its
     * target. Each drone salvages there and deposits on return; the unit's shared
     * counter is set to the number launched and each drone decrements it on landing,
     * so the unit's next formation waits for them all.
     */
    @Unique
    private static void autosalvager$launchFormation(SpaceShip station, SalvageUnit u) {
        try {
            Asteroid target = u.target;
            if (target == null) {
                return;
            }
            int idx = Math.max(0, Math.min(u.tier - 1, AUTOSALVAGER_DRONES.length - 1));
            int groupSize = AUTOSALVAGER_DRONES[idx];
            int bins = AUTOSALVAGER_BINS[idx];
            double luck = AUTOSALVAGER_LUCK[idx];

            // Per-hit wreck damage matches the salvager weapon exactly: on each cut
            // it calls Asteroid.hit(..., (weaponTier+1)*2, ...). We mirror that flat
            // value off the module tier. Same-type wrecks therefore take a fixed
            // number of hits (variation comes from wreck type/hull size, since HP is
            // a fixed per-type constant, not a random roll); the drone adds a small
            // +/-35% jitter so identical wrecks still don't clear in lock-step.
            double wreckDamage = (u.tier + 1) * 2.0;

            int launched = 0;
            for (int i = 0; i < groupSize; i++) {
                try {
                    new autosalvager.fx.SalvageDroneFX(station, target, u.tier, luck, bins, wreckDamage,
                            u.inFlight, i, groupSize);
                    launched++;
                } catch (Throwable ignored) {
                    // a failed spawn must never wedge the counter open
                }
            }
            // Set AFTER construction (drones don't update until the next engine
            // tick, so this can't race their decrements).
            u.inFlight[0] = launched;
            if (Debug.ENABLED) {
                Debug.log("T" + u.tier + " launched " + launched + "/" + groupSize + " drone(s) at wreck ("
                        + (int) target.getX() + "," + (int) target.getY() + "), hits=" + (int) target.hits);
            }
        } catch (Throwable t) {
            Debug.log("launch failed: " + t);
        }
    }
}

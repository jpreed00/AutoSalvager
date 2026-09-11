package autosalvager.fx;

import java.util.ArrayList;

import _tables.LootTable;
import crafting.SalvageSystem;
import game.graphics.GraphicsLoader;
import game.objects.Asteroid;
import game.objects.SpaceShip;
import illuminatus.core.graphics.Blend;
import illuminatus.core.graphics.Color;
import illuminatus.core.objects.EngineObject;
import illuminatus.core.tools.util.Utils;
import items.Item;

/**
 * A salvage drone that mirrors the game's salvager weapon (SalvagerFX): a small
 * craft that flies from the station out to a wreck, works it over (darting from
 * spot to spot and sparking at each), then carries the recovered items home to
 * deposit them into station storage.
 *
 * <p>Drones launch in <b>groups</b> (see {@code AutoSalvageMixin}) but with a
 * short per-drone <b>launch stagger</b>, so a formation strings out into a stream
 * rather than a rigid line bouncing back and forth. The next group only launches
 * once every drone from the previous one has returned (tracked by a shared
 * {@code int[]} counter each drone decrements on {@link #finish()}).</p>
 *
 * <p><b>Lifecycle (per drone):</b> WAIT (idle at the station until its stagger
 * delay elapses) → OUT (fly to the wreck) → WORK (repeatedly dart to a nearby
 * spot and spark there for a random total of 1.2-1.8s, then roll the salvage) →
 * RETURN (fly home) → deposit + finish.</p>
 *
 * <p><b>The harvest/deposit is tied to the drone itself:</b> the drone rolls the
 * salvage at the wreck (wearing it down like the weapon does, gated by
 * {@code prob((tier+1)/hardness)}) and deposits what it carried into
 * {@code owner.cargo} only when it lands back home. So loot literally rides the
 * drone — no drone trip, no salvage.</p>
 *
 * <p><b>Why our own EngineObject instead of reusing SalvagerFX:</b> SalvagerFX
 * draws itself from its {@code WeaponContainer}'s {@code xPos/yPos}, a
 * package-private field written only by the game's {@code WeaponsArray} during
 * mount layout, so positioning a phantom container at a station would require
 * arming/borrowing a real weapon mount — fragile, invasive coupling. An
 * {@link EngineObject} is auto-registered for update+draw on construction
 * (via {@code EngineObjectManager.addInstance} → auto {@code addHandler(...,true,true)}),
 * so a self-contained flyer launched from the station's stable centre is robust
 * and needs no weapon system at all.</p>
 */
public class SalvageDroneFX extends EngineObject {

    // Lifecycle phases.
    private static final int PHASE_WAIT = 0;
    private static final int PHASE_OUT = 1;
    private static final int PHASE_WORK = 2;
    private static final int PHASE_RETURN = 3;

    // Work micro-states: dart to a spot, then spark there.
    private static final int WORK_MOVING = 0;
    private static final int WORK_SPARK = 1;

    /**
     * World units the drone travels per update, indexed by tier (I..V). Matched
     * to (and kept a touch below) the game's fighters, which cruise ~3.0-5.0
     * units/update: each tier adds +0.5, so a bigger module = a faster drone and
     * a shorter trip. A far wreck (up to 4500 range) still takes many seconds.
     */
    private static double droneSpeed(int tierIndex) {
        int idx = tierIndex < 0 ? 0 : (tierIndex > 4 ? 4 : tierIndex);
        return 2.5 + 0.5 * idx; // 2.5, 3.0, 3.5, 4.0, 4.5
    }

    private static final float DRONE_SCALE = 1.5f;

    /** Lateral spacing between drones in a formation (world units). */
    private static final double FORMATION_SPACING = 14.0;

    /** Base per-drone launch stagger, plus a little jitter (nanoseconds). */
    private static final long LAUNCH_GAP_NS = 130_000_000L; // 0.13s between drones

    /** How far (world units) each work "hop" repositions the drone around the wreck. */
    private static final double WORK_HOP_MIN = 6.0;
    private static final double WORK_HOP_MAX = 20.0;

    /** Distance from the drone centre to its nose, where the cutting sparks sit. */
    private static final double SPARK_FRONT_OFFSET = 9.0;

    /** Home-trip speed multiplier when the target expired mid-run (matches the weapon's abort boost). */
    private static final double RETURN_BOOST = 3.0;

    /** Base id of a salvager weapon whose icon we borrow as the drone sprite. */
    private static final int SALVAGER_WEAPON_BASE_ID = 8000;
    private static Item iconItem;

    private final SpaceShip owner;
    private final Asteroid target;
    private final double speed;
    private final int tier;       // 1-based salvage tier (feeds the yield gate + loot roll)
    private final double luck;     // loot-quality factor passed to the loot table
    private final int bins;        // items this drone tries to recover per trip
    private final double wreckDamage; // hits removed from the wreck per successful trip
    /** Shared per-station in-flight counter; decremented once when this drone lands. */
    private final int[] groupCounter;
    /** Constant perpendicular offset so a group flies in formation, not on top of each other. */
    private final double offX;
    private final double offY;

    // Launch stagger + work-time timers (wall-clock, so they're frame-rate independent).
    private final long spawnTimeNs;
    private final long launchDelayNs;
    private final long workDurationNs;
    private long workStartNs;

    // Work-hop state: dart from curOff -> tgtOff, spark, repeat (offsets are relative to the wreck).
    private int workStep = WORK_MOVING;
    private double curOffX;
    private double curOffY;
    private double tgtOffX;
    private double tgtOffY;
    private double moveFromX;
    private double moveFromY;
    private long moveStartNs;
    private long moveDurationNs;
    private long sparkStartNs;
    private long sparkDurationNs;

    // Spark-animation state (mirrors SalvagerFX's harvesting look).
    private final double spinRate;
    private double spin;

    /** Salvage recovered at the wreck, carried home and deposited on landing. */
    private final ArrayList<Item> carried = new ArrayList<Item>();

    private int phase = PHASE_WAIT;
    private double fraction = 0.0;
    private double drawX;
    private double drawY;
    private double heading;       // station->wreck angle (degrees)
    private double lastTargetX;
    private double lastTargetY;
    private boolean harvested = false;
    private boolean aborted = false;   // target expired mid-run -> boosted trip home, no loot
    private boolean finished = false;

    // Previous frame's drone position, so a fast-moving drone can smear the flame
    // along the actual path it covered this frame (fills the inter-frame gap that
    // otherwise makes the flame look detached during the boosted run home). NaN = none.
    private double prevFlameX = Double.NaN;
    private double prevFlameY = Double.NaN;

    public SalvageDroneFX(SpaceShip owner, Asteroid target, int tier, double luck, int bins,
                          double wreckDamage, int[] groupCounter, int indexInGroup, int groupSize) {
        super(owner.getX(), owner.getY());
        this.owner = owner;
        this.target = target;
        this.tier = tier;
        this.luck = luck;
        this.bins = bins < 1 ? 1 : bins;
        this.wreckDamage = wreckDamage > 0.0 ? wreckDamage : 5.0;
        this.speed = droneSpeed(tier - 1);
        this.groupCounter = groupCounter;
        this.drawX = owner.getX();
        this.drawY = owner.getY();
        this.lastTargetX = target != null ? target.getX() : owner.getX();
        this.lastTargetY = target != null ? target.getY() : owner.getY();

        // Fan the group out perpendicular to the flight axis so they read as
        // several craft rather than a single blob.
        double baseHeading = target != null
                ? Utils.directionDegrees(owner.getX(), owner.getY(), target.getX(), target.getY())
                : 0.0;
        int gs = groupSize < 1 ? 1 : groupSize;
        double lateral = (indexInGroup - (gs - 1) / 2.0) * FORMATION_SPACING;
        int perp = (int) (baseHeading + 90.0);
        this.offX = Utils.fastLengthDegreesX(0.0, lateral, perp);
        this.offY = Utils.fastLengthDegreesY(0.0, lateral, perp);

        // Stagger this drone's departure so the formation leaves as a stream, and
        // give it a random total "work" duration for animation variety.
        this.spawnTimeNs = System.nanoTime();
        this.launchDelayNs = (long) (indexInGroup * LAUNCH_GAP_NS + Utils.random(0.0, 0.08) * 1_000_000_000.0);
        this.workDurationNs = (long) (Utils.random(1.2, 1.8) * 1_000_000_000.0);

        // Randomised spin rate, matching SalvagerFX's constructor range.
        this.spinRate = Utils.random(-1.5, 1.5);
        this.spin = baseHeading; // start the tool pointed at the wreck, then rotate
    }

    @Override
    public void update() {
        try {
            if (finished) {
                return;
            }
            if (owner == null) {
                finish();
                return;
            }

            boolean targetValid = target != null && target.isActive();
            double tx = targetValid ? target.getX() : lastTargetX;
            double ty = targetValid ? target.getY() : lastTargetY;
            if (targetValid) {
                lastTargetX = tx;
                lastTargetY = ty;
            }

            // Target-gone short-circuit (like the weapon's abort): the instant the
            // wreck expires, stop working and head straight home empty-handed, with
            // a speed boost for the trip back.
            if (!targetValid && !aborted) {
                aborted = true;
                if (phase == PHASE_OUT || phase == PHASE_WORK) {
                    phase = PHASE_RETURN;
                }
            }

            double ox = owner.getX();
            double oy = owner.getY();
            heading = Utils.directionDegrees(ox, oy, tx, ty);
            double dist = Utils.distance2D(ox, oy, tx, ty);
            double step = dist > 1.0 ? (speed / dist) : 1.0;
            long now = System.nanoTime();

            switch (phase) {
                case PHASE_WAIT:
                    // Idle at the station until this drone's stagger delay elapses.
                    if (now - spawnTimeNs >= launchDelayNs) {
                        phase = PHASE_OUT;
                    }
                    break;
                case PHASE_OUT:
                    fraction += step;
                    if (fraction >= 1.0) {
                        fraction = 1.0;
                        phase = PHASE_WORK;
                        workStartNs = now;
                        beginWorkHop(now); // dart to the first work spot
                    }
                    break;
                case PHASE_WORK:
                    updateWork(now);
                    if (now - workStartNs >= workDurationNs) {
                        harvestAtWreck();
                        phase = PHASE_RETURN;
                    }
                    break;
                case PHASE_RETURN:
                default:
                    // Zip home faster when the target expired mid-run.
                    double retStep = dist > 1.0 ? ((aborted ? speed * RETURN_BOOST : speed) / dist) : 1.0;
                    fraction -= retStep;
                    if (fraction <= 0.0) {
                        depositAtHome();
                        finish();
                        return;
                    }
                    break;
            }

            drawX = ox + (tx - ox) * fraction;
            drawY = oy + (ty - oy) * fraction;
            if (phase == PHASE_WORK) {
                drawX += curOffX;
                drawY += curOffY;
            }
            setPosition(drawX + offX, drawY + offY);
        } catch (Throwable t) {
            System.out.println("[AutoSalvager] drone update failed: " + t);
            finish();
        }
    }

    /** Pick a fresh spot near the wreck and start darting toward it. */
    private void beginWorkHop(long now) {
        moveFromX = curOffX;
        moveFromY = curOffY;
        double dir = Utils.random(0.0, 360.0);
        double d = Utils.random(WORK_HOP_MIN, WORK_HOP_MAX);
        tgtOffX = Utils.lengthDegreesX(0.0, d, dir);
        tgtOffY = Utils.lengthDegreesY(0.0, d, dir);
        workStep = WORK_MOVING;
        moveStartNs = now;
        moveDurationNs = (long) (Utils.random(0.14, 0.26) * 1_000_000_000.0);
    }

    /** Advance the dart-then-spark cycle and the spark animation. */
    private void updateWork(long now) {
        spin += spinRate;

        if (workStep == WORK_MOVING) {
            double p = moveDurationNs > 0 ? (now - moveStartNs) / (double) moveDurationNs : 1.0;
            if (p >= 1.0) {
                curOffX = tgtOffX;
                curOffY = tgtOffY;
                workStep = WORK_SPARK;
                sparkStartNs = now;
                sparkDurationNs = (long) (Utils.random(0.18, 0.34) * 1_000_000_000.0);
            } else {
                double s = p * p * (3.0 - 2.0 * p); // smoothstep for a snappy-but-eased dart
                curOffX = moveFromX + (tgtOffX - moveFromX) * s;
                curOffY = moveFromY + (tgtOffY - moveFromY) * s;
            }
        } else { // WORK_SPARK: hold and spark, then hop again
            if (now - sparkStartNs >= sparkDurationNs) {
                beginWorkHop(now);
            }
        }
    }

    /**
     * Salvage the wreck (called once, when the work animation finishes): wear the
     * wreck down (explode when depleted), roll the authentic {@code (tier+1)/
     * hardness} yield gate, and on success pull {@code bins} items to carry home.
     */
    private void harvestAtWreck() {
        if (harvested) {
            return;
        }
        harvested = true;
        try {
            if (target == null || !target.isActive() || !target.isSalvage()) {
                return;
            }

            // Roll the yield gate and pull the bins FIRST, so a drone that lands the
            // killing blow still brings its haul home (and every drone that reaches
            // an active wreck gets a fair shot before it's destroyed). Authentic gate:
            // tough wrecks resist, higher tiers extract more reliably; soft wrecks
            // (hardness <= tier+1) always yield.
            int hardness = target.getHardness();
            double yieldChance = (hardness <= 0) ? 1.0 : (tier + 1.0) / hardness;
            if (Utils.prob(yieldChance)) {
                for (int k = 0; k < bins; k++) {
                    Item salvage = SalvageSystem.getTargetSpecificSalvage(target);
                    if (salvage == null) {
                        salvage = LootTable.pickRandomScrapOrSalvage(tier, owner, luck);
                    }
                    if (salvage == null) {
                        break;
                    }
                    carried.add(salvage);
                }
            }

            // Then wear the wreck down. Like the salvager weapon (Asteroid.hit with
            // isSalvager=true), wreckDamage is the flat per-hit value (tier+1)*2. The
            // weapon's own per-cut damage is deterministic, but we jitter each
            // drone-hit +/-35% so identical-type wrecks don't clear in lock-step.
            // Explode at 0.
            double dealt = wreckDamage * Utils.random(0.65, 1.35);
            target.hits -= dealt;
            if (target.hits <= 0.0) {
                target.explode(owner, true, 1.0f, false);
            }
        } catch (Throwable t) {
            System.out.println("[AutoSalvager] drone harvest failed: " + t);
        }
    }

    /** Deposit everything carried into the station's cargo when the drone lands. */
    private void depositAtHome() {
        try {
            if (owner == null || owner.cargo == null || carried.isEmpty()) {
                return;
            }
            for (int i = 0; i < carried.size(); i++) {
                owner.cargo.add(carried.get(i), true);
            }
            carried.clear();
            owner.cargo.refresh();
            owner.cargo.updateFlagFull();
        } catch (Throwable t) {
            System.out.println("[AutoSalvager] drone deposit failed: " + t);
        }
    }

    @Override
    public void draw(double a, double b) {
        try {
            Item icon = icon();
            if (icon == null) {
                return;
            }
            double px = drawX + offX;
            double py = drawY + offY;

            if (phase == PHASE_WORK && workStep == WORK_SPARK) {
                drawSparking(icon, px, py);
            } else if (phase == PHASE_WORK) {
                // Darting between work spots: face the hop direction, small thruster.
                drawFlame(px, py, Utils.directionDegrees(moveFromX, moveFromY, tgtOffX, tgtOffY), -4.0);
                Color.WHITE.use(1.0f);
                icon.drawIconAsMount(px, py, Utils.directionDegrees(moveFromX, moveFromY, tgtOffX, tgtOffY),
                        DRONE_SCALE);
            } else {
                drawFlying(icon, px, py);
            }
        } catch (Throwable ignored) {
            // never let a cosmetic draw crash the render loop
        }
    }

    /** Sparking-at-a-work-spot look, recreated from SalvagerFX's harvesting draw. */
    private void drawSparking(Item icon, double px, double py) {
        // Sparks sit at the drone's nose (offset forward along its facing), not its
        // centre, with a small per-frame jitter so they stay attached while working.
        double frontX = Utils.lengthDegreesX(px, SPARK_FRONT_OFFSET, spin);
        double frontY = Utils.lengthDegreesY(py, SPARK_FRONT_OFFSET, spin);
        Blend.ADDITIVE.use();
        Color.LT_YELLOW.use((float) Utils.random(0.15, 0.35));
        GraphicsLoader.PARTICLE.drawScaledRotated(
                frontX + Utils.random(-3.5, 3.5), frontY + Utils.random(-3.5, 3.5),
                1.8, 1.8, Utils.random(360));
        // Bright elongated core spark right on the nose.
        Color.WHITE.use(0.5f);
        GraphicsLoader.PARTICLE.drawScaledRotated(frontX, frontY, 0.5, 1.0, Utils.random(360));
        Blend.NORMAL.use();

        // The salvager icon rotating as it cuts (translation comes from the hops).
        Color.WHITE.use(1.0f);
        icon.drawIconAsMount(px, py, spin, DRONE_SCALE);
    }

    /** Cruising look: an additive engine flame trailing behind, icon nose-forward. */
    private void drawFlying(Item icon, double px, double py) {
        if (phase == PHASE_OUT || phase == PHASE_RETURN) {
            drawFlame(px, py, heading, (phase == PHASE_OUT) ? -4.0 : 4.0);
        }
        Color.WHITE.use(1.0f);
        double facing = (phase == PHASE_RETURN) ? heading + 180.0 : heading;
        icon.drawIconAsMount(px, py, facing, DRONE_SCALE);
    }

    /**
     * Additive engine flame. At cruise it's a single glow just behind the engine
     * (matching the salvager weapon). When the drone covers a lot of ground in one
     * frame (the boosted run home), the flame is instead drawn as a streak laid
     * along the exact path it travelled this frame, with its bright head on the
     * drone itself and fading out toward where it just was — an attached afterburner,
     * with no separate glow lagging a step behind.
     */
    private void drawFlame(double px, double py, double dirDeg, double offset) {
        int dir = (int) dirDeg;
        Blend.ADDITIVE.use();
        double gap = Double.isNaN(prevFlameX) ? 0.0 : Utils.distance2D(prevFlameX, prevFlameY, px, py);
        if (gap > 6.0 && gap < 40.0) {
            // Fast motion: streak from where the drone was up to where it is now.
            // Head (t=1) sits on the drone so it stays attached; tail fades out.
            int n = (int) Math.min(6, Math.ceil(gap / 5.0));
            for (int i = 1; i <= n; i++) {
                double t = i / (double) n;                 // 0..1 from tail -> drone
                double gx = prevFlameX + (px - prevFlameX) * t;
                double gy = prevFlameY + (py - prevFlameY) * t;
                Color.WHITE.use((float) (0.8 * (0.35 + 0.65 * t)));
                GraphicsLoader.EXPLOSION.drawScaledRotated(gx, gy, 0.4, 0.4, Utils.random(360), 16.0, 16.0);
            }
        } else {
            // Cruise (or a phase jump we shouldn't smear across): a single glow behind.
            Color.WHITE.use(0.8f);
            GraphicsLoader.EXPLOSION.drawScaledRotated(
                    Utils.fastLengthDegreesX(px, offset, dir),
                    Utils.fastLengthDegreesY(py, offset, dir),
                    0.5, 0.5, Utils.random(360), 16.0, 16.0);
        }
        Blend.NORMAL.use();

        prevFlameX = px;
        prevFlameY = py;
    }

    @Override
    public void setup() {
        // nothing to initialise beyond the constructor
    }

    @Override
    public void onChange() {
        // no sprite-sheet / state changes to react to
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (groupCounter != null && groupCounter[0] > 0) {
            groupCounter[0]--;
        }
        try {
            destroy();
        } catch (Throwable ignored) {
            // no-op
        }
    }

    /** Lazily load (once) a salvager weapon item to borrow its icon as the drone sprite. */
    private static Item icon() {
        if (iconItem == null) {
            try {
                Item it = new Item(SALVAGER_WEAPON_BASE_ID);
                it.loadFromDatabase();
                iconItem = it;
            } catch (Throwable t) {
                return null;
            }
        }
        return iconItem;
    }
}

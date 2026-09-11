package autosalvager.fx;

import game.objects.Asteroid;

/**
 * Per-module Auto-Salvager state. One of these exists for each Auto-Salvager
 * module installed in a station, so several modules run several independent
 * formations at once (each working its own wreck).
 *
 * <p>Deliberately kept in {@code autosalvager.fx}, NOT the mixin package: Mixin
 * runs its transformer over every class in a config's {@code package}
 * ({@code autosalvager.mixin}) and would try (and fail) to transform this plain
 * helper. It's {@code public} because the mixin's {@code @Unique} methods are
 * merged into {@code crafting.SalvageSystem} at load time and reference it across
 * packages.</p>
 */
public final class SalvageUnit {

    /** Module tier, 1..5 (drives formation size, bins, range, luck, damage). */
    public final int tier;

    /** This unit's in-flight drone count; the drones share and decrement it on landing. */
    public final int[] inFlight = new int[]{0};

    /** The wreck this unit is currently working (kept until it expires / leaves range). */
    public Asteroid target;

    public SalvageUnit(int tier) {
        this.tier = tier;
    }
}

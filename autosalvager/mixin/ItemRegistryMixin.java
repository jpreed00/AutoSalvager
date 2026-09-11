package autosalvager.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import _database.ItemDatabase;
import illuminatus.core.graphics.Color;
import items.TypeTag;
import items.lists.ModuleList;

/**
 * Registers five new station-only "Auto-Salvager" module items (tiers I-V) into
 * the item database, cloning the game's own Auto-Miner definitions so the stats
 * ramp identically.
 *
 * <p>The Auto-Miner is defined in {@link ModuleList#writeToDatabase()} via the
 * static {@code ModuleList.write(...)} helper with base IDs 9001-9005. We reuse
 * that same helper (so our items get the exact same schema, station-only flag,
 * rarity colouring, etc.) with the unused base IDs 9006-9010.</p>
 *
 * <p>We inject at the TAIL of {@link ItemDatabase#loadDatabase()} rather than the
 * version-gated database rebuild ({@code runUpdate}); {@code write(...)} adds the
 * entries to the in-memory {@code itemDataFile} that was just loaded, so a fresh
 * {@code new Item(9006).loadFromDatabase()} resolves every launch.</p>
 */
@Mixin(value = ItemDatabase.class, remap = false)
public class ItemRegistryMixin {

    /** Base id of Auto-Salvager I; tiers II-V are the next four ids. */
    @Unique
    private static final int AUTOSALVAGER_BASE_ID = 9006;

    @Inject(method = "loadDatabase", at = @At("TAIL"))
    private static void autosalvager$register(CallbackInfo ci) {
        try {
            autosalvager$writeTiers();
        } catch (Throwable t) {
            System.out.println("[AutoSalvager] Failed to register module items: " + t);
        }
    }

    /**
     * Mirrors the Auto-Miner write calls exactly (icon index, useLevel, rarity
     * tag, volume, credit value, power stat, and the three trailing flags), only
     * swapping in salvager naming/description and the tier's max salvage tier.
     * Icon indices 480-484 are reused from the Auto-Miner sprite.
     */
    @Unique
    private static void autosalvager$writeTiers() {
        ModuleList.write(AUTOSALVAGER_BASE_ID, 480, Color.WHITE, "Auto-Salvager I",
            "Salvage Max Tier: 1. Deploys 2 salvage drones to wrecks within 2500\u0001range; each recovers up to 1 item per trip (odds scale with tier vs. wreck toughness) and delivers it to station storage on return.",
            2, TypeTag.COMMON, 25.0, 500000L, 15.0f, true, true, false);
        ModuleList.write(AUTOSALVAGER_BASE_ID + 1, 481, Color.WHITE, "Auto-Salvager II",
            "Salvage Max Tier: 2. Deploys 3 salvage drones to wrecks within 3000\u0001range; each recovers up to 1 item per trip (odds scale with tier vs. wreck toughness) and delivers it to station storage on return.",
            3, TypeTag.UNCOMMON, 30.0, 1200000L, 25.0f, true, true, false);
        ModuleList.write(AUTOSALVAGER_BASE_ID + 2, 482, Color.WHITE, "Auto-Salvager III",
            "Salvage Max Tier: 3. Deploys 4 salvage drones to wrecks within 3500\u0001range; each recovers up to 2 items per trip (odds scale with tier vs. wreck toughness) and delivers them to station storage on return.",
            4, TypeTag.RARE, 35.0, 2400000L, 35.0f, true, true, false);
        ModuleList.write(AUTOSALVAGER_BASE_ID + 3, 483, Color.WHITE, "Auto-Salvager IV",
            "Salvage Max Tier: 4. Deploys 5 salvage drones to wrecks within 4000\u0001range; each recovers up to 2 items per trip (odds scale with tier vs. wreck toughness) and delivers them to station storage on return.",
            5, TypeTag.EXOTIC, 40.0, 4800000L, 45.0f, true, true, false);
        ModuleList.write(AUTOSALVAGER_BASE_ID + 4, 484, Color.WHITE, "Auto-Salvager V",
            "Salvage Max Tier: 5. Deploys 6 salvage drones to wrecks within 4500\u0001range; each recovers up to 3 items per trip (odds scale with tier vs. wreck toughness) and delivers them to station storage on return.",
            6, TypeTag.LEGENDARY, 45.0, 9600000L, 55.0f, true, true, false);
    }
}

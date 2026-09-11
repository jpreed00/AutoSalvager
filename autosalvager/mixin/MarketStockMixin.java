package autosalvager.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import game.markets.Market;
import game.markets.MarketDatabase;
import game.markets.MarketItem;
import illuminatus.core.datastructures.List;

/**
 * Stocks the Auto-Salvager modules for sale at NPC Industrial stations, exactly
 * where the game stocks the Auto-Miner. {@code MarketList.writeIndustrialStation}
 * adds the Auto-Miner via {@code market.add(99001, ...)} where the market id is
 * the module base id + 90000; so our base ids 9006-9010 map to 99006-99010.
 *
 * <p>We inject at the TAIL of {@link MarketDatabase#loadDatabase()} and append the
 * entries directly to the freshly-loaded in-memory {@code markets} list. The
 * game's {@code MarketRandomizer} generates each station's stock from those
 * {@link Market} objects, so the modules then appear for sale at Industrial
 * stations. This runs after {@link _database.ItemDatabase#loadDatabase()} has
 * registered the items, so the market entries resolve to real items.</p>
 */
@Mixin(value = MarketDatabase.class, remap = false)
public class MarketStockMixin {

    @Shadow
    private static List<Market> markets;

    @Inject(method = "loadDatabase", at = @At("TAIL"))
    private static void autosalvager$stock(CallbackInfo ci) {
        try {
            if (markets == null) {
                return;
            }
            int stocked = 0;
            for (int i = 0; i < markets.size(); i++) {
                Market m = markets.get(i);
                if (m == null) {
                    continue;
                }
                if (m.getMarketType() == Market.INDUSTRIAL_MARKET) {
                    // 99006..99010 == Auto-Salvager I..V (base id + 90000).
                    m.add(99006, MarketItem.PRODUCES_ALWAYS);
                    m.add(99007, MarketItem.PRODUCES_ALWAYS);
                    m.add(99008, MarketItem.PRODUCES_ALWAYS);
                    m.add(99009, MarketItem.PRODUCES_ALWAYS);
                    m.add(99010, MarketItem.PRODUCES_ALWAYS);
                    stocked++;
                }
            }
            System.out.println("[AutoSalvager] Stocked Auto-Salvager modules in " + stocked + " industrial market(s).");
        } catch (Throwable t) {
            System.out.println("[AutoSalvager] Failed to stock markets: " + t);
        }
    }
}

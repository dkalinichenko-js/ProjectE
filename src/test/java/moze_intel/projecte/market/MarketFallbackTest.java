package moze_intel.projecte.market;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import moze_intel.projecte.market.math.DynamicMarketMath;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MarketFallbackTest {
    @Test
    void fillsOnlyMissingPositiveLegacyValuesInInitializedWorld() {
        MarketSavedData data = new MarketSavedData();
        data.initialized = true;
        data.liquidity = 31.5;
        data.indexTarget = 512.5;
        data.indexBasket.put("minecraft:diamond", 2.5);
        data.fractions.put("player:example", new BigDecimal("0.375"));
        data.entries.put("minecraft:diamond", new MarketSavedData.Entry(
                new double[] {12, 34, 56}, new double[] {0.2, 0.3, 0.5}, "original prior", 47));
        // Exercise an already initialized world after its SavedData has been reloaded.
        data = MarketSavedData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
        MarketSavedData.Entry existing = data.entries.get("minecraft:diamond");
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("minecraft:iron_ingot", 100L);
        values.put("minecraft:diamond", 2_000L);
        values.put("minecraft:dirt", 0L);
        values.put("minecraft:cobblestone", -1L);

        Assertions.assertEquals(1, MarketFallback.addMissing(data, values, 0.25));
        Assertions.assertSame(existing, data.entries.get("minecraft:diamond"));
        Assertions.assertArrayEquals(new double[] {12, 34, 56}, existing.prices);
        Assertions.assertArrayEquals(new double[] {0.2, 0.3, 0.5}, existing.weights);
        Assertions.assertEquals(47, existing.inventory);
        Assertions.assertEquals(31.5, data.liquidity);
        Assertions.assertEquals(512.5, data.indexTarget);
        Assertions.assertEquals(Map.of("minecraft:diamond", 2.5), data.indexBasket);
        Assertions.assertEquals(Map.of("player:example", new BigDecimal("0.375")), data.fractions);
        Assertions.assertTrue(data.initialized);
        Assertions.assertTrue(data.isDirty());

        MarketSavedData.Entry fallback = data.entries.get("minecraft:iron_ingot");
        Assertions.assertNotNull(fallback);
        Assertions.assertArrayEquals(new double[] {75, 100, 125}, fallback.prices);
        Assertions.assertArrayEquals(new double[] {1, 1, 1}, fallback.weights);
        Assertions.assertEquals(0, fallback.inventory);
        Assertions.assertTrue(fallback.provenance.startsWith("legacy EMC fallback"));
        Assertions.assertFalse(data.entries.containsKey("minecraft:dirt"));
        Assertions.assertFalse(data.entries.containsKey("minecraft:cobblestone"));

        Assertions.assertEquals(0, MarketFallback.addMissing(data, values, 0.5));
        Assertions.assertSame(existing, data.entries.get("minecraft:diamond"));
        Assertions.assertSame(fallback, data.entries.get("minecraft:iron_ingot"));
        Assertions.assertArrayEquals(new double[] {75, 100, 125}, fallback.prices);
    }

    @Test
    void frozenFallbackHasBidAskSpreadAndRoundTripCannotProfit() {
        MarketSavedData data = new MarketSavedData();
        Assertions.assertEquals(1, MarketFallback.addMissing(data, Map.of("minecraft:iron_ingot", 100L), 0.25));
        MarketSavedData.Entry fallback = data.entries.get("minecraft:iron_ingot");
        DynamicMarketMath model = new DynamicMarketMath(fallback.prices, fallback.weights, data.liquidity);
        Assertions.assertEquals(75, model.bid(0));
        Assertions.assertEquals(125, model.ask(0));
        Assertions.assertTrue(model.quoteBuyReal(1, 1).compareTo(model.quoteSellReal(0, 1)) >= 0);
    }

    @Test
    void largeValuesRemainFiniteAndInvalidSpreadsAreRejected() {
        MarketSavedData data = new MarketSavedData();
        Assertions.assertEquals(1, MarketFallback.addMissing(data, Map.of("test:max", Long.MAX_VALUE), 0.25));
        for (double price : data.entries.get("test:max").prices) {
            Assertions.assertTrue(Double.isFinite(price) && price > 0);
        }
        for (double spread : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, 0, -0.1, 1, 1.1}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> MarketFallback.addMissing(new MarketSavedData(), Map.of("test:item", 1L), spread));
        }
    }
}

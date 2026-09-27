package moze_intel.projecte.market;

import java.math.BigDecimal;

import java.math.BigInteger;
import moze_intel.projecte.market.math.DynamicMarketMath;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MarketSavedDataTest {

	@Test
	void saveLoadPreservesFrozenPriorInventoryAndQuotes() {
		MarketSavedData before = new MarketSavedData();
		before.initialized = true;
		before.liquidity = 31.5;
		before.indexTarget = 512.5;
		before.indexBasket.put("minecraft:copper_ingot", 2.5);
		before.indexBasket.put("minecraft:iron_ingot", 1.25);
		before.fractions.put("player:example", new BigDecimal("0.37500000000000000000001"));
		double[] prices = {12.25, 45.5, 120.75};
		double[] weights = {0.2, 0.3, 0.5};
		before.entries.put("minecraft:diamond",
				new MarketSavedData.Entry(prices, weights, "recipe valuation: test", 47));
		// The entry must freeze its prior rather than retaining mutable caller arrays.
		prices[0] = 999;
		weights[0] = 0.9;

		MarketSavedData.Entry original = before.entries.get("minecraft:diamond");
		DynamicMarketMath oldMath = new DynamicMarketMath(original.prices, original.weights, before.liquidity);
		BigInteger oldSell = oldMath.quoteSell(original.inventory, 3);
		BigInteger oldBuy = oldMath.quoteBuy(original.inventory, 3);

		CompoundTag serialized = before.save(new CompoundTag(), RegistryAccess.EMPTY);
		MarketSavedData after = MarketSavedData.load(serialized, RegistryAccess.EMPTY);
		MarketSavedData.Entry loaded = after.entries.get("minecraft:diamond");

		Assertions.assertTrue(after.initialized);
		Assertions.assertEquals(31.5, after.liquidity);
		Assertions.assertEquals(512.5, after.indexTarget);
		Assertions.assertEquals(before.indexBasket, after.indexBasket);
		Assertions.assertEquals(before.fractions, after.fractions);
		Assertions.assertEquals(1, after.entries.size());
		Assertions.assertNotNull(loaded);
		Assertions.assertArrayEquals(new double[] {12.25, 45.5, 120.75}, loaded.prices);
		Assertions.assertArrayEquals(new double[] {0.2, 0.3, 0.5}, loaded.weights);
		Assertions.assertEquals("recipe valuation: test", loaded.provenance);
		Assertions.assertEquals(47, loaded.inventory);

		DynamicMarketMath restoredMath = new DynamicMarketMath(loaded.prices, loaded.weights, after.liquidity);
		Assertions.assertEquals(oldSell, restoredMath.quoteSell(loaded.inventory, 3));
		Assertions.assertEquals(oldBuy, restoredMath.quoteBuy(loaded.inventory, 3));
		Assertions.assertArrayEquals(oldMath.posteriorWeights(original.inventory), restoredMath.posteriorWeights(loaded.inventory));
	}
}

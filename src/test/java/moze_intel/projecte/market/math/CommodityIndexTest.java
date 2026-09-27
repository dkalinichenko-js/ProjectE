package moze_intel.projecte.market.math;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommodityIndexTest {

	@Test
	void weightedGeometricIndexHitsTargetAndKeepsRatios() {
		double[] prices = {2, 8, 32};
		double[] weights = {1, 2, 1};
		Assertions.assertEquals(8, CommodityIndex.index(prices, weights), 1e-12);
		double scale = CommodityIndex.scale(prices, weights, 200);
		Assertions.assertEquals(25, scale, 1e-12);
		Assertions.assertEquals(200, CommodityIndex.index(new double[] {
				prices[0] * scale, prices[1] * scale, prices[2] * scale}, weights), 1e-10);
		Assertions.assertEquals(prices[2] / prices[0], (prices[2] * scale) / (prices[0] * scale), 0);
		// A recipe cost is an additive sum, so a common multiplicative unit
		// conversion preserves its ratio to every other additive recipe cost.
		double recipeA = 3 * prices[0] + prices[1];
		double recipeB = prices[1] + 2 * prices[2];
		Assertions.assertEquals(recipeA / recipeB, (recipeA * scale) / (recipeB * scale), 1e-15);
	}

	@Test
	void uniformPriceChangeProducesReciprocalScale() {
		double[] weights = {1, 3, 2};
		double[] original = {7, 13, 29};
		double[] tripled = {21, 39, 87};
		Assertions.assertEquals(CommodityIndex.scale(original, weights, 500) / 3,
				CommodityIndex.scale(tripled, weights, 500), 1e-12);
	}

	@Test
	void restoredInventoriesRestoreExactScale() {
		DynamicMarketMath copper = new DynamicMarketMath(new double[] {10, 20, 40}, new double[] {1, 3, 1}, 3);
		DynamicMarketMath iron = new DynamicMarketMath(new double[] {80, 160, 320}, new double[] {1, 3, 1}, 3);
		double[] basketWeights = {2, 1};
		double initial = CommodityIndex.scale(new double[] {copper.median(0), iron.median(0)}, basketWeights, 100);
		CommodityIndex.scale(new double[] {copper.median(20), iron.median(-20)}, basketWeights, 100);
		double restored = CommodityIndex.scale(new double[] {copper.median(0), iron.median(0)}, basketWeights, 100);
		Assertions.assertEquals(initial, restored, 0);
	}

	@Test
	void validatesAndNormalizesExtremeWeights() {
		Assertions.assertEquals(1e300, CommodityIndex.index(new double[] {1e300, 1e300},
				new double[] {Double.MAX_VALUE, Double.MAX_VALUE}), 1e286);
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CommodityIndex.index(new double[] {1, 0}, new double[] {1, 1}));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CommodityIndex.index(new double[] {1}, new double[] {0}));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> CommodityIndex.scale(new double[] {1}, new double[] {1}, Double.NaN));
	}

	@Test
	void realQuotesSumFractionalEdgesWithoutUnitRounding() {
		DynamicMarketMath market = new DynamicMarketMath(new double[] {0.125}, new double[] {1}, 1);
		Assertions.assertEquals(0, market.quoteSellReal(0, 3).compareTo(new BigDecimal("0.375")));
		Assertions.assertEquals(0, market.quoteBuyReal(3, 3).compareTo(new BigDecimal("0.375")));
		Assertions.assertEquals(BigDecimal.ZERO, market.quoteBuyReal(0, 0));
		DynamicMarketMath huge = new DynamicMarketMath(new double[] {1e300}, new double[] {1}, 1);
		Assertions.assertEquals(0, huge.quoteSellReal(0, 2).compareTo(BigDecimal.valueOf(1e300).multiply(BigDecimal.valueOf(2))));
		DynamicMarketMath varied = new DynamicMarketMath(new double[] {1.25, 3.5, 8.75}, new double[] {1, 2, 1}, 4);
		BigDecimal whole = varied.quoteSellReal(-5, 12);
		BigDecimal split = varied.quoteSellReal(-5, 4).add(varied.quoteSellReal(-1, 8));
		Assertions.assertEquals(0, whole.compareTo(split));
	}
}

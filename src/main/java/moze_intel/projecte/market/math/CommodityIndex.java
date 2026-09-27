package moze_intel.projecte.market.math;

/**
 * A stateless unit conversion for a fixed basket of current real central prices.
 * The basket index is a weighted geometric mean; a target divided by that index
 * scales every price equally. With no temporal state, restoring the same prices
 * restores exactly the same scale.
 */
public final class CommodityIndex {

	private CommodityIndex() {
	}

	public static double index(double[] prices, double[] weights) {
		if (prices == null || weights == null || prices.length == 0 || prices.length != weights.length) {
			throw new IllegalArgumentException("Basket prices and weights must have the same nonzero length");
		}
		double maxWeight = 0;
		double minLog = Double.POSITIVE_INFINITY;
		double maxLog = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < prices.length; i++) {
			if (!Double.isFinite(prices[i]) || prices[i] <= 0 || !Double.isFinite(weights[i]) || weights[i] < 0) {
				throw new IllegalArgumentException("Basket prices must be finite and positive; weights finite and nonnegative");
			}
			maxWeight = Math.max(maxWeight, weights[i]);
			double log = Math.log(prices[i]);
			minLog = Math.min(minLog, log);
			maxLog = Math.max(maxLog, log);
		}
		if (maxWeight == 0) {
			throw new IllegalArgumentException("Basket weights must have positive total");
		}
		if (minLog == maxLog) {
			return prices[0];
		}
		// Divide first so even a collection of near-MAX_VALUE weights cannot
		// overflow its normalization. Anchor the logs to avoid cancellation.
		double anchor = Math.log(prices[0]);
		double numerator = 0;
		double numeratorCorrection = 0;
		double denominator = 0;
		double denominatorCorrection = 0;
		for (int i = 0; i < prices.length; i++) {
			double weight = weights[i] / maxWeight;
			double term = weight * (Math.log(prices[i]) - anchor);
			double adjustedNumerator = term - numeratorCorrection;
			double nextNumerator = numerator + adjustedNumerator;
			numeratorCorrection = (nextNumerator - numerator) - adjustedNumerator;
			numerator = nextNumerator;
			double adjustedDenominator = weight - denominatorCorrection;
			double nextDenominator = denominator + adjustedDenominator;
			denominatorCorrection = (nextDenominator - denominator) - adjustedDenominator;
			denominator = nextDenominator;
		}
		double logIndex = Math.max(minLog, Math.min(maxLog, anchor + numerator / denominator));
		double value = Math.exp(logIndex);
		// A weighted geometric mean lies between its extreme prices; these
		// guards only correct rounding at the representable double boundaries.
		if (Double.isInfinite(value)) {
			return Double.MAX_VALUE;
		}
		return value == 0 ? Double.MIN_VALUE : value;
	}

	public static double scale(double[] prices, double[] weights, double target) {
		if (!Double.isFinite(target) || target <= 0) {
			throw new IllegalArgumentException("Target index must be finite and positive");
		}
		double factor = target / index(prices, weights);
		if (!Double.isFinite(factor) || factor <= 0) {
			throw new IllegalArgumentException("Target scale is outside the positive double range");
		}
		return factor;
	}
}

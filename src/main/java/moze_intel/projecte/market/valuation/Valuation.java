package moze_intel.projecte.market.valuation;

import java.util.List;

/** A small discrete price distribution, in abstract market units per item. */
public record Valuation(double[] prices, double[] weights, List<String> provenance) {

	public Valuation {
		if (prices.length == 0 || prices.length != weights.length) {
			throw new IllegalArgumentException("Prices and weights must have the same nonzero length");
		}
		prices = prices.clone();
		weights = weights.clone();
		provenance = List.copyOf(provenance);
		double total = 0;
		for (int i = 0; i < prices.length; i++) {
			if (!Double.isFinite(prices[i]) || prices[i] <= 0 || !Double.isFinite(weights[i]) || weights[i] < 0) {
				throw new IllegalArgumentException("Invalid price distribution");
			}
			total += weights[i];
		}
		if (!Double.isFinite(total) || total <= 0) {
			throw new IllegalArgumentException("Price weights must have positive total");
		}
		for (int i = 0; i < weights.length; i++) {
			weights[i] /= total;
		}
	}

	@Override
	public double[] prices() {
		return prices.clone();
	}

	@Override
	public double[] weights() {
		return weights.clone();
	}
}

package moze_intel.projecte.market.math;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * An immutable, discrete price model. Positive inventory displacement means units
 * have been sold to the exchange. Its prior and the current displacement completely
 * determine the posterior; a sequence of earlier trades cannot change a quote.
 */
public final class DynamicMarketMath {

    public static final double BID_QUANTILE = 0.1;
    public static final double ASK_QUANTILE = 0.9;
    public static final long DEFAULT_MAX_ORDER_UNITS = 100_000;

    private final double[] prices;
    private final double[] logPrices;
    private final double[] logPriorWeights;
    private final double liquidityScale;
    private final long maxOrderUnits;

    public DynamicMarketMath(double[] prices, double[] priorWeights, double liquidityScale) {
        this(prices, priorWeights, liquidityScale, DEFAULT_MAX_ORDER_UNITS);
    }

    public DynamicMarketMath(double[] prices, double[] priorWeights, double liquidityScale, long maxOrderUnits) {
        if (prices == null || priorWeights == null || prices.length == 0 || prices.length != priorWeights.length) {
            throw new IllegalArgumentException("Prices and prior weights must be nonempty arrays of equal length");
        }
        if (!Double.isFinite(liquidityScale) || liquidityScale <= 0 || maxOrderUnits <= 0) {
            throw new IllegalArgumentException("Liquidity scale and maximum order size must be positive");
        }
        Integer[] order = new Integer[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (!Double.isFinite(prices[i]) || prices[i] <= 0 || !Double.isFinite(priorWeights[i]) || priorWeights[i] <= 0) {
                throw new IllegalArgumentException("Every price and prior weight must be finite and positive");
            }
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(prices[a], prices[b]));
        this.prices = new double[prices.length];
        logPrices = new double[prices.length];
        logPriorWeights = new double[prices.length];
        for (int i = 0; i < order.length; i++) {
            int source = order[i];
            this.prices[i] = prices[source];
            logPrices[i] = Math.log(prices[source]);
            logPriorWeights[i] = Math.log(priorWeights[source]);
        }
        this.liquidityScale = liquidityScale;
        this.maxOrderUnits = maxOrderUnits;
    }

    public double liquidityScale() {
        return liquidityScale;
    }

    public long maxOrderUnits() {
        return maxOrderUnits;
    }

    /** Sorted support prices, copied so callers cannot mutate the model. */
    public double[] prices() {
        return prices.clone();
    }

    /** Normalized posterior weights in the order returned by {@link #prices()}. */
    public double[] posteriorWeights(long displacement) {
        return posteriorWeightsAt(displacement, 0);
    }

    /** Smallest support price whose cumulative posterior weight reaches probability. */
    public double quantile(long displacement, double probability) {
        return quantileAt(displacement, 0, probability);
    }

    public double bid(long displacement) {
        return quantile(displacement, BID_QUANTILE);
    }

    public double ask(long displacement) {
        return quantile(displacement, ASK_QUANTILE);
    }

    public double median(long displacement) {
        return quantile(displacement, 0.5);
    }

    /** The bid at the half-unit edge crossed when selling from this displacement. */
    public double sellEdgeBid(long displacementBeforeUnit) {
        if (displacementBeforeUnit == Long.MAX_VALUE) {
            throw new IllegalArgumentException("A sell would overflow displacement");
        }
        return quantileAt(displacementBeforeUnit, 0.5, BID_QUANTILE);
    }

    /** The ask at the half-unit edge crossed when buying from this displacement. */
    public double buyEdgeAsk(long displacementBeforeUnit) {
        if (displacementBeforeUnit == Long.MIN_VALUE) {
            throw new IllegalArgumentException("A buy would overflow displacement");
        }
        // Canonicalize the edge as (lower displacement + 0.5), just as selling
        // does, so opposite traversals evaluate precisely the same posterior.
        return quantileAt(displacementBeforeUnit - 1, 0.5, ASK_QUANTILE);
    }

    /** Total EMC credited for selling units; floor is applied to every edge price. */
    public BigInteger quoteSell(long displacement, long units) {
        checkOrder(displacement, units, true);
        BigInteger total = BigInteger.ZERO;
        for (long k = 0; k < units; k++) {
            double bid = sellEdgeBid(displacement + k);
            total = total.add(BigDecimal.valueOf(bid).toBigInteger());
        }
        return total;
    }

    /** Sum of real half-edge bids, with no per-item integer rounding. */
    public BigDecimal quoteSellReal(long displacement, long units) {
        checkOrder(displacement, units, true);
        BigDecimal total = BigDecimal.ZERO;
        for (long k = 0; k < units; k++) {
            total = total.add(BigDecimal.valueOf(sellEdgeBid(displacement + k)));
        }
        return total;
    }

    /** Total EMC charged for buying units; ceiling is applied to every edge price. */
    public BigInteger quoteBuy(long displacement, long units) {
        checkOrder(displacement, units, false);
        BigInteger total = BigInteger.ZERO;
        for (long k = 0; k < units; k++) {
            double ask = buyEdgeAsk(displacement - k);
            BigDecimal value = BigDecimal.valueOf(ask);
            BigInteger floor = value.toBigInteger();
            total = total.add(value.compareTo(new BigDecimal(floor)) == 0 ? floor : floor.add(BigInteger.ONE));
        }
        return total;
    }

    /** Sum of real half-edge asks, with no per-item integer rounding. */
    public BigDecimal quoteBuyReal(long displacement, long units) {
        checkOrder(displacement, units, false);
        BigDecimal total = BigDecimal.ZERO;
        for (long k = 0; k < units; k++) {
            total = total.add(BigDecimal.valueOf(buyEdgeAsk(displacement - k)));
        }
        return total;
    }

    private void checkOrder(long displacement, long units, boolean sell) {
        if (units < 0 || units > maxOrderUnits) {
            throw new IllegalArgumentException("Order units must be in [0, " + maxOrderUnits + "]");
        }
        try {
            Math.addExact(displacement, sell ? units : -units);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Order would overflow displacement", exception);
        }
    }

    private double quantileAt(long displacement, double halfStep, double probability) {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new IllegalArgumentException("Probability must be in [0, 1]");
        }
        if (probability == 0) {
            return prices[0];
        }
        if (probability == 1) {
            return prices[prices.length - 1];
        }
        double[] weights = posteriorWeightsAt(displacement, halfStep);
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (cumulative >= probability) {
                return prices[i];
            }
        }
        return prices[prices.length - 1];
    }

    private double[] posteriorWeightsAt(long displacement, double halfStep) {
        // Subtract an anchor log price first. This prevents a common, very large
        // exponent from overwhelming the normalization at extreme displacement.
        double intensity = ((double) displacement + halfStep) / liquidityScale;
        int anchor = intensity < 0 ? prices.length - 1 : 0;
        double anchorLogPrice = logPrices[anchor];
        double[] logWeights = new double[prices.length];
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < prices.length; i++) {
            double distance = intensity < 0 ? anchorLogPrice - logPrices[i] : logPrices[i] - anchorLogPrice;
            double penalty = distance == 0 ? 0 : Math.abs(intensity) * distance;
            logWeights[i] = logPriorWeights[i] - penalty;
            maximum = Math.max(maximum, logWeights[i]);
        }
        double sum = 0;
        for (int i = 0; i < logWeights.length; i++) {
            logWeights[i] = Math.exp(logWeights[i] - maximum);
            sum += logWeights[i];
        }
        for (int i = 0; i < logWeights.length; i++) {
            logWeights[i] /= sum;
        }
        return logWeights;
    }
}

package moze_intel.projecte.market.math;

import java.math.BigInteger;

/** Run directly with java; this suite needs no Minecraft or test framework. */
public final class DynamicMarketMathSelfTest {

    public static void main(String[] args) {
        priorAndTilt();
        quantilesAndRounding();
        orderPathProperties();
        extremeStatesAndValidation();
        System.out.println("DynamicMarketMathSelfTest passed");
    }

    private static void priorAndTilt() {
        double[] prices = {100, 10, 1};
        double[] weights = {2, 3, 5};
        DynamicMarketMath market = new DynamicMarketMath(prices, weights, 10);
        prices[0] = 999;
        weights[0] = 999;
        equal(new double[] {1, 10, 100}, market.prices());
        equal(new double[] {0.5, 0.3, 0.2}, market.posteriorWeights(0), 1e-14);
        double[] copy = market.prices();
        copy[0] = 999;
        check(market.prices()[0] == 1, "price support leaked mutable state");
        double[] sold = market.posteriorWeights(10);
        double[] bought = market.posteriorWeights(-10);
        check(sold[0] > 0.5 && sold[2] < 0.2, "selling should favor low prices");
        check(bought[0] < 0.5 && bought[2] > 0.2, "buying should favor high prices");
        double expectedRatio = (0.3 / 0.5) / 10;
        near(expectedRatio, sold[1] / sold[0], 1e-14);
        equal(new double[] {0.5, 0.3, 0.2}, market.posteriorWeights(0), 1e-14);
    }

    private static void quantilesAndRounding() {
        DynamicMarketMath market = new DynamicMarketMath(
              new double[] {1.1, 2.5, 10.2}, new double[] {1, 8, 1}, 1_000_000);
        check(market.bid(0) == 1.1, "lower quantile");
        check(market.ask(0) == 2.5, "upper quantile");
        check(market.quantile(0, 0) == 1.1, "zero quantile");
        check(market.quantile(0, 1) == 10.2, "one quantile");
        check(market.quoteSell(0, 1).equals(BigInteger.ONE), "floor the unit bid");
        check(market.quoteBuy(1, 1).equals(BigInteger.valueOf(3)), "ceil the unit ask");
        check(market.quoteSell(0, 0).equals(BigInteger.ZERO), "zero sale");
        check(market.quoteBuy(0, 0).equals(BigInteger.ZERO), "zero purchase");
        DynamicMarketMath huge = new DynamicMarketMath(new double[] {1e100}, new double[] {1}, 1);
        check(huge.quoteBuy(0, 1).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
              "order totals must not overflow a long");
    }

    private static void orderPathProperties() {
        DynamicMarketMath market = new DynamicMarketMath(
              new double[] {1, 4, 13, 30}, new double[] {2, 3, 3, 2}, 4);
        long q = -7;
        long count = 29;
        BigInteger wholeSell = market.quoteSell(q, count);
        BigInteger splitSell = market.quoteSell(q, 11).add(market.quoteSell(q + 11, count - 11));
        check(wholeSell.equals(splitSell), "partitioned sales must add exactly");
        BigInteger wholeBuy = market.quoteBuy(q + count, count);
        BigInteger splitBuy = market.quoteBuy(q + count, 8).add(market.quoteBuy(q + count - 8, count - 8));
        check(wholeBuy.equals(splitBuy), "partitioned purchases must add exactly");
        check(wholeSell.compareTo(wholeBuy) <= 0, "a reversed order must not profit");
        for (long edge = -20; edge <= 20; edge++) {
            check(market.sellEdgeBid(edge) <= market.buyEdgeAsk(edge + 1), "crossing spread");
            check(market.quoteSell(edge, 1).compareTo(market.quoteBuy(edge + 1, 1)) <= 0,
                  "integer rounding must preserve no-arbitrage");
        }
        double[] restored = market.posteriorWeights(q);
        market.quoteSell(q, count);
        market.quoteBuy(q + count, count);
        equal(restored, market.posteriorWeights(q), 0);
    }

    private static void extremeStatesAndValidation() {
        DynamicMarketMath market = new DynamicMarketMath(new double[] {1e-200, 1, 1e200},
              new double[] {1e-200, 1, 1e200}, 1e-200, 5);
        for (long q : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
            double sum = 0;
            for (double weight : market.posteriorWeights(q)) {
                check(Double.isFinite(weight) && weight >= 0, "invalid posterior at " + q);
                sum += weight;
            }
            near(1, sum, 1e-14);
        }
        check(market.posteriorWeights(Long.MAX_VALUE)[0] == 1, "positive extreme should favor minimum price");
        check(market.posteriorWeights(Long.MIN_VALUE)[2] == 1, "negative extreme should favor maximum price");
        expectFailure(() -> market.quoteSell(0, 6));
        expectFailure(() -> market.quoteBuy(0, -1));
        expectFailure(() -> market.quoteSell(Long.MAX_VALUE, 1));
        expectFailure(() -> market.quoteBuy(Long.MIN_VALUE, 1));
        expectFailure(() -> market.quantile(0, Double.NaN));
        expectFailure(() -> new DynamicMarketMath(new double[] {0}, new double[] {1}, 1));
        expectFailure(() -> new DynamicMarketMath(new double[] {1}, new double[] {0}, 1));
        expectFailure(() -> new DynamicMarketMath(new double[] {1}, new double[] {1}, Double.NaN));
    }

    private static void expectFailure(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void equal(double[] expected, double[] actual) {
        equal(expected, actual, 0);
    }

    private static void equal(double[] expected, double[] actual, double tolerance) {
        check(expected.length == actual.length, "different array length");
        for (int i = 0; i < expected.length; i++) {
            near(expected[i], actual[i], tolerance);
        }
    }

    private static void near(double expected, double actual, double tolerance) {
        check(Math.abs(expected - actual) <= tolerance,
              "expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

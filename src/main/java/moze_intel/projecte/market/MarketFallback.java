package moze_intel.projecte.market;

import java.util.Map;
import java.util.Objects;

/** Adds frozen priors for static-EMC items absent from an existing market. */
public final class MarketFallback {
    private MarketFallback() {
    }

    /**
     * Adds only missing, positive-valued items. Existing entries, inventory,
     * liquidity, index state, and player fractions are never changed.
     *
     * @return the number of newly added entries
     */
    public static int addMissing(MarketSavedData data, Map<String, Long> legacyValues, double spreadFraction) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(legacyValues, "legacyValues");
        if (!Double.isFinite(spreadFraction) || spreadFraction <= 0 || spreadFraction >= 1) {
            throw new IllegalArgumentException("Spread fraction must be finite and strictly between zero and one");
        }

        int added = 0;
        for (String id : legacyValues.keySet().stream().filter(Objects::nonNull).sorted().toList()) {
            if (id.isBlank() || data.entries.containsKey(id)) {
                continue;
            }
            Long legacyValue = legacyValues.get(id);
            if (legacyValue == null || legacyValue <= 0) {
                continue;
            }
            double value = legacyValue.doubleValue();
            double[] prices = {value * (1 - spreadFraction), value, value * (1 + spreadFraction)};
            for (double price : prices) {
                if (!Double.isFinite(price) || price <= 0) {
                    throw new IllegalArgumentException("Invalid fallback price for " + id);
                }
            }
            data.entries.put(id, new MarketSavedData.Entry(prices, new double[] {1, 1, 1},
                    "legacy EMC fallback (spread " + spreadFraction + ")", 0));
            added++;
        }
        if (added > 0) {
            data.setDirty();
        }
        return added;
    }
}

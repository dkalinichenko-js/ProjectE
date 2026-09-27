package moze_intel.projecte.market;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MarketConfigDefaultsTest {
    @Test
    void oldJsonInheritsFallbackDefaultsButExplicitFalseStaysFalse() {
        MarketConfig oldConfig = MarketConfig.GSON.fromJson("{\"enabled\":true}", MarketConfig.class);
        Assertions.assertTrue(oldConfig.legacyEmcFallback);
        Assertions.assertEquals(0.25, oldConfig.fallbackSpread);

        MarketConfig optedOut = MarketConfig.GSON.fromJson(
                "{\"legacyEmcFallback\":false,\"fallbackSpread\":0.4}", MarketConfig.class);
        Assertions.assertFalse(optedOut.legacyEmcFallback);
        Assertions.assertEquals(0.4, optedOut.fallbackSpread);
    }
}

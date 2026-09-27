package moze_intel.projecte.market;

/** Separate client class keeps dedicated-server packet registration safe. */
final class MarketClient {
    static String format(Number real, double scale) {
        if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) return null;
        try {
            return new java.math.BigDecimal(real.toString()).multiply(java.math.BigDecimal.valueOf(scale),
                new java.math.MathContext(8)).stripTrailingZeros().toEngineeringString();
        } catch (NumberFormatException e) { return null; }
    }
    static void receive(MarketSync packet) {
        MarketService.receive(packet, net.minecraft.client.Minecraft.getInstance().player);
    }
}

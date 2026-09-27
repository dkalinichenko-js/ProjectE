package moze_intel.projecte.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import moze_intel.projecte.config.ProjectEConfig;

/** Loaded at server start. Changes to priors apply only to new market worlds. */
public final class MarketConfig {
    public boolean enabled = true;
    public double liquidityScale = 4096;
    public int maxOrder = 4096;
    public double indexTarget = 256;
    public Map<String, Double> indexBasket = new LinkedHashMap<>(Map.of(
        "minecraft:raw_iron", 1.0, "minecraft:raw_copper", 1.0, "minecraft:coal", 1.0,
        "minecraft:oak_log", 1.0, "minecraft:redstone", 1.0));
    public List<String> whitelist = List.of();
    public List<String> blacklist = List.of();
    public Map<String, double[]> rootPriors = new LinkedHashMap<>();
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static MarketConfig load() {
        Path path = ProjectEConfig.CONFIG_DIR.resolve("market.json");
        try {
            MarketConfig config;
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) { config = GSON.fromJson(reader, MarketConfig.class); }
            } else {
                config = new MarketConfig();
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(config));
            }
            if (config == null || !Double.isFinite(config.liquidityScale) || config.liquidityScale <= 0
                    || config.maxOrder < 1 || config.maxOrder > 100000 || config.whitelist == null
                    || config.blacklist == null || config.rootPriors == null || config.indexBasket == null || config.indexBasket.isEmpty()
                    || !Double.isFinite(config.indexTarget) || config.indexTarget <= 0) throw new IllegalArgumentException("Invalid market config");
            for (double w : config.indexBasket.values())
                if (!Double.isFinite(w) || w <= 0) throw new IllegalArgumentException("Index weights must be positive");
            for (var prior : config.rootPriors.values()) {
                if (prior == null || prior.length == 0 || prior.length > 256) throw new IllegalArgumentException("Invalid root prior");
                for (double p : prior) if (!Double.isFinite(p) || p <= 0 || p > 1e15) throw new IllegalArgumentException("Root prices must be in (0, 1e15]");
            }
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load " + path + "; refusing to silently use different market settings", e);
        }
    }
}

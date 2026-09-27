package moze_intel.projecte.market;

import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** World-global, dimension-independent market. Priors are frozen with inventory. */
public final class MarketSavedData extends SavedData {
    public final Map<String, Entry> entries = new LinkedHashMap<>();
    public boolean initialized;
    public double liquidity = 4096;
    public double indexTarget = 256;
    public final Map<String, Double> indexBasket = new LinkedHashMap<>();
    public final Map<String, BigDecimal> fractions = new LinkedHashMap<>();
    public static final Factory<MarketSavedData> FACTORY = new Factory<>(MarketSavedData::new, MarketSavedData::load, null);

    public static final class Entry {
        public final double[] prices;
        public final double[] weights;
        public final String provenance;
        public long inventory;
        public Entry(double[] prices, double[] weights, String provenance, long inventory) {
            this.prices = prices.clone(); this.weights = weights.clone(); this.provenance = provenance; this.inventory = inventory;
        }
    }
    public static MarketSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketSavedData data = new MarketSavedData();
        data.initialized = tag.getBoolean("initialized");
        data.liquidity = tag.getDouble("liquidity");
        if (tag.contains("indexTarget")) data.indexTarget = tag.getDouble("indexTarget");
        CompoundTag basket = tag.getCompound("indexBasket");
        for (String id : basket.getAllKeys()) data.indexBasket.put(id, basket.getDouble(id));
        CompoundTag fractions = tag.getCompound("fractions");
        for (String id : fractions.getAllKeys()) {
            BigDecimal fraction = new BigDecimal(fractions.getString(id));
            if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) >= 0)
                throw new IllegalArgumentException("Invalid saved market fraction");
            data.fractions.put(id, fraction);
        }
        CompoundTag entries = tag.getCompound("entries");
        for (String id : entries.getAllKeys()) {
            CompoundTag e = entries.getCompound(id);
            ListTag prices = e.getList("prices", Tag.TAG_DOUBLE), weights = e.getList("weights", Tag.TAG_DOUBLE);
            double[] p = new double[prices.size()], w = new double[weights.size()];
            for (int i = 0; i < p.length; i++) p[i] = prices.getDouble(i);
            for (int i = 0; i < w.length; i++) w[i] = weights.getDouble(i);
            data.entries.put(id, new Entry(p, w, e.getString("provenance"), e.getLong("inventory")));
        }
        return data;
    }
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("initialized", initialized); tag.putDouble("liquidity", liquidity);
        tag.putDouble("indexTarget", indexTarget);
        CompoundTag basket = new CompoundTag(), fractionsTag = new CompoundTag();
        indexBasket.forEach(basket::putDouble);
        fractions.forEach((id,value) -> fractionsTag.putString(id,value.toPlainString()));
        tag.put("indexBasket", basket); tag.put("fractions", fractionsTag);
        CompoundTag all = new CompoundTag();
        entries.forEach((id, entry) -> {
            CompoundTag e = new CompoundTag();
            ListTag p = new ListTag(), w = new ListTag();
            for (double price : entry.prices) p.add(DoubleTag.valueOf(price));
            for (double weight : entry.weights) w.add(DoubleTag.valueOf(weight));
            e.put("prices", p); e.put("weights", w); e.putString("provenance", entry.provenance); e.putLong("inventory", entry.inventory);
            all.put(id, e);
        });
        tag.put("entries", all); return tag;
    }
}

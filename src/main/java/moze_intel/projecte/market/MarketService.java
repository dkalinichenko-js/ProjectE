package moze_intel.projecte.market;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import moze_intel.projecte.PECore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.gameObjs.container.TransmutationContainer;
import moze_intel.projecte.market.math.DynamicMarketMath;
import moze_intel.projecte.market.valuation.MarketValuation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** All authoritative calls run on the server thread. Quotes never mutate state. */
public final class MarketService {
    private static final Map<MinecraftServer, State> SERVERS = new WeakHashMap<>();
    private static final Map<String, MarketSavedData.Entry> CLIENT = new HashMap<>();
    private static boolean clientEnabled;
    private static double clientLiquidity = 4096;
    private static int clientMaxOrder = 4096;
    private static double clientScale = 1;
    private static BigDecimal clientFraction = BigDecimal.ZERO;
    private record State(MarketConfig config, MarketSavedData data) {}
    private static State state(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, s -> {
            MarketConfig config = MarketConfig.load();
            MarketSavedData data = s.overworld().getDataStorage().computeIfAbsent(MarketSavedData.FACTORY, "projecte_market");
            if (!data.initialized && config.enabled) {
                Map<net.minecraft.world.item.Item, moze_intel.projecte.market.valuation.Valuation> overrides = new HashMap<>();
                config.rootPriors.forEach((id, prices) -> {
                    if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))) throw new IllegalArgumentException("Unknown root item: " + id);
                    double[] weights = new double[prices.length]; Arrays.fill(weights, 1.0);
                    overrides.put(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)),
                        new moze_intel.projecte.market.valuation.Valuation(prices, weights, List.of("config override")));
                });
                MarketValuation.compute(s.getRecipeManager(), s.registryAccess(), Map.of(), overrides).forEach((item, value) ->
                    data.entries.put(BuiltInRegistries.ITEM.getKey(item).toString(), new MarketSavedData.Entry(
                        value.prices(), value.weights(), String.join("; ", value.provenance()), 0)));
                data.liquidity = config.liquidityScale;
                data.initialized = true; data.setDirty();
                PECore.LOGGER.info("Dynamic EMC market initialized with {} valuations", data.entries.size());
            }
            addFallbacks(config, data);
            if (data.initialized && data.indexBasket.isEmpty()) {
                config.indexBasket.forEach((id,weight) -> {
                    if (!data.entries.containsKey(id)) throw new IllegalArgumentException("Index commodity has no valuation: " + id);
                    data.indexBasket.put(id,weight);
                });
                data.indexTarget = config.indexTarget;
                data.setDirty();
            }
            // Validate persisted data rather than silently reset positions after corruption.
            for (var entry : data.entries.values()) {
                math(entry, data.liquidity);
                if (entry.inventory < -1_000_000_000_000L || entry.inventory > 1_000_000_000_000L)
                    throw new IllegalStateException("Persisted market inventory exceeds supported range");
            }
            validateIndex(data);
            if (config.enabled && net.neoforged.fml.ModList.get().isLoaded("projectexpansion"))
                PECore.LOGGER.warn("DYNAMIC MARKET: ProjectExpansion has unsupported static exchange routes. Disable it for a market-controlled economy.");
            return new State(config, data);
        });
    }
    private static void addFallbacks(MarketConfig config, MarketSavedData data) {
        if (!config.enabled || !config.legacyEmcFallback || !data.initialized) return;
        Map<String, Long> legacyValues = new TreeMap<>();
        for (var item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (data.entries.containsKey(id)) continue;
            long emc = moze_intel.projecte.api.proxy.IEMCProxy.INSTANCE.getValue(new ItemStack(item));
            if (emc > 0) legacyValues.put(id, emc);
        }
        int added = MarketFallback.addMissing(data, legacyValues, config.fallbackSpread);
        if (added > 0) PECore.LOGGER.info("Dynamic EMC market added {} legacy-EMC fallback priors (spread {})", added, config.fallbackSpread);
    }

    /** Called after legacy EMC mapping is ready, including datapack reloads. Never reprices existing entries. */
    public static void refreshFallbacks(MinecraftServer server) {
        State s = state(server);
        addFallbacks(s.config, s.data);
    }

    private static void validateIndex(MarketSavedData data) {
        if (data.indexBasket.isEmpty()) return;
        double[] lows = new double[data.indexBasket.size()], highs = new double[lows.length], weights = new double[lows.length];
        int i = 0;
        for (var commodity : new TreeMap<>(data.indexBasket).entrySet()) {
            var entry = data.entries.get(commodity.getKey());
            if (entry == null) throw new IllegalArgumentException("Missing saved index commodity: " + commodity.getKey());
            double[] prices = math(entry,data.liquidity).prices();
            lows[i] = prices[0]; highs[i] = prices[prices.length-1]; weights[i] = commodity.getValue(); i++;
        }
        // Validate the entire reachable scale range before accepting transactions.
        moze_intel.projecte.market.math.CommodityIndex.scale(lows,weights,data.indexTarget);
        moze_intel.projecte.market.math.CommodityIndex.scale(highs,weights,data.indexTarget);
    }
    private static DynamicMarketMath math(MarketSavedData.Entry e, double liquidity) {
        return new DynamicMarketMath(e.prices, e.weights, liquidity);
    }
    public static void start(MinecraftServer server) { if (server.overworld() != null) state(server); }
    public static void stop(MinecraftServer server) { SERVERS.remove(server); }
    public static boolean enabled(Player player) {
        return player.level().isClientSide ? clientEnabled : state(Objects.requireNonNull(player.getServer())).config.enabled;
    }
    public static boolean enabled(MinecraftServer server) { return state(server).config.enabled; }
    private static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static boolean permitted(MarketConfig c, String id) {
        return (c.whitelist.isEmpty() || c.whitelist.contains(id)) && !c.blacklist.contains(id);
    }
    public static boolean canExchange(Player player, ItemStack stack) {
        if (stack.isEmpty() || !enabled(player) || !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()))
                || stack.getCapability(PECapabilities.EMC_HOLDER_ITEM_CAPABILITY) != null) return false;
        if (player.level().isClientSide) return CLIENT.containsKey(id(stack));
        State s = state(player.getServer());
        return permitted(s.config, id(stack)) && s.data.entries.containsKey(id(stack));
    }
    public static BigDecimal quote(Player player, ItemStack stack, int count, boolean buy) {
        if (count <= 0 || !canExchange(player, stack)) return null;
        MarketSavedData.Entry entry;
        double liquidity; int limit;
        if (player.level().isClientSide) {
            entry = CLIENT.get(id(stack)); liquidity = clientLiquidity; limit = clientMaxOrder;
        } else {
            State s = state(player.getServer()); entry = s.data.entries.get(id(stack)); liquidity = s.data.liquidity; limit = s.config.maxOrder;
        }
        if (count > limit) return null;
        try {
            long next = Math.addExact(entry.inventory, buy ? -count : count);
            if (next < -1_000_000_000_000L || next > 1_000_000_000_000L) return null;
            return buy ? math(entry, liquidity).quoteBuyReal(entry.inventory, count) : math(entry, liquidity).quoteSellReal(entry.inventory, count);
        } catch (ArithmeticException | IllegalArgumentException ex) { return null; }
    }
    public static BigDecimal trade(Player player, ItemStack stack, int count, boolean buy) {
        if (player.level().isClientSide) return null;
        if (!player.getServer().isSameThread()) throw new IllegalStateException("Market trade off server thread");
        if (buy) {
            var knowledge = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
            if (knowledge == null || !knowledge.hasExplicitKnowledge(ItemInfo.fromStack(stack))) return null;
        }
        BigDecimal result = quote(player, stack, count, buy);
        if (result == null) return null;
        State s = state(player.getServer());
        MarketSavedData.Entry entry = s.data.entries.get(id(stack));
        entry.inventory += buy ? -count : count;
        s.data.setDirty();
        for (ServerPlayer recipient : player.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(recipient, new MarketSync(false, s.config.enabled, s.data.liquidity, s.config.maxOrder, scale(s), fraction(recipient).toPlainString(),
                Map.of(id(stack), entry)));
        }
        return result;
    }
    public static void sync(ServerPlayer player) {
        State s = state(player.getServer());
        Map<String, MarketSavedData.Entry> batch = new LinkedHashMap<>(); boolean reset = true;
        for (var entry : s.data.entries.entrySet()) {
            if (!permitted(s.config, entry.getKey())) continue;
            batch.put(entry.getKey(), entry.getValue());
            if (batch.size() == 64) {
                PacketDistributor.sendToPlayer(player, new MarketSync(reset, s.config.enabled, s.data.liquidity, s.config.maxOrder, scale(s), fraction(player).toPlainString(), Map.copyOf(batch)));
                reset = false; batch.clear();
            }
        }
        PacketDistributor.sendToPlayer(player, new MarketSync(reset, s.config.enabled, s.data.liquidity, s.config.maxOrder, scale(s), fraction(player).toPlainString(), Map.copyOf(batch)));
    }
    public static void receive(MarketSync packet, Player player) {
        if (packet.reset()) CLIENT.clear();
        clientEnabled = packet.enabled(); clientLiquidity = packet.liquidity(); clientMaxOrder = packet.maxOrder();
        clientScale = packet.scale(); clientFraction = new BigDecimal(packet.fraction());
        CLIENT.putAll(packet.entries());
        if (player != null && player.containerMenu instanceof TransmutationContainer container) container.transmutationInventory.updateClientTargets(false);
    }
    public static BigDecimal fraction(Player player) {
        return player.level().isClientSide ? clientFraction : state(player.getServer()).data.fractions.getOrDefault(player.getUUID().toString(), BigDecimal.ZERO);
    }
    public static void setFraction(Player player, BigDecimal value) {
        if (player.level().isClientSide) throw new IllegalStateException("Client cannot settle market balance");
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) throw new IllegalArgumentException("Fraction must be in [0,1)");
        State s = state(player.getServer());
        s.data.fractions.put(player.getUUID().toString(), value); s.data.setDirty();
        PacketDistributor.sendToPlayer((ServerPlayer)player, new MarketSync(false,s.config.enabled,s.data.liquidity,s.config.maxOrder,
            scale(s),value.toPlainString(),Map.of()));
    }
    /** Denomination only: never applied to balances, inventory, or settlement. */
    public static double scale(Player player) {
        return player.level().isClientSide ? clientScale : scale(state(player.getServer()));
    }
    private static double scale(State s) {
        if (s.data.indexBasket.isEmpty()) return 1;
        double[] prices = new double[s.data.indexBasket.size()], weights = new double[prices.length]; int i = 0;
        for (var commodity : new TreeMap<>(s.data.indexBasket).entrySet()) {
            var e = s.data.entries.get(commodity.getKey()); var m = math(e,s.data.liquidity);
            double[] p = m.prices(), w = m.posteriorWeights(e.inventory); double logPrice = 0;
            for (int j=0;j<p.length;j++) logPrice += w[j] * Math.log(p[j]);
            prices[i] = Math.exp(logPrice); weights[i] = commodity.getValue(); i++;
        }
        return moze_intel.projecte.market.math.CommodityIndex.scale(prices,weights,s.data.indexTarget);
    }
    /** Shared legacy tooltip formatter; server accounting and APIs remain real-valued. */
    public static String formatClient(Number real) {
        if (!clientEnabled || !net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) return null;
        return MarketClient.format(real, clientScale);
    }
    public static String format(Player player, BigDecimal real) {
        return real.multiply(BigDecimal.valueOf(scale(player)),new MathContext(8)).stripTrailingZeros().toEngineeringString();
    }
    public static Map<String, Object> inspect(MinecraftServer server, String id) {
        State s = state(server); var e = s.data.entries.get(id);
        if (e == null) return Map.of("item", id, "error", "No valuation");
        DynamicMarketMath m = math(e, s.data.liquidity);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("item", id); out.put("exchangeable", permitted(s.config,id)); out.put("inventory",e.inventory);
        out.put("prices",e.prices); out.put("priorWeights",e.weights); out.put("posteriorPrices",m.prices()); out.put("posteriorWeights",m.posteriorWeights(e.inventory));
        out.put("realBid",m.quoteSellReal(e.inventory,1)); out.put("realAsk",m.quoteBuyReal(e.inventory,1));
        double scale = scale(s);
        out.put("scale",scale); out.put("indexTarget",s.data.indexTarget); out.put("indexBasket",s.data.indexBasket);
        out.put("bid",m.quoteSellReal(e.inventory,1).multiply(BigDecimal.valueOf(scale)));
        out.put("ask",m.quoteBuyReal(e.inventory,1).multiply(BigDecimal.valueOf(scale)));
        out.put("liquidity",s.data.liquidity); out.put("provenance",e.provenance); return out;
    }
    public static Map<String, Object> dump(MinecraftServer server) {
        Map<String,Object> out = new TreeMap<>();
        for (String id : state(server).data.entries.keySet()) out.put(id,inspect(server,id));
        return out;
    }
}

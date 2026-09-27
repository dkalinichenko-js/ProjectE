package moze_intel.projecte.market.valuation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.util.valueproviders.IntProvider;
import net.neoforged.neoforge.common.Tags;

/**
 * Bounded, conservative seed valuation for a loaded server recipe set.
 *
 * <p>Prices are estimates in abstract units, not EMC. A few naturally obtained
 * resources provide roots. Common {@code c:ores} and {@code c:raw_materials}
 * items receive broad material estimates. Supported recipes propagate their
 * costs forward, and alternative recipes become separate hypotheses. Items
 * unreachable from these roots are intentionally absent. An explicit fallback
 * map can fill some such gaps; the default overload never consults EMC.
 */
public final class MarketValuation {

	private static final int MAX_DEPTH = 24;
	private static final int MAX_HYPOTHESES = 6;
	private static final double[] SCENARIO_WEIGHTS = {0.25, 0.5, 0.25};
	private static final double[] QUANTILES = {0.125, 0.5, 0.875};
	private static final Map<String, Double> MATERIAL_ESTIMATES = Map.ofEntries(
			Map.entry("coal", 120.0), Map.entry("copper", 150.0), Map.entry("iron", 260.0),
			Map.entry("gold", 760.0), Map.entry("redstone", 90.0), Map.entry("lapis", 180.0),
			Map.entry("diamond", 6_000.0), Map.entry("emerald", 5_200.0),
			Map.entry("netherite", 16_000.0), Map.entry("quartz", 180.0),
			Map.entry("tin", 230.0), Map.entry("lead", 310.0), Map.entry("silver", 440.0),
			Map.entry("nickel", 430.0), Map.entry("zinc", 270.0), Map.entry("aluminum", 240.0),
			Map.entry("aluminium", 240.0), Map.entry("uranium", 1_900.0),
			Map.entry("osmium", 680.0), Map.entry("platinum", 1_600.0),
			Map.entry("sulfur", 110.0), Map.entry("apatite", 160.0),
			Map.entry("fluorite", 250.0), Map.entry("certus_quartz", 420.0),
			Map.entry("allthemodium", 24_000.0), Map.entry("vibranium", 48_000.0),
			Map.entry("unobtainium", 96_000.0));

	private MarketValuation() {
	}

	public static Map<Item, Valuation> compute(RecipeManager recipes, RegistryAccess registryAccess) {
		return compute(recipes, registryAccess, Map.of(), Map.of());
	}

	/**
	 * @param fallbackValues optional externally supplied EMC values, used only for
	 *                       items still unreachable after recipe propagation.
	 */
	public static Map<Item, Valuation> compute(RecipeManager recipes, RegistryAccess registryAccess, Map<Item, Long> fallbackValues) {
		return compute(recipes, registryAccess, fallbackValues, Map.of());
	}

	/**
	 * Explicit roots replace built-in estimates before recipe propagation. This
	 * permits pack-specific world-generation evidence to affect all descendants.
	 * Root distributions are returned exactly as supplied; propagation uses three
	 * representative quantiles to keep the recursive search bounded.
	 * Fallback values are applied only after propagation and never act as roots.
	 */
	public static Map<Item, Valuation> compute(RecipeManager recipes, RegistryAccess registryAccess,
			Map<Item, Long> fallbackValues, Map<Item, Valuation> rootOverrides) {
		Map<Item, List<Hypothesis>> known = new HashMap<>();
		seedRoots(known, registryAccess);
		for (Map.Entry<Item, Valuation> entry : rootOverrides.entrySet()) {
			Item item = entry.getKey();
			Valuation override = entry.getValue();
			if (item != null && override != null) {
				known.put(item, List.of(new Hypothesis(quantiles(override),
						"configured root: " + String.join("; ", override.provenance()), Set.of(item), true)));
			}
		}
		List<RecipeEdge> edges = collectRecipes(recipes, registryAccess);
		for (int depth = 0; depth < MAX_DEPTH; depth++) {
			Map<Item, List<Hypothesis>> discovered = new HashMap<>();
			for (RecipeEdge edge : edges) {
				if (!known.containsKey(edge.output())) {
					Hypothesis proposal = evaluate(edge, known);
					if (proposal != null) {
						discovered.computeIfAbsent(edge.output(), ignored -> new ArrayList<>()).add(proposal);
					}
				}
			}
			if (discovered.isEmpty()) {
				break;
			}
			for (Map.Entry<Item, List<Hypothesis>> entry : discovered.entrySet()) {
				known.put(entry.getKey(), shortlist(entry.getValue()));
			}
		}
		// A second route can become available after the first route established an
		// item. Include it once, without feeding it back into cyclic recipe chains.
		Map<Item, List<Hypothesis>> alternatives = new HashMap<>();
		for (RecipeEdge edge : edges) {
			List<Hypothesis> existing = known.get(edge.output());
			if (existing != null && existing.stream().noneMatch(Hypothesis::root)) {
				Hypothesis proposal = evaluate(edge, known);
				if (proposal != null) {
					alternatives.computeIfAbsent(edge.output(), ignored -> new ArrayList<>()).add(proposal);
				}
			}
		}
		for (Map.Entry<Item, List<Hypothesis>> entry : alternatives.entrySet()) {
			List<Hypothesis> combined = new ArrayList<>(known.get(entry.getKey()));
			combined.addAll(entry.getValue());
			known.put(entry.getKey(), shortlist(combined));
		}
		for (Map.Entry<Item, Long> entry : fallbackValues.entrySet()) {
			if (!known.containsKey(entry.getKey()) && entry.getValue() != null && entry.getValue() > 0) {
				addRoot(known, entry.getKey(), entry.getValue(), 0.45, 2.0,
						"fallback: externally supplied EMC; weak prior, no recipe witness");
			}
		}
		Map<Item, Valuation> result = new HashMap<>();
		for (Map.Entry<Item, List<Hypothesis>> entry : known.entrySet()) {
			List<Hypothesis> hypotheses = entry.getValue();
			double[] prices = new double[hypotheses.size() * 3];
			double[] weights = new double[prices.length];
			List<String> provenance = new ArrayList<>(hypotheses.size());
			for (int h = 0; h < hypotheses.size(); h++) {
				Hypothesis hypothesis = hypotheses.get(h);
				provenance.add(hypothesis.provenance());
				for (int s = 0; s < 3; s++) {
					prices[h * 3 + s] = hypothesis.prices()[s];
					weights[h * 3 + s] = SCENARIO_WEIGHTS[s] / hypotheses.size();
				}
			}
			result.put(entry.getKey(), new Valuation(prices, weights, provenance));
		}
		for (Map.Entry<Item, Valuation> entry : rootOverrides.entrySet()) {
			if (entry.getKey() != null && entry.getValue() != null) {
				Valuation override = entry.getValue();
				result.put(entry.getKey(), new Valuation(override.prices(), override.weights(), override.provenance()));
			}
		}
		return Map.copyOf(result);
	}

	private static double[] quantiles(Valuation valuation) {
		double[] prices = valuation.prices();
		double[] weights = valuation.weights();
		Integer[] order = new Integer[prices.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble(i -> prices[i]));
		double[] result = new double[3];
		double cumulative = 0;
		int at = 0;
		for (int i : order) {
			cumulative += weights[i];
			while (at < 3 && cumulative >= QUANTILES[at]) {
				result[at++] = prices[i];
			}
		}
		while (at < 3) {
			result[at++] = prices[order[order.length - 1]];
		}
		return result;
	}

	private static void seedRoots(Map<Item, List<Hypothesis>> known, RegistryAccess registryAccess) {
		// Hand-set natural-resource estimates. They are deliberately broad; their
		// numerical values only establish a market scale and are not copied EMC.
		root(known, Items.DIRT, 1.5); root(known, Items.COBBLESTONE, 2); root(known, Items.STONE, 3);
		root(known, Items.SAND, 2); root(known, Items.GRAVEL, 2); root(known, Items.CLAY_BALL, 7);
		root(known, Items.FLINT, 13); root(known, Items.OAK_LOG, 28); root(known, Items.SPRUCE_LOG, 28);
		root(known, Items.BIRCH_LOG, 28); root(known, Items.JUNGLE_LOG, 32);
		root(known, Items.ACACIA_LOG, 30); root(known, Items.DARK_OAK_LOG, 30);
		root(known, Items.MANGROVE_LOG, 34); root(known, Items.CHERRY_LOG, 34);
		root(known, Items.NETHERRACK, 2); root(known, Items.END_STONE, 14);
		root(known, Items.CACTUS, 15); root(known, Items.SUGAR_CANE, 13);
		root(known, Items.KELP, 5); root(known, Items.BAMBOO, 6);
		root(known, Items.WHEAT, 21); root(known, Items.CARROT, 17);
		root(known, Items.POTATO, 17); root(known, Items.BEETROOT, 18);
		root(known, Items.COAL, 120); root(known, Items.REDSTONE, 90);
		root(known, Items.LAPIS_LAZULI, 180); root(known, Items.QUARTZ, 180);
		root(known, Items.RAW_COPPER, 150); root(known, Items.RAW_IRON, 260);
		root(known, Items.RAW_GOLD, 760); root(known, Items.DIAMOND, 6_000);
		root(known, Items.EMERALD, 5_200);
		Map<String, OreOpportunity> opportunities = oreOpportunities(registryAccess);
		for (Item item : BuiltInRegistries.ITEM) {
			if (known.containsKey(item)) {
				continue;
			}
			ItemStack stack = item.getDefaultInstance();
			boolean ore = stack.is(Tags.Items.ORES);
			boolean raw = stack.is(Tags.Items.RAW_MATERIALS);
			if (!ore && !raw) {
				continue;
			}
			ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
			String material = materialName(id.getPath());
			double base = MATERIAL_ESTIMATES.getOrDefault(material, 360.0);
			String kind = raw ? "c:raw_materials" : "c:ores";
			OreOpportunity opportunity = opportunities.get(material);
			String evidence = "";
			if (opportunity != null) {
				// A feature's nominal blocks per chunk are only an opportunity
				// signal. Biome, height, dimension, exposure, replacement and
				// overlapping attempts determine actual obtainable yield.
				double factor = Math.max(0.7, Math.min(1.4,
						Math.pow(64.0 / opportunity.nominalBudget(), 0.2)));
				base *= factor;
				evidence = "; placed feature " + opportunity.id() + " size " + opportunity.size()
						+ ", count " + opportunity.minCount() + ".." + opportunity.maxCount()
						+ ", nominal opportunity only";
			}
			if (ore) {
				base *= 0.85; // Ore yield and processing method vary by pack.
			}
			addRoot(known, item, base, 0.35, 2.8,
					"estimated " + kind + " root (" + material + ", " + id + evidence + ")");
		}
	}

	private static Map<String, OreOpportunity> oreOpportunities(RegistryAccess access) {
		Map<String, OreOpportunity> result = new HashMap<>();
		Registry<PlacedFeature> placed = access.registry(Registries.PLACED_FEATURE).orElse(null);
		if (placed == null) {
			return result;
		}
		for (Map.Entry<net.minecraft.resources.ResourceKey<PlacedFeature>, PlacedFeature> entry : placed.entrySet()) {
			ResourceLocation id = entry.getKey().location();
			PlacedFeature feature = entry.getValue();
			ConfiguredFeature<?, ?> configured = feature.feature().value();
			if (!(configured.config() instanceof OreConfiguration ore) || ore.size <= 0) {
				continue;
			}
			for (PlacementModifier modifier : feature.placement()) {
				if (!(modifier instanceof CountPlacement count)) {
					continue;
				}
				try {
					JsonElement encoded = CountPlacement.CODEC.codec().encodeStart(JsonOps.INSTANCE, count).result().orElse(null);
					if (!(encoded instanceof JsonObject object)) {
						continue;
					}
					IntProvider provider = IntProvider.CODEC.parse(JsonOps.INSTANCE, object.get("count")).result().orElse(null);
					if (provider == null || provider.getMaxValue() <= 0) {
						continue;
					}
					int min = provider.getMinValue();
					int max = provider.getMaxValue();
					double budget = ore.size * (min + max) / 2.0;
					if (budget <= 0) {
						continue;
					}
					OreOpportunity opportunity = new OreOpportunity(id, ore.size, min, max, budget);
					for (OreConfiguration.TargetBlockState target : ore.targetStates) {
						Item oreItem = target.state.getBlock().asItem();
						if (oreItem != Items.AIR && oreItem.getDefaultInstance().is(Tags.Items.ORES)) {
							String material = materialName(BuiltInRegistries.ITEM.getKey(oreItem).getPath());
							result.merge(material, opportunity,
									(previous, next) -> previous.nominalBudget() >= next.nominalBudget() ? previous : next);
						}
					}
				} catch (RuntimeException ignored) {
					// Unknown placement encodings remain broad tag estimates.
				}
			}
		}
		return result;
	}

	private static String materialName(String path) {
		String material = path.replaceFirst("^(deepslate_|nether_|end_|raw_)", "")
				.replaceFirst("^(ore_|raw_)", "").replaceFirst("(_ore|_raw)$", "");
		if (material.startsWith("ore_")) {
			material = material.substring(4);
		}
		return material;
	}

	private static void root(Map<Item, List<Hypothesis>> known, Item item, double estimate) {
		addRoot(known, item, estimate, 0.55, 1.8,
				"estimated natural-resource root (" + BuiltInRegistries.ITEM.getKey(item) + ")");
	}

	private static void addRoot(Map<Item, List<Hypothesis>> known, Item item, double estimate,
			double lowerFactor, double upperFactor, String source) {
		known.put(item, List.of(new Hypothesis(new double[] {estimate * lowerFactor, estimate, estimate * upperFactor},
				source, Set.of(item), true)));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static List<RecipeEdge> collectRecipes(RecipeManager manager, RegistryAccess access) {
		List<RecipeEdge> edges = new ArrayList<>();
		for (RecipeType type : List.of(RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING,
				RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING, RecipeType.STONECUTTING)) {
			for (Object object : manager.getAllRecipesFor(type)) {
				RecipeHolder<?> holder = (RecipeHolder<?>) object;
				Recipe<?> recipe = holder.value();
				if (recipe.isSpecial() || !(recipe instanceof CraftingRecipe || recipe instanceof AbstractCookingRecipe
						|| recipe instanceof SingleItemRecipe)) {
					continue;
				}
				try {
					ItemStack output = recipe.getResultItem(access);
					if (output.isEmpty() || output.getCount() <= 0 || output.getItem() == Items.AIR ||
							!ItemStack.isSameItemSameComponents(output, output.getItem().getDefaultInstance())) {
						continue;
					}
					List<Item[]> choices = new ArrayList<>();
					boolean valid = true;
					for (Ingredient ingredient : recipe.getIngredients()) {
						if (ingredient.isEmpty()) {
							continue;
						}
						ItemStack[] options = ingredient.getItems();
						if (options.length == 0) {
							valid = false;
							break;
						}
						Set<Item> items = new HashSet<>();
						for (ItemStack option : options) {
							if (option.isEmpty() || option.getCount() != 1 ||
									!ItemStack.isSameItemSameComponents(option, option.getItem().getDefaultInstance()) ||
									option.getItem().hasCraftingRemainingItem(option)) {
								// Container returns and reusable tools need full recipe semantics.
								valid = false;
								break;
							}
							items.add(option.getItem());
						}
						if (!valid || items.isEmpty()) {
							valid = false;
							break;
						}
						choices.add(items.toArray(Item[]::new));
					}
					if (valid && !choices.isEmpty()) {
						edges.add(new RecipeEdge(holder.id(), output.getItem(), output.getCount(), choices));
					}
				} catch (RuntimeException ignored) {
					// A modded recipe with nonstandard data stays unpriced.
				}
			}
		}
		return edges;
	}

	private static Hypothesis evaluate(RecipeEdge edge, Map<Item, List<Hypothesis>> known) {
		double[] price = new double[3];
		Set<Item> ancestry = new HashSet<>();
		for (Item[] options : edge.choices()) {
			Hypothesis best = null;
			for (Item option : options) {
				List<Hypothesis> hypotheses = known.get(option);
				if (hypotheses == null) {
					continue;
				}
				for (Hypothesis hypothesis : hypotheses) {
					if (!hypothesis.ancestry().contains(edge.output()) &&
							(best == null || hypothesis.prices()[1] < best.prices()[1])) {
						best = hypothesis;
					}
				}
			}
			if (best == null) {
				return null;
			}
			ancestry.addAll(best.ancestry());
			for (int s = 0; s < 3; s++) {
				price[s] += best.prices()[s];
			}
		}
		ancestry.add(edge.output());
		for (int s = 0; s < 3; s++) {
			price[s] /= edge.count();
			if (!Double.isFinite(price[s]) || price[s] <= 0) {
				return null;
			}
		}
		return new Hypothesis(price, "recipe " + edge.id(), Set.copyOf(ancestry), false);
	}

	private static List<Hypothesis> shortlist(List<Hypothesis> hypotheses) {
		Map<String, Hypothesis> unique = new LinkedHashMap<>();
		for (Hypothesis hypothesis : hypotheses) {
			unique.merge(hypothesis.provenance(), hypothesis,
					(left, right) -> left.prices()[1] <= right.prices()[1] ? left : right);
		}
		List<Hypothesis> selected = new ArrayList<>(unique.values());
		selected.sort(Comparator.comparingDouble(h -> h.prices()[1]));
		return List.copyOf(selected.subList(0, Math.min(MAX_HYPOTHESES, selected.size())));
	}

	private record RecipeEdge(ResourceLocation id, Item output, int count, List<Item[]> choices) {
	}

	private record Hypothesis(double[] prices, String provenance, Set<Item> ancestry, boolean root) {
	}

	private record OreOpportunity(ResourceLocation id, int size, int minCount, int maxCount, double nominalBudget) {
	}
}

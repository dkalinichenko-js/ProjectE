package moze_intel.projecte.market.valuation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(EphemeralTestServerProvider.class)
class MarketValuationTest {

	@Test
	void propagatesOutputCountsAndKeepsRecipeAlternativesWithoutCycleCollapse(MinecraftServer server) {
		RecipeManager recipes = new RecipeManager(server.registryAccess());
		recipes.replaceRecipes(List.of(
				recipe("dirt_to_paper", Items.PAPER, 4, Items.DIRT, Items.DIRT),
				recipe("cobble_to_paper", Items.PAPER, 1, Items.COBBLESTONE),
				recipe("paper_to_book", Items.BOOK, 1, Items.PAPER),
				recipe("book_to_paper", Items.PAPER, 1, Items.BOOK),
				recipe("bucket_to_feather", Items.FEATHER, 1, Items.WATER_BUCKET)));
		Map<Item, Valuation> roots = Map.of(
				Items.DIRT, fixed(20, "test dirt root"),
				Items.COBBLESTONE, fixed(50, "test cobble root"),
				Items.FLINT, new Valuation(new double[] {2, 3, 5, 8},
						new double[] {0.1, 0.2, 0.3, 0.4}, List.of("full support override")),
				Items.WATER_BUCKET, fixed(100, "test water bucket root"));

		Map<Item, Valuation> result = MarketValuation.compute(recipes, server.registryAccess(), Map.of(), roots);
		Valuation paper = result.get(Items.PAPER);
		Assertions.assertNotNull(paper);
		Assertions.assertTrue(contains(paper, 10), "Two dirt for four paper should cost 10 per paper");
		Assertions.assertTrue(contains(paper, 50), "The cobblestone recipe should remain a separate hypothesis");
		Assertions.assertEquals(10, result.get(Items.BOOK).prices()[1]);
		Assertions.assertTrue(paper.provenance().stream().noneMatch(s -> s.contains("book_to_paper")),
				"A reverse recipe must not feed a price back into its own source");
		Assertions.assertNull(result.get(Items.FEATHER), "A bucket-return recipe is intentionally unsupported");
		Assertions.assertArrayEquals(new double[] {2, 3, 5, 8}, result.get(Items.FLINT).prices(),
				"A configured root keeps its complete discrete support");
		Assertions.assertArrayEquals(new double[] {0.1, 0.2, 0.3, 0.4}, result.get(Items.FLINT).weights());
	}

	private static RecipeHolder<ShapelessRecipe> recipe(String id, Item output, int count, Item... inputs) {
		Ingredient[] ingredients = Arrays.stream(inputs).map(Ingredient::of).toArray(Ingredient[]::new);
		return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("valuation_test", id),
				new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output, count),
						NonNullList.of(Ingredient.EMPTY, ingredients)));
	}

	private static Valuation fixed(double price, String source) {
		return new Valuation(new double[] {price}, new double[] {1}, List.of(source));
	}

	private static boolean contains(Valuation valuation, double price) {
		return Arrays.stream(valuation.prices()).anyMatch(sample -> Math.abs(sample - price) < 1e-9);
	}
}

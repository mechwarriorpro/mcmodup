package mcmodup.recipecheck;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Test probe only: installed next to, never inside, the generated low-code mod. */
@Mod("mcmodup_recipecheck")
public final class ModernRecipeSmoke {
    public ModernRecipeSmoke() {
        NeoForge.EVENT_BUS.addListener(ModernRecipeSmoke::check);
    }

    private static void check(ServerStartedEvent event) {
        try {
            final ServerLevel level = event.getServer().overworld();
            final List<String> rows = Files.readAllLines(Path.of(System.getProperty("mcmodup.recipe.cases")));
            final String[] meta = rows.getFirst().split("\t");
            require(ModList.get().isLoaded(meta[1]), "Missing converted NeoForge mod " + meta[1]);
            require(level.getRecipeManager().getRecipes().stream().filter(r -> r.id().getNamespace().equals(meta[1])).count()
                == Integer.parseInt(meta[2]), "Different number of loaded NeoForge recipes");
            for (final String row : rows.subList(1, rows.size())) {
                final String[] c = row.split("\t");
                if (c[0].equals("C")) craft(level, c);
                else if (c[0].equals("S") || c[0].equals("A")) smelt(level, c);
                else throw new IllegalArgumentException(row);
            }
            final String difference = rows.stream().anyMatch(row -> row.startsWith("A\t"))
                ? "; 3 XP after two automated extractions and one player pickup" : "";
            System.out.println("MCMODUP_RECIPE_PASS " + (rows.size() - 1) + " runtime cases for " + meta[1] + difference);
        } catch (Throwable error) {
            System.err.println("MCMODUP_RECIPE_FAIL " + error);
            error.printStackTrace();
        }
    }

    private static ItemStack item(String id) {
        return id.equals(".") ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
    }

    private static void result(ItemStack stack, String expected, int count) {
        require(!stack.isEmpty() && stack.is(item(expected).getItem()) && stack.getCount() == count,
            "Expected " + count + " " + expected + ", got " + stack);
    }

    private static void craft(ServerLevel level, String[] c) {
        final var manager = level.getRecipeManager();
        final var holder = manager.byKey(ResourceLocation.parse(c[1])).orElseThrow();
        final var recipe = (CraftingRecipe) holder.value();
        final var stacks = Arrays.stream(c[7].split(",")).map(ModernRecipeSmoke::item).toList();
        final var input = CraftingInput.of(Integer.parseInt(c[5]), Integer.parseInt(c[6]), stacks);
        final boolean shouldMatch = c[2].equals("1");
        require(recipe.matches(input, level) == shouldMatch, "Changed crafting acceptance: " + String.join(" ", c));
        if (shouldMatch) {
            final var actual = manager.getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
            require(actual.id().equals(holder.id()), "Modern registry matches a competing recipe: " + actual.id());
            result(actual.value().assemble(input, level.registryAccess()), c[3], Integer.parseInt(c[4]));
            require(recipe.getRemainingItems(input).stream().allMatch(ItemStack::isEmpty), "Unexpected crafting remainder");
        }
    }

    private static void smelt(ServerLevel level, String[] c) {
        final var manager = level.getRecipeManager();
        final var input = new SingleRecipeInput(item(c[2]));
        final var holder = manager.getRecipeFor(RecipeType.SMELTING, input, level).orElseThrow();
        require(holder.id().equals(ResourceLocation.parse(c[1])), "Smelting is not supplied by the converted mod");
        final SmeltingRecipe recipe = holder.value();
        final int ticks = Integer.parseInt(c[5]);
        final int xp = Integer.parseInt(c[8]);
        final int count = Integer.parseInt(c[4]);
        final boolean automated = c[0].equals("A");
        require(recipe.getCookingTime() == ticks && recipe.getExperience() == Integer.parseInt(c[7]), "Changed cook time or per-cook XP");
        require(manager.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(Items.STICK)), level).isEmpty(),
            "Unrelated furnace input unexpectedly smelts");
        require(manager.getRecipeFor(RecipeType.SMOKING, input, level).isEmpty()
            && manager.getRecipeFor(RecipeType.BLASTING, input, level).isEmpty(), "Unexpected additional cooker support");
        final BlockPos pos = level.getSharedSpawnPos().above(4).east(automated ? 12 : 0);
        final AABB range = new AABB(pos).inflate(4);
        final int before = level.getEntitiesOfClass(ExperienceOrb.class, range).stream().mapToInt(ExperienceOrb::getValue).sum();
        level.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
        final var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
        final int operations = automated ? 3 : 1;
        furnace.setItem(0, new ItemStack(input.item().getItem(), operations + 1));
        furnace.setItem(1, new ItemStack(Items.COAL));
        for (int operation = 1; operation <= operations; operation++) {
            for (int tick = 1; tick <= ticks; tick++) {
                AbstractFurnaceBlockEntity.serverTick(level, pos, level.getBlockState(pos), furnace);
                if (tick < ticks) require(furnace.getItem(2).isEmpty(), "Furnace produced output before the expected tick");
            }
            result(furnace.getItem(2), c[3], count);
            require(furnace.getItem(0).getCount() == operations + 1 - operation, "Furnace must consume exactly one input");
            if (operation < operations) result(furnace.removeItem(2, count), c[3], count);
        }
        furnace.getRecipesToAwardAndPopExperience(level, Vec3.atCenterOf(pos));
        final int after = level.getEntitiesOfClass(ExperienceOrb.class, range).stream().mapToInt(ExperienceOrb::getValue).sum();
        require(after - before == xp, "Expected " + xp + " XP, got " + (after - before));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

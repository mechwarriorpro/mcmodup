package mcmodup.energisticscheck;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.storage.StorageCells;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import thaumcraft.aspect.Aspect;
import thaumicenergistics.cells.CellsFeature;
import thaumicenergistics.cells.EssentiaStorageType;
import thaumicenergistics.essentia.EssentiaKey;
import thaumicenergistics.essentia.EssentiaKeyType;

@Mod(EnergisticsPackSmoke.ID)
@GameTestHolder(EnergisticsPackSmoke.ID)
@PrefixGameTestTemplate(false)
public final class EnergisticsPackSmoke {
    public static final String ID = "mcmodup_energistics_check";

    @GameTest(template = "empty")
    public static void essentiaRoundTripsThroughAe2(GameTestHelper test) {
        var key = EssentiaKey.of(Aspect.FIRE);
        var registry = test.getLevel().registryAccess();
        var typeId = ResourceLocation.fromNamespaceAndPath("thaumicenergistics", "essentia");
        test.assertTrue(AEKeyTypes.get(typeId) == EssentiaKeyType.TYPE, "Thaumcraft essentia isn't registered with AE2");
        test.assertTrue(key.equals(AEKey.fromTagGeneric(registry, key.toTagGeneric(registry))), "AE2 couldn't decode Thaumcraft fire essentia");
        var stack = new ItemStack(CellsFeature.cell(EssentiaStorageType.TYPE_1K).get());
        var cell = StorageCells.getCellInventory(stack, null);
        test.assertTrue(cell != null, "AE2 does not recognize the add-on's storage cell");
        var source = IActionSource.empty();
        test.assertTrue(cell.insert(key, 37, Actionable.MODULATE, source) == 37, "The AE2 cell didn't accept fire essentia");
        var saved = ItemStack.parseOptional(registry, (net.minecraft.nbt.CompoundTag) stack.save(registry));
        var reloaded = StorageCells.getCellInventory(saved, null);
        test.assertTrue(reloaded != null && reloaded.extract(key, 100, Actionable.MODULATE, source) == 37,
                "The saved cell didn't preserve its fire essentia");
        test.succeed();
    }

    @GameTest(template = "empty")
    public static void microblocksRegisterInTheSamePack(GameTestHelper test) {
        var item = ResourceLocation.fromNamespaceAndPath("cb_microblock", "microblock");
        var recipe = ResourceLocation.fromNamespaceAndPath("cb_microblock", "diamond_saw");
        test.assertTrue(BuiltInRegistries.ITEM.containsKey(item), "The CB Multipart microblock item is missing");
        test.assertTrue(test.getLevel().getRecipeManager().byKey(recipe).isPresent(), "The CB Microblock diamond saw recipe is missing");
        test.succeed();
    }
}

package mcmodup.test;

import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.crafting.CraftingInput;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CatalogSmoke.ID)
@PrefixGameTestTemplate(false)
public final class CatalogAspectSmoke {
    @GameTest(template = "empty")
    public static void aspectFromComponents(GameTestHelper helper) {
        String ns = "thaumcraft_aspects";
        var fire = CatalogSmoke.stack(helper, ns, "aspect_ignis");
        var air = CatalogSmoke.stack(helper, ns, "aspect_aer");
        CatalogSmoke.check(helper, CraftingInput.of(2, 1, List.of(fire, air)), ns, "aspect_lux");
        helper.succeed();
    }
}

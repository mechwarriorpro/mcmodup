package @PACKAGE@;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tiers;

/** The synthesized base Polytool. Legacy infusion and energy modules are separate features. */
final class PolytoolItem extends PickaxeItem {
    PolytoolItem(Item.Properties properties) {
        super(Tiers.IRON, properties.attributes(PickaxeItem.createAttributes(Tiers.IRON, 1.0F, -2.8F)));
    }
}

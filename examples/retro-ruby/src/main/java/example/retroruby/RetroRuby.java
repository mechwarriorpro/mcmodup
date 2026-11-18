package example.retroruby;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.Item;

@Mod(modid = "retro_ruby", name = "Retro Ruby", version = "1.0.0")
public class RetroRuby {
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        Item ruby = new Item().setUnlocalizedName("ruby");
        GameRegistry.registerItem(ruby, "ruby");
    }
}

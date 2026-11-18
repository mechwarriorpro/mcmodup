/*
 * Ender Rift bridge for Ender Storage 2.13 on NeoForge, based on Thaumic Tinkerer by Vazkii
 * and contributors (1.7.10 EnderStorageFunctions). CC BY-NC-SA 3.0, see the add-on's NOTICE.md.
 */
package thaumic.tinkerer.compat.enderstorage;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.config.EnderStorageConfig;
import codechicken.enderstorage.manager.EnderStorageManager;
import codechicken.enderstorage.storage.EnderItemStorage;
import codechicken.enderstorage.tile.TileEnderChest;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.wands.WandHelper;
import thaumic.tinkerer.common.item.foci.ItemModFocus;

/** Only entered if Ender Storage is loaded. The saved focus doesn't require any optional registry type. */
public final class EnderStorageCompat {
    private static final String LINK = "thaumictinkerer:ender_rift_frequency";

    private EnderStorageCompat() {}

    // Returning false hands an unbound focus to the normal vanilla Ender Chest cast.
    public static boolean tryCast(ItemStack wand, ServerPlayer player, AspectList cost) {
        var focus = WandHelper.getFocusItem(wand);
        var level = player.serverLevel();
        if (player.isShiftKeyDown()) {
            var hit = ItemModFocus.trace(player, player.blockInteractionRange(), true, false);
            if (hit.getType() == HitResult.Type.BLOCK && level.hasChunkAt(hit.getBlockPos())) {
                var pos = hit.getBlockPos();
                if (level.getBlockEntity(pos) instanceof TileEnderChest chest) {
                    var frequency = chest.getFrequency();
                    // The native Ender Pouch follows this rule for another player's chest in anarchy mode.
                    if (EnderStorageConfig.anarchyMode && frequency.owner().filter(player.getUUID()::equals).isEmpty()) {
                        frequency = frequency.withoutOwner();
                    }
                    var encoded = Frequency.CODEC.encodeStart(NbtOps.INSTANCE, frequency).getOrThrow();
                    CustomData.update(DataComponents.CUSTOM_DATA, focus, tag -> tag.put(LINK, encoded));
                    WandHelper.setFocus(wand, focus);
                    return true;
                }
                if (level.getBlockState(pos).is(Blocks.OBSIDIAN)) {
                    CustomData.update(DataComponents.CUSTOM_DATA, focus, tag -> tag.remove(LINK));
                    WandHelper.setFocus(wand, focus);
                    return true;
                }
            }
        }
        var frequency = read(focus);
        if (frequency == null) return false;
        if (WandHelper.consumeAllVis(wand, player, cost, true, false)) {
            EnderStorageManager.instance(false).getStorage(frequency, EnderItemStorage.TYPE)
                    .openContainer(player, focus.getHoverName());
        }
        return true;
    }

    private static Frequency read(ItemStack focus) {
        var data = focus.get(DataComponents.CUSTOM_DATA);
        Tag value = data == null ? null : data.copyTag().get(LINK);
        return value == null ? null : Frequency.CODEC.parse(NbtOps.INSTANCE, value).result().orElse(null);
    }

    public static void addTooltip(ItemStack focus, List<Component> tooltip) {
        var frequency = read(focus);
        if (frequency != null) frequency.ownerName().ifPresent(tooltip::add);
    }

    public static String sortingKey(ItemStack focus) {
        var frequency = read(focus);
        if (frequency == null) return "TTEC-VANILLA";
        int colors = (frequency.left().getWoolMeta() << 8)
                | (frequency.middle().getWoolMeta() << 4) | frequency.right().getWoolMeta();
        return "TTEC" + colors + frequency.ownerName().map(Component::getString).orElse("global");
    }
}

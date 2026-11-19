package mcmodup_kernel.atomic_thermometer;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(ThermometerMod.ID)
public final class ThermometerMod {
    public static final String ID = "atomic_thermometer";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    static final DeferredBlock<Thermometer> BLOCK = BLOCKS.register("thermometer",
        () -> new Thermometer(BlockBehaviour.Properties.of().strength(1F)));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Thermometer.Sensor>> SENSOR = ENTITIES.register("thermometer",
        () -> BlockEntityType.Builder.of(Thermometer.Sensor::new, BLOCK.get()).build(null));
    private static final DeferredBlock<Turbine> TURBINE = BLOCKS.register("steam_turbine",
        () -> new Turbine(BlockBehaviour.Properties.of().strength(3.5F).requiresCorrectToolForDrops()));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Turbine.Rotor>> ROTOR = ENTITIES.register("steam_turbine",
        () -> BlockEntityType.Builder.of(Turbine.Rotor::new, TURBINE.get()).build(null));
    private static final DeferredItem<BlockItem> TURBINE_ITEM = ITEMS.registerSimpleBlockItem(TURBINE);
    static final DeferredItem<TrackingItem> ITEM = ITEMS.register("thermometer", TrackingItem::new);

    public ThermometerMod(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        HeatGrid.register();
        Turbine.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) event.accept(ITEM);
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(TURBINE_ITEM);
        });
    }

    static final class TrackingItem extends BlockItem {
        TrackingItem() { super(BLOCK.get(), new Properties()); }

        @Override public InteractionResult useOn(UseOnContext use) {
            var player = use.getPlayer();
            if (player == null || !player.isShiftKeyDown()) return super.useOn(use);
            if (!use.getLevel().isClientSide()) {
                var pos = use.getClickedPos();
                Thermometer.target(use.getItemInHand(), pos);
                player.displayClientMessage(Component.translatable("info." + ID + ".tracked", pos.getX(), pos.getY(), pos.getZ()), true);
            }
            return InteractionResult.sidedSuccess(use.getLevel().isClientSide());
        }

        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            var stack = player.getItemInHand(hand);
            if (!level.isClientSide()) {
                Thermometer.target(stack, null);
                player.displayClientMessage(Component.translatable("info." + ID + ".untracked"), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            var pos = Thermometer.target(stack);
            lines.add(pos == null ? Component.translatable("info." + ID + ".untracked") :
                Component.translatable("info." + ID + ".tracked", pos.getX(), pos.getY(), pos.getZ()));
        }
    }
}

    @GameTest(template = "fluid_room")
    public static void release394DispenserTransfers(GameTestHelper test) {
        var level = test.getLevel();
        var bucket = dispenser(test, 1, radioactive(test, "bucket_element_u", 1, 37, level.getGameTime()), ItemStack.EMPTY);
        var split = dispenser(test, 3, item("element_fe", 3), item("element_fe", 5));
        var shortPayment = dispenser(test, 5, item("molecule_cellulose", 3), item("molecule_cellulose", 4));
        var blocked = dispenser(test, 7, item("bucket_element_fe", 1), ItemStack.EMPTY);
        var flowing = dispenser(test, 9, new ItemStack(Items.BUCKET), ItemStack.EMPTY);
        var water = dispenser(test, 11, new ItemStack(Items.BUCKET), ItemStack.EMPTY);
        level.setBlockAndUpdate(blocked.east(), Blocks.STONE.defaultBlockState());
        setFluid(level, flowing.east(), "fluid_element_fe", false);
        level.setBlockAndUpdate(water.east(), Blocks.WATER.defaultBlockState());
        for (var pos : List.of(bucket, split, shortPayment, blocked, flowing, water)) powerDispenser(level, pos, true);
        test.runAfterDelay(5, () -> {
            test.assertTrue(count(level, bucket, new ItemStack(Items.BUCKET)) == 1 &&
                level.getFluidState(bucket.east()).isSource(), "Filled bucket didn't place a source and return one bucket");
            test.assertTrue(count(level, split, item("test_tube", 1)) == 8 && count(level, split, item("element_fe", 1)) == 0 &&
                level.getFluidState(split.east()).isSource(), "Dispenser didn't pay eight matching tubes across slots");
            test.assertTrue(count(level, shortPayment, item("molecule_cellulose", 1)) == 7 && level.isEmptyBlock(shortPayment.east()),
                "Dispenser spent tubes or placed fluid on a short payment");
            test.assertTrue(count(level, blocked, item("bucket_element_fe", 1)) == 1 && level.getBlockState(blocked.east()).is(Blocks.STONE),
                "Blocked target lost a bucket or displaced stone");
            test.assertTrue(!level.getFluidState(flowing.east()).isEmpty() && !level.getFluidState(flowing.east()).isSource() &&
                count(level, flowing, item("bucket_element_fe", 1)) == 0 && visibleItems(level, flowing.east(), new ItemStack(Items.BUCKET)) == 1,
                "An empty dispenser bucket promoted flowing chemical into a source, or lost its fallback item");
            test.assertTrue(count(level, water, new ItemStack(Items.WATER_BUCKET)) == 1 && level.isEmptyBlock(water.east()),
                "Empty bucket lost its vanilla water fallback");
            for (var pos : List.of(bucket, split)) {
                powerDispenser(level, pos, false);
                powerDispenser(level, pos, true);
            }
            test.runAfterDelay(5, () -> {
                var returnedBucket = ((Container) level.getBlockEntity(bucket)).getItem(0);
                test.assertTrue(returnedBucket.is(item("bucket_element_u", 1).getItem()) && clock(test, returnedBucket).getLong("age") == 37 &&
                    level.isEmptyBlock(bucket.east()), "Bucket automation reset the stored radioactive source clock: " + returnedBucket +
                    "; target=" + level.getBlockState(bucket.east()));
                test.assertTrue(count(level, split, item("element_fe", 1)) == 8 && count(level, split, item("test_tube", 1)) == 0 &&
                    level.isEmptyBlock(split.east()), "Empty tubes couldn't recover the source they placed");
                test.succeed();
            });
        });
    }

    @GameTest(template = "fluid_room")
    public static void release394FluidReactions(GameTestHelper test) {
        var level = test.getLevel();
        var source = test.absolutePos(new BlockPos(3, 5, 3));
        var flow = test.absolutePos(new BlockPos(9, 5, 3));
        var salt = test.absolutePos(new BlockPos(3, 5, 10));
        var disabled = test.absolutePos(new BlockPos(9, 5, 10));
        var tnt = test.absolutePos(new BlockPos(12, 5, 10));
        var option = reactionOption("FLUID_CONTACT");
        var before = option.get();
        try {
            option.set(false);
            level.setBlockAndUpdate(disabled, Blocks.WATER.defaultBlockState());
            setFluid(level, disabled.east(), "fluid_element_li", true);
            level.getBlockState(disabled.east()).tick(level, disabled.east(), level.getRandom());
            test.assertTrue(level.getFluidState(disabled).is(Fluids.WATER) && level.getFluidState(disabled.east()).isSource(),
                "Disabled block contact still consumed both inputs");
            level.setBlockAndUpdate(disabled, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(disabled.east(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(tnt.east(), Blocks.LAVA.defaultBlockState());
            setFluid(level, tnt, "fluid_molecule_tnt", true);
            level.getBlockState(tnt).tick(level, tnt, level.getRandom());
            test.assertTrue(level.isEmptyBlock(tnt), "Lava ignition must be independent of the contact config");
        } finally { option.set(before); }
        level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        setFluid(level, source.east(), "fluid_element_li", true);
        setFluid(level, flow, "fluid_element_li", true);
        level.setBlockAndUpdate(flow.east(), Fluids.WATER.getFlowing(7, false).createLegacyBlock());
        setFluid(level, salt, "fluid_molecule_sodiumhydroxide", true);
        setFluid(level, salt.east(), "fluid_molecule_hcl", true);
        test.runAfterDelay(2, () -> {
            test.assertTrue(level.isEmptyBlock(source) && level.isEmptyBlock(source.east()) &&
                atLayer(level, source, 1, "fluid_element_h") == 1 && atLayer(level, source, -1, "fluid_molecule_lithiumhydroxide") == 1,
                "Water and local lithium sources didn't yield the recorded gas above and solid below");
            test.assertTrue(level.isEmptyBlock(flow) && level.isEmptyBlock(flow.east()) &&
                atLayer(level, flow, 1, "flowing_fluid_element_h") == 1 && atLayer(level, flow, -1, "fluid_molecule_lithiumhydroxide") == 0,
                "A flowing reagent created a full source or a solid product");
            test.assertTrue(level.isEmptyBlock(salt) && level.isEmptyBlock(salt.east()) && atLayer(level, salt, -1, "fluid_molecule_salt") == 1,
                "An unordered pair of two local fluids failed to react");
            test.succeed();
        });
    }

    @GameTest(template = "fluid_room")
    public static void release394ItemContact(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(4, 5, 4));
        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        var bucket = droppedBucket(level, pos, 0.5, "bucket_element_li");
        var dryPair = pos.offset(6, 0, 0);
        var waterEdge = pos.offset(0, 0, 6);
        setFluid(level, dryPair, "fluid_molecule_hcl", true);
        setFluid(level, waterEdge, "fluid_molecule_hcl", true);
        level.setBlockAndUpdate(waterEdge.east(), Blocks.WATER.defaultBlockState());
        var dry = droppedBucket(level, dryPair, 0.5, "bucket_molecule_sodiumhydroxide");
        var wet = droppedBucket(level, waterEdge, 0.95, "bucket_molecule_sodiumhydroxide");
        var option = reactionOption("ITEM_CONTACT");
        var before = option.get();
        try {
            option.set(false);
            NeoForge.EVENT_BUS.post(new EntityTickEvent.Post(bucket));
            test.assertTrue(!bucket.isRemoved() && atLayer(level, pos, 1, "fluid_element_h") == 0,
                "Disabled item contact still spent the filled bucket");
        } finally { option.set(before); }
        test.runAfterDelay(2, () -> {
            test.assertTrue(bucket.isRemoved() && level.getFluidState(pos).is(Fluids.WATER) &&
                visibleItems(level, pos, new ItemStack(Items.BUCKET)) == 1 && atLayer(level, pos, 1, "fluid_element_h") == 1 &&
                atLayer(level, pos, -1, "fluid_molecule_lithiumhydroxide") == 1,
                "Dropped bucket didn't pay once, keep the water and return an empty bucket with both products");
            test.assertTrue(!dry.isRemoved() && wet.isRemoved() && atLayer(level, waterEdge, -1, "fluid_molecule_salt") == 1 &&
                atLayer(level, dryPair, -1, "fluid_molecule_salt") == 0,
                "Item contact didn't preserve the original vanilla-water bounding-box gate");
            test.succeed();
        });
    }

    private static ItemEntity droppedBucket(ServerLevel level, BlockPos pos, double xOffset, String path) {
        var item = new ItemEntity(level, pos.getX() + xOffset, pos.getY() + 0.1, pos.getZ() + 0.5, item(path, 1));
        item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        item.setNoGravity(true);
        level.addFreshEntity(item);
        return item;
    }

    @GameTest(template = "fluid_room")
    public static void release394DirectionalFlow(GameTestHelper test) {
        var level = test.getLevel();
        var gas = test.absolutePos(new BlockPos(3, 5, 3));
        var liquid = test.absolutePos(new BlockPos(10, 5, 3));
        var solid = test.absolutePos(new BlockPos(10, 5, 10));
        var capped = test.absolutePos(new BlockPos(3, 5, 10));
        for (var dir : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST))
            level.setBlockAndUpdate(capped.relative(dir), Blocks.STONE.defaultBlockState());
        setFluid(level, gas, "fluid_element_h", true);
        setFluid(level, liquid, "fluid_molecule_ethanol", true);
        setFluid(level, solid, "fluid_element_fe", true);
        setFluid(level, capped, "fluid_element_h", true);
        test.runAfterDelay(3, () -> {
            test.assertTrue(level.getFluidState(gas).isSource() && fluidAmount(level, gas.above(), "flowing_fluid_element_h") == 7 &&
                level.isEmptyBlock(gas.below()), "A gas did not extend an upward seven-quanta cell while keeping its source");
            test.assertTrue(fluidAmount(level, capped.east(), "flowing_fluid_element_h") == 7 && level.getBlockState(capped.above()).is(Blocks.STONE),
                "A cap did not deflect gas sideways, or was overwritten");
            test.assertTrue(level.isEmptyBlock(liquid.below()), "A liquid ignored its five-tick delay");
            level.setBlockAndUpdate(capped, Blocks.AIR.defaultBlockState());
            test.runAfterDelay(5, () -> {
                test.assertTrue(level.isEmptyBlock(capped.east()) && level.isEmptyBlock(capped.east().above()),
                    "Unsupported gas left a new column after the feeder was removed");
                test.assertTrue(level.getFluidState(liquid).isSource() && fluidAmount(level, liquid.below(), "flowing_fluid_molecule_ethanol") == 7 &&
                    level.isEmptyBlock(liquid.above()), "A liquid did not extend a downward seven-quanta cell while keeping its source");
                test.assertTrue(level.getFluidState(solid).isSource() && level.isEmptyBlock(solid.below()) &&
                    level.isEmptyBlock(solid.above()) && level.isEmptyBlock(solid.east()), "A one-quanta solid source started flowing");
                test.succeed();
            });
        });
    }

    private record Displacement(String label, String block, boolean flows, int drops) {}
    private static final Displacement[] DOWNWARD = {
@DOWNWARD@
    };

    @GameTest(template = "fluid_room")
    public static void release394Displacement(GameTestHelper test) {
        var level = test.getLevel();
        String ethanol = "fluid_molecule_ethanol";
        for (int i = 0; i < DOWNWARD.length; i++) {
            var sample = DOWNWARD[i];
            var target = test.absolutePos(new BlockPos(2 + i % 4 * 3, 5, 2 + i / 4 * 3));
            var incoming = target.above();
            level.setBlockAndUpdate(target.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            if (sample.label().equals("reeds")) level.setBlockAndUpdate(target.below().east(), Blocks.WATER.defaultBlockState());
            var previous = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(sample.block()));
            level.setBlock(target, previous.defaultBlockState(), 2);
            setFluid(level, incoming, ethanol, true);
            var box = new AABB(target).inflate(1);
            var before = level.getEntitiesOfClass(ItemEntity.class, box);
            tickFluid(level, incoming);
            boolean replaced = fluidAmount(level, target, "flowing_" + ethanol) == 7;
            int drops = level.getEntitiesOfClass(ItemEntity.class, box).stream().filter(entity -> !before.contains(entity))
                .mapToInt(entity -> entity.getItem().getCount()).sum();
            test.assertTrue(replaced == sample.flows() && drops == sample.drops(),
                "Downward displacement " + sample.label() + ": " + replaced + " / " + drops + " drops, state " + level.getBlockState(target));
            if (!sample.flows()) test.assertTrue(level.getBlockState(target).is(previous), "Blocked fluid displaced " + previous);
        }
        var gas = test.absolutePos(new BlockPos(2, 5, 11));
        setFluid(level, gas, "fluid_element_h", true);
        level.setBlockAndUpdate(gas.above(), Blocks.WATER.defaultBlockState());
        tickFluid(level, gas);
        test.assertTrue(fluidAmount(level, gas.above(), "flowing_fluid_element_h") == 7, "Gas could not displace vanilla water above");
        var density = gas.east(4);
        setFluid(level, density, "fluid_element_h", true);
        setFluid(level, density.above(), ethanol, true);
        tickFluid(level, density);
        test.assertTrue(fluidAmount(level, density.above(), ethanol) == 8, "Hydrogen displaced denser ethanol");
        for (boolean outlet : new boolean[]{false, true}) {
            var source = test.absolutePos(new BlockPos(3 + (outlet ? 7 : 0), 5, 13));
            for (var side : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST}) {
                if (outlet && side == Direction.WEST) continue;
                level.setBlockAndUpdate(source.relative(side), Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(source.east(), Blocks.WATER.defaultBlockState());
            setFluid(level, source, ethanol, true);
            tickFluid(level, source);
            test.assertTrue(outlet ? level.getFluidState(source.east()).is(Fluids.WATER) :
                fluidAmount(level, source.east(), "flowing_" + ethanol) == 7, "Fluid skipped Forge's blocked-route tie rule");
        }
        test.succeed();
    }

    private static void tickFluid(ServerLevel level, BlockPos pos) {
        level.getFluidState(pos).tick(level, pos);
    }

    @GameTest(template = "fluid_room")
    public static void release394SourceAgeAndContact(GameTestHelper test) throws ReflectiveOperationException {
        var level = test.getLevel();
        var radiation = Class.forName("mcmodup.generated." + MOD + ".ChemistryRadiation");
        var field = radiation.getDeclaredField("FLUID_EFFECTS");
        field.setAccessible(true);
        var option = (ModConfigSpec.BooleanValue)field.get(null);
        boolean original = option.get();
        test.assertTrue(original == @CONTACT_ENABLED@, "Wrong .394 fluid contact default");
        var chemicals = List.of("element_u", @RADCHLOR_PATH@);
        var clocks = new ArrayList<CompoundTag>();
        for (int index = 0; index < chemicals.size(); index++) {
            var path = chemicals.get(index);
            var source = test.absolutePos(new BlockPos(4 + index * 5, 5, 4));
            var fluidPath = "fluid_" + path;
            var bucket = radioactive(test, "bucket_" + path, 1, 3_000_000, level.getGameTime());
            clocks.add(clock(test, bucket));
            setFluid(level, source, fluidPath, true);
            ((net.minecraft.world.item.BucketItem)bucket.getItem()).checkExtraContent(null, level, bucket, source);
            var flow = source.south(4);
            setFluid(level, flow, fluidPath, false);
            test.assertTrue(level.getBlockEntity(source) != null && level.getBlockEntity(flow) == null,
                "Only .394 radioactive sources own saved clocks");
            var pig = EntityType.PIG.create(level);
            try {
                var state = level.getBlockState(source);
                state.entityInside(level, source, pig);
                String expected = index == 0 ? @CONTACT_URANIUM@ : @CONTACT_RADCHLOR@;
                test.assertTrue(contactEffects(pig).equals(expected), "Wrong .394 contact effects for " + path + ": " + contactEffects(pig));
                pig.removeAllEffects();
                option.set(false);
                state.entityInside(level, source, pig);
                test.assertTrue(pig.getActiveEffects().isEmpty(), "Disabled fluid effects still dosed a mob in " + path);
            } finally { option.set(original); }
        }
        test.runAfterDelay(26, () -> {
            for (int index = 0; index < chemicals.size(); index++) {
                var pos = test.absolutePos(new BlockPos(4 + index * 5, 5, 4));
                var path = chemicals.get(index);
                var state = level.getBlockState(pos);
                var source = level.getBlockEntity(pos);
                test.assertTrue(source != null && level.getFluidState(pos).isSource() && state.is(BuiltInRegistries.BLOCK.get(id("fluid_" + path))),
                    "An overdue fluid source decayed in the world instead of waiting for collection");
                var tag = source.saveWithoutMetadata(level.registryAccess());
                level.removeBlock(pos, false);
                level.setBlockAndUpdate(pos, state);
                level.getBlockEntity(pos).loadWithComponents(tag, level.registryAccess());
                var recovered = ((net.minecraft.world.level.block.BucketPickup) state.getBlock()).pickupBlock(null, level, pos, state);
                test.assertTrue(recovered.is(item("bucket_" + path, 1).getItem()) && clocks.get(index).equals(clock(test, recovered)),
                    "Pickup after reload changed the overdue source identity or clock");
            }
            test.succeed();
        });
    }

    private static String contactEffects(net.minecraft.world.entity.LivingEntity entity) {
        var effects = new ArrayList<String>();
        for (var effect : entity.getActiveEffects()) {
            var type = effect.getEffect();
            int oldId = type == MobEffects.WEAKNESS ? 18 : type == MobEffects.POISON ? 19 : type == MobEffects.WITHER ? 20 : -1;
            effects.add(oldId + ":" + effect.getAmplifier() + "/" + effect.getDuration());
        }
        effects.sort(String::compareTo);
        return effects.toString();
    }

    private static int fluidAmount(ServerLevel level, BlockPos pos, String path) {
        var state = level.getFluidState(pos);
        return state.getType() == BuiltInRegistries.FLUID.get(id(path)) ? state.getAmount() : 0;
    }

    @GameTest(template = "test_room", timeoutTicks = 240)
    public static void release394FurnaceFuels(GameTestHelper test) {
@FUEL_CHECKS@
        fuel(test, "element_fe", 0);
        fuel(test, "bucket_element_c", 0);
        var pos = test.absolutePos(new BlockPos(1, 1, 1));
        var level = test.getLevel();
        level.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
        var furnace = (Container) level.getBlockEntity(pos);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        furnace.setItem(1, item("element_c", 2));
        test.runAfterDelay(205, () -> {
            test.assertTrue(furnace.getItem(0).isEmpty() && furnace.getItem(2).is(Items.IRON_INGOT) &&
                furnace.getItem(2).getCount() == 1 && furnace.getItem(1).is(item("element_c", 1).getItem()) && furnace.getItem(1).getCount() == 1,
                "A 200-tick carbon vial didn't smelt exactly one iron in a stock furnace");
            test.succeed();
        });
    }

    private static void fuel(GameTestHelper test, String path, int ticks) {
        test.assertTrue(item(path, 1).getBurnTime(RecipeType.SMELTING) == ticks, "Wrong furnace burn ticks for " + path);
    }
    private static BlockPos dispenser(GameTestHelper test, int z, ItemStack first, ItemStack second) {
        var pos = test.absolutePos(new BlockPos(2, 4, z));
        var level = test.getLevel();
        level.setBlockAndUpdate(pos.east().below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        var contents = (Container) level.getBlockEntity(pos);
        contents.setItem(0, first);
        contents.setItem(1, second);
        return pos;
    }
    private static void powerDispenser(ServerLevel level, BlockPos pos, boolean on) {
        level.setBlockAndUpdate(pos.west(), (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
    }
    private static int count(ServerLevel level, BlockPos pos, ItemStack expected) {
        var contents = (Container) level.getBlockEntity(pos);
        int total = 0;
        for (int i = 0; i < contents.getContainerSize(); i++) {
            var stack = contents.getItem(i);
            if (stack.is(expected.getItem())) total += stack.getCount();
        }
        return total;
    }
    private static int visibleItems(ServerLevel level, BlockPos pos, ItemStack expected) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.5)).stream()
            .filter(entity -> entity.getItem().is(expected.getItem())).mapToInt(entity -> entity.getItem().getCount()).sum();
    }
    private static void setFluid(ServerLevel level, BlockPos pos, String path, boolean source) {
        var fluid = (FlowingFluid) BuiltInRegistries.FLUID.get(id(path));
        level.setBlockAndUpdate(pos, (source ? fluid.defaultFluidState() : fluid.getFlowing(7, false)).createLegacyBlock());
    }
    private static int atLayer(ServerLevel level, BlockPos center, int layer, String path) {
        var fluid = BuiltInRegistries.FLUID.get(id(path));
        int count = 0;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            if (level.getFluidState(center.offset(x, layer, z)).getType() == fluid) count++;
        }
        return count;
    }
    private static ModConfigSpec.BooleanValue reactionOption(String name) {
        try {
            var field = Class.forName("mcmodup.generated." + MOD + ".FluidReactions").getDeclaredField(name);
            field.setAccessible(true);
            return (ModConfigSpec.BooleanValue) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

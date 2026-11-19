    @GameTest(template = "test_room")
    public static void release394TubeBucketAndContact(GameTestHelper test) {
        var level = test.getLevel();
        var iron = item("element_fe", 1);
        var bucket = item("bucket_element_fe", 1);
        var fluid = BuiltInRegistries.FLUID.get(id("fluid_element_fe"));
        var parts = new ArrayList<ItemStack>();
        parts.add(new ItemStack(Items.BUCKET));
        for (int i = 1; i < 9; i++) parts.add(iron.copy());
        test.assertTrue(ItemStack.matches(craft(test, CraftingInput.of(3, 3, parts)), bucket), "Eight iron tubes and a bucket didn't craft one full bucket");
        var reverse = CraftingInput.of(1, 1, List.of(bucket));
        test.assertTrue(craft(test, reverse).getCount() == 8 && craft(test, reverse).is(iron.getItem()), "Bucket didn't return eight original tubes");
        var remaining = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, reverse, level).orElseThrow().value().getRemainingItems(reverse);
        test.assertTrue(remaining.getFirst().is(Items.BUCKET), "Reverse craft lost its bucket");
        parts.set(1, item("element_cu", 1));
        test.assertTrue(craft(test, CraftingInput.of(3, 3, parts)).isEmpty(), "Mixed chemicals passed bucket recipe");
        for (int i = 1; i < 9; i++) parts.set(i, item("molecule_water", 1));
        test.assertTrue(craft(test, CraftingInput.of(3, 3, parts)).isEmpty() &&
            craft(test, CraftingInput.of(1, 1, List.of(new ItemStack(Items.WATER_BUCKET)))).is(item("molecule_water", 1).getItem()),
            "The .394 water bucket crafting asymmetry was lost");
        var vial = iron.getCapability(Capabilities.FluidHandler.ITEM);
        test.assertTrue(vial != null && vial.drain(124, FluidAction.EXECUTE).isEmpty() && vial.getContainer().is(iron.getItem()), "Partial tube drain lost fluid");
        var water = item("molecule_water", 1).getCapability(Capabilities.FluidHandler.ITEM);
        test.assertTrue(water != null && water.drain(125, FluidAction.SIMULATE).is(Fluids.WATER), "Water tube no longer carries vanilla water");
        var amount = vial.drain(125, FluidAction.SIMULATE);
        test.assertTrue(amount.is(fluid) && amount.getAmount() == 125 && vial.getContainer().is(iron.getItem()), "Simulating a tube drain consumed it");
        test.assertTrue(vial.drain(125, FluidAction.EXECUTE).getAmount() == 125 && vial.getContainer().is(item("test_tube", 1).getItem()), "Draining didn't return an empty tube");
        test.assertTrue(vial.fill(amount, FluidAction.EXECUTE) == 125 && vial.getContainer().is(iron.getItem()), "Empty tube cannot be filled back from chemical fluid");
        var pos = place(test, "decomposer", new BlockPos(1, 1, 1));
        var tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.NORTH);
        tank.fill(new FluidStack(fluid, 4900), FluidAction.EXECUTE);
        test.assertTrue(!FluidUtil.tryEmptyContainer(iron, tank, 125, null, true).isSuccess() && tank.getFluidInTank(0).getAmount() == 4900,
            "A full machine took a partial tube and lost the rest");
        tank.drain(25, FluidAction.EXECUTE);
        var transferred = FluidUtil.tryEmptyContainer(iron, tank, 125, null, true);
        test.assertTrue(transferred.isSuccess() && transferred.getResult().is(item("test_tube", 1).getItem()) &&
            tank.getFluidInTank(0).getAmount() == 5000, "Exactly 125 mB of space didn't exchange one tube");
        var pig = test.spawn(EntityType.PIG, new BlockPos(1, 1, 2));
        var hazard = place(test, "fluid_element_u", new BlockPos(1, 1, 2));
        level.getBlockState(hazard).entityInside(level, hazard, pig);
        test.assertTrue(pig.hasEffect(MobEffects.POISON), "Radioactive .394 fluid had no on-contact effect");
        var bucketHandler = bucket.getCapability(Capabilities.FluidHandler.ITEM);
        test.assertTrue(bucketHandler != null && bucketHandler.drain(1000, FluidAction.SIMULATE).getAmount() == 1000 &&
            bucketHandler.drain(999, FluidAction.EXECUTE).isEmpty(), "Bucket didn't report its full 1000 mB capacity");
        test.succeed();
    }

    @GameTest(template = "test_room")
    public static void release394RadioactiveTubeSource(GameTestHelper test) throws Exception {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(3, 2, 3));
        var floor = pos.below();
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        var actor = new net.neoforged.neoforge.common.util.FakePlayer(level,
            new com.mojang.authlib.GameProfile(new java.util.UUID(26, 394), "mcmodupTube"));
        var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        var base = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(floor), Direction.UP, floor, false);
        var sourceHit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.UP, pos, false);
        actor.setPos(floor.getX() + 0.5, floor.getY(), floor.getZ() + 1.5);
        var uranium = item("element_u", 3);
        var other = radioactive(test, "element_u", 5, 310, level.getGameTime());
        var oldest = clock(test, other);
        actor.setItemInHand(hand, uranium);
        actor.getInventory().setItem(9, other.copyWithCount(4));
        test.assertTrue(!tubeUse("pour", level, actor, hand, base).getResult().consumesAction() && uranium.getCount() == 3 && level.isEmptyBlock(pos),
            "Fewer than eight tubes placed a source or lost payment");
        actor.getInventory().setItem(9, other);
        var empty = tubeUse("pour", level, actor, hand, base).getObject();
        var source = level.getBlockEntity(pos);
        test.assertTrue(empty.is(item("test_tube", 1).getItem()) && empty.getCount() == 8 && actor.getInventory().getItem(9).isEmpty() && source != null,
            "A full source didn't take exactly eight split uranium tubes");
        var state = level.getBlockState(pos);
        var tag = source.saveWithoutMetadata(level.registryAccess());
        level.removeBlock(pos, false);
        level.setBlockAndUpdate(pos, state);
        level.getBlockEntity(pos).loadWithComponents(tag, level.registryAccess());
        actor.setItemInHand(hand, empty);
        var filled = tubeUse("collect", level, actor, hand, sourceHit).getObject();
        test.assertTrue(filled.is(item("element_u", 1).getItem()) && filled.getCount() == 8 && oldest.equals(clock(test, filled)) && level.isEmptyBlock(pos),
            "Source pickup didn't restore eight tubes and their oldest persisted clock");
        test.succeed();
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.world.InteractionResultHolder<ItemStack> tubeUse(String action, net.minecraft.world.level.Level level,
        Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) throws Exception {
        var base = item("test_tube", 1).getItem().getClass().getEnclosingClass();
        var method = base.getDeclaredMethod(action, net.minecraft.world.level.Level.class, Player.class,
            net.minecraft.world.InteractionHand.class, net.minecraft.world.phys.BlockHitResult.class);
        method.setAccessible(true);
        return (net.minecraft.world.InteractionResultHolder<ItemStack>) method.invoke(null, level, player, hand, hit);
    }

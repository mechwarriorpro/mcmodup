    private record Exposure(String item, int count, float damage, String time) {}
    private static final Map<String, Exposure> LEGACY_DECAY = Map.ofEntries(
@PLAYER_CASES@
    );

    @GameTest(template = "test_room", timeoutTicks = 65)
    public static void @PREFIX@DecayScheduler(GameTestHelper test) {
        long now = test.getLevel().getGameTime();
        int delay = 1 - (int)(now % 20);
        test.runAfterDelay(delay > 0 ? delay : delay + 20, () -> {
            var actor = decayObserver(test);
            actor.getInventory().setItem(0, expiredUranium(test));
            var sample = actor.getInventory().getItem(0).copy();
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(actor));
            unchanged(test, actor, sample, actor.getInventory().getItem(0), "End tick");
            if (NEWER) {
                observeDecay(test, "tick_start", actor, () -> actor.getInventory().getItem(0));
                observeDecay(test, "repeat", actor, () -> actor.getInventory().getItem(0));
                test.succeed();
            } else {
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Pre(actor));
                unchanged(test, actor, sample, actor.getInventory().getItem(0), "FINAL outside its one-second update");
                test.runAfterDelay(19, () -> {
                    observeDecay(test, "hotbar", actor, () -> actor.getInventory().getItem(0));
                    observeDecay(test, "repeat", actor, () -> actor.getInventory().getItem(0));
                    test.succeed();
                });
            }
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 65)
    public static void @PREFIX@DecayPlayerAndChest(GameTestHelper test) {
        onDecayTick(test, () -> {
            var armored = decayObserver(test);
            // Force vanilla armor points on the unspawned player too, so the test detects an armor-sensitive source.
            armored.getAttribute(Attributes.ARMOR).setBaseValue(20);
            armored.getInventory().setItem(0, expiredUranium(test));
            observeDecay(test, "vanilla_armor", armored, () -> armored.getInventory().getItem(0));

            var cursor = decayObserver(test);
            cursor.containerMenu.setCarried(expiredUranium(test));
            observeDecay(test, "cursor", cursor, cursor.containerMenu::getCarried);
            cursor.getInventory().setItem(0, cursor.containerMenu.getCarried());
            cursor.containerMenu.setCarried(ItemStack.EMPTY);
            observeDecay(test, "cursor_placed", cursor, () -> cursor.getInventory().getItem(0));

            var actor = decayObserver(test);
            var storage = new SimpleContainer(27);
            actor.containerMenu = ChestMenu.threeRows(1, actor.getInventory(), storage);
            storage.setItem(0, expiredUranium(test));
            observeDecay(test, "vanilla_chest", actor, () -> storage.getItem(0));
            var preview = decayObserver(test);
            var craftResult = preview.inventoryMenu.getSlot(0);
            craftResult.set(expiredUranium(test));
            var ghost = craftResult.getItem().copy();
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Pre(preview));
            unchanged(test, preview, ghost, craftResult.getItem(), "Unpaid crafting preview");
            test.succeed();
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 65)
    public static void @PREFIX@DecayMachineSlots(GameTestHelper test) {
        onDecayTick(test, () -> {
            decayMachine(test, "leaded", "leaded_chest", new BlockPos(1, 1, 1), 0, 26, 27);
            decayMachine(test, "synthesis", "synthesizer", new BlockPos(2, 1, 1), 10, 15, 16);
@REACTOR_DECAY_CASES@
            if (NEWER) {
                var option = decayMachineOption();
                boolean before = option.get();
                try {
                    option.set(false);
                    decayMachine(test, "decomposer_false", "decomposer", new BlockPos(2, 1, 2), 0, 25, 26);
                    option.set(true);
                    decayMachine(test, "decomposer_true", "decomposer", new BlockPos(3, 1, 2), 0, 25, 26);
                } finally { option.set(before); }
            } else {
                decayMachine(test, "decomposer", "decomposer", new BlockPos(2, 1, 2), 0, 25, 26);
            }
            test.succeed();
        });
    }

    private static void onDecayTick(GameTestHelper test, Runnable action) {
        test.runAfterDelay(NEWER ? 1 : 20 - (test.getLevel().getGameTime() % 20), action);
    }

    private static void decayMachine(GameTestHelper test, String label, String path, BlockPos relative, int stored, int early, int late) {
        var pos = place(test, path, relative);
        var tile = (MenuProvider) test.getLevel().getBlockEntity(pos);
        var actor = decayObserver(test);
        actor.containerMenu = tile.createMenu(1, actor.getInventory(), actor);
        var slot = actor.containerMenu.getSlot(stored);
        slot.set(expiredUranium(test));
        observeDecay(test, label + "_storage", actor, slot::getItem);
        slot.set(ItemStack.EMPTY);
        if (label.equals("fission")) {
            var viewer = decayObserver(test);
            // A real output-only slot must keep the decay product in place.
            viewer.containerMenu = tile.createMenu(1, viewer.getInventory(), viewer);
            var output = viewer.containerMenu.getSlot(NEWER ? 1 : 2);
            output.set(expiredUranium(test));
            observeDecay(test, label + "_storage", viewer, output::getItem);
            output.set(ItemStack.EMPTY);
        }
        for (int index : new int[]{early, late, 0}) {
            var viewer = decayObserver(test);
            var inv = viewer.getInventory();
            viewer.containerMenu = tile.createMenu(1, inv, viewer);
            inv.setItem(index, expiredUranium(test));
            observeDecay(test, label + "_player_" + index, viewer, () -> inv.getItem(index));
        }
    }

    private static ModConfigSpec.BooleanValue decayMachineOption() {
        try {
            var field = Class.forName("mcmodup.generated." + MOD + ".ChemistryRadiation").getDeclaredField("DECAY_SAFE");
            field.setAccessible(true);
            return (ModConfigSpec.BooleanValue) field.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static ItemStack expiredUranium(GameTestHelper test) {
        return radioactive(test, "element_u", 3, LIFE + 1201, test.getLevel().getGameTime());
    }

    private static void observeDecay(GameTestHelper test, String key, DecayObserver actor, Supplier<ItemStack> slot) {
        var target = LEGACY_DECAY.get(key);
        var initial = slot.get().getItem();
        float before = actor.getHealth();
        actor.messages.clear();
        NeoForge.EVENT_BUS.post(new PlayerTickEvent.Pre(actor));
        var result = slot.get();
        test.assertTrue(result.is(item(target.item(), 1).getItem()) && result.getCount() == target.count(), key + " replaced the wrong slot: " + result);
        test.assertTrue(before - actor.getHealth() == target.damage(), key + " dealt " + (before - actor.getHealth()) + " instead of " + target.damage());
        if (target.time() == null) {
            test.assertTrue(actor.messages.isEmpty(), key + " reported a decay when none occurred");
            return;
        }
        var expected = NEWER
            ? Component.translatable("message." + MOD + ".radiation_decay", initial.getDescription(), result.getItem().getDescription(), target.time())
            : Component.translatable("message." + MOD + ".radiation_decay", initial.getDescription(), result.getItem().getDescription());
        test.assertTrue(actor.messages.equals(List.of(expected)), key + " didn't report one release-local decay with the right arguments: " + actor.messages);
    }

    private static void unchanged(GameTestHelper test, DecayObserver actor, ItemStack sample, ItemStack actual, String context) {
        test.assertTrue(ItemStack.matches(sample, actual) && actor.messages.isEmpty() && actor.getHealth() == 20F, context + " processed radiation");
    }

    private static DecayObserver decayObserver(GameTestHelper test) {
        return new DecayObserver(test.getLevel(), test.absolutePos(new BlockPos(1, 1, 1)));
    }

    private static final class DecayObserver extends Player {
        final List<Component> messages = new ArrayList<>();
        final List<ItemStack> drops = new ArrayList<>();
        DecayObserver(ServerLevel level, BlockPos pos) {
            super(level, pos, 0F, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DecayProbe"));
        }
        @Override public boolean isCreative() { return false; }
        @Override public boolean isSpectator() { return false; }
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public ItemEntity drop(ItemStack offered, boolean thrown) {
            drops.add(offered.copy());
            return null;
        }
    }

@BUCKET_DECAY_CHECKS@

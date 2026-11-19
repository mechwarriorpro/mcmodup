    @GameTest(template = "test_room")
    public static void release394DecayBuckets(GameTestHelper test) {
@BUCKET_CASES@
        test.succeed();
    }

    private static void decayBucket(GameTestHelper test, String label, String kind, long age, Map<String, Integer> expected, boolean full) {
        var actor = decayObserver(test);
        var inv = actor.getInventory();
        if (full) for (int i = 1; i < 36; i++) inv.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        inv.setItem(0, radioactive(test, kind, 1, age, test.getLevel().getGameTime()));
        observeDecay(test, label, actor, () -> inv.getItem(0));
        test.assertTrue(clock(test, inv.getItem(0)).getLong("age") == 0, label + " kept the consumed chemical's age");
        var extras = new java.util.HashMap<String, Integer>();
        var byproducts = new ArrayList<>(actor.drops);
        for (int i = 1; i < 36; i++) byproducts.add(inv.getItem(i));
        for (var stack : byproducts) {
            if (stack.isEmpty() || stack.is(Items.COBBLESTONE)) continue;
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            test.assertTrue(id.getNamespace().equals(MOD) && stack.getCount() <= stack.getMaxStackSize(), label + " spawned an invalid byproduct");
            extras.merge(id.getPath(), stack.getCount(), Integer::sum);
        }
        test.assertTrue(extras.equals(expected) && full == !actor.drops.isEmpty(), label + " lost or duplicated byproducts: " + extras);
    }

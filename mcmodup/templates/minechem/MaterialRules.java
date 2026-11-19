package @PACKAGE@;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import @PACKAGE@.ChemistryRecipes.Part;
import @PACKAGE@.ChemistryRecipes.Recipe;

/** Frozen original material graphs. Lookup and previews never consume a random roll. */
final class MaterialRules {
    private record Ref(String key, double weight) {}
    private record Node(float chance, List<Part> parts, List<Node> choices, List<Ref> refs) {
        boolean variable() {
            return chance < 1F || choices.size() > 1 || choices.stream().anyMatch(Node::variable) ||
                refs.stream().anyMatch(ref -> ref.weight() % 1 != 0 || GRAPH.get(ref.key()).variable());
        }

        void roll(RandomSource random, List<Part> result) {
            if (chance < 1F && random.nextFloat() >= chance) return;
            if (!choices.isEmpty()) {
                choices.get(random.nextInt(choices.size())).roll(random, result);
                return;
            }
            result.addAll(parts);
            for (var ref : refs) {
                var child = GRAPH.get(ref.key());
                double remainder = ref.weight();
                while (remainder >= 1.0) {
                    child.roll(random, result);
                    remainder--;
                }
                if (remainder > 0 && random.nextDouble() < remainder) child.roll(random, result);
            }
        }
    }

    private record Entry(Recipe recipe, String node) {}
    private static final Map<String, Node> GRAPH = new HashMap<>();
    private static final Map<String, Entry> ITEMS = new HashMap<>(), FLUIDS = new HashMap<>();

    private static final Set<String> VARIABLE_ITEMS = new HashSet<>();

    static {
        var path = "/data/" + CatalogPort.MOD_ID + "/mcmodup/materials.txt";
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                MaterialRules.class.getResourceAsStream(path), path), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.startsWith("!")) {
                    int split = line.indexOf('=');
                    GRAPH.put(line.substring(0, split), parse(line.substring(split + 1)));
                    continue;
                }
                var row = line.substring(1).split(";", -1);
                int volume = Integer.parseInt(row[3]);
                var products = parts(row[4]);
                var inputs = volume == 0 ? List.of(new Part(row[1], 1)) : List.<Part>of();
                var recipe = new Recipe(row[1], Integer.parseInt(row[2]), products.stream().mapToInt(Part::count).sum(), volume, inputs, products);
                (volume == 0 ? ITEMS : FLUIDS).put(recipe.id(), new Entry(recipe, row[0]));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Minechem material table " + path, e);
        }
        ITEMS.forEach((id, entry) -> { if (GRAPH.get(entry.node()).variable()) VARIABLE_ITEMS.add(id); });
    }

    private static List<Part> parts(String row) {
        if (row.equals("-") || row.isEmpty()) return List.of();
        var result = new ArrayList<Part>();
        for (var token : row.split(",")) {
            int end = token.lastIndexOf('@');
            result.add(new Part(token.substring(0, end), Integer.parseInt(token.substring(end + 1))));
        }
        return List.copyOf(result);
    }

    private static Node parse(String row) {
        if (row.startsWith("S") || row.startsWith("C")) {
            int split = row.indexOf('!');
            float chance = Float.parseFloat(row.substring(1, split));
            if (row.startsWith("C")) return new Node(chance, parts(row.substring(split + 1)), List.of(), List.of());
            var choices = new ArrayList<Node>();
            for (String branch : row.substring(split + 1).split("\\|")) choices.add(parse(branch));
            return new Node(chance, List.of(), List.copyOf(choices), List.of());
        }
        if (row.startsWith("R")) {
            var tokens = row.substring(1).split("\\|");
            var refs = new ArrayList<Ref>();
            for (int i = 1; i < tokens.length; i++) {
                int split = tokens[i].lastIndexOf('@');
                refs.add(new Ref(tokens[i].substring(0, split), Double.parseDouble(tokens[i].substring(split + 1))));
            }
            return new Node(1, parts(tokens[0]), List.of(), List.copyOf(refs));
        }
        return new Node(1, parts(row), List.of(), List.of());
    }

    static List<Recipe> recipes(boolean fluid) {
        return (fluid ? FLUIDS : ITEMS).values().stream().map(Entry::recipe).toList();
    }

    static List<Part> roll(Recipe recipe, RandomSource random) {
        var entry = (recipe.fluidAmount() == 0 ? ITEMS : FLUIDS).get(recipe.id());
        var products = new ArrayList<Part>();
        GRAPH.get(entry.node()).roll(random, products);
        return products;
    }

    static boolean variable(Recipe recipe) { return VARIABLE_ITEMS.contains(recipe.id()); }

    static boolean validInput(ItemStack stack) {
        // The old rows name metadata zero for equipment and the water variant of bottles.
        return !stack.isEmpty() && !stack.isDamaged() && (!stack.is(Items.POTION) ||
                new PotionContents(Potions.WATER).equals(stack.get(DataComponents.POTION_CONTENTS)));
    }

    private MaterialRules() {}
}

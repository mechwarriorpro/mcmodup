package mcmodup.test;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod(ConnectorMixedSmoke.ID)
@GameTestHolder(ConnectorMixedSmoke.ID)
@PrefixGameTestTemplate(false)
public final class ConnectorMixedSmoke {
    public static final String ID = "mcmodup_connector_mixed";

    @GameTest(template = "empty")
    public static void exactArchivesFound(GameTestHelper helper) throws IOException {
        var mods = ModList.get();
        for (String id : new String[]{"legacyconnector", "cb_multipart", "codechickenlib"}) {
            helper.assertTrue(mods.isLoaded(id), "Missing required mod " + id);
        }
        var expected = JsonParser.parseString(Files.readString(Path.of("original-identities.json"))).getAsJsonObject();
        var matched = new HashSet<String>();
        var errors = new ArrayList<String>();
        for (var modFile : mods.getModFiles()) {
            var path = modFile.getFile().findResource("META-INF", "legacyconnector", "provenance.json");
            if (!Files.isRegularFile(path)) continue;
            var proof = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String fingerprint = proof.get("originalSha256").getAsString();
            if (!expected.has(fingerprint)) continue;
            var entry = expected.getAsJsonObject(fingerprint);
            String name = entry.get("name").getAsString();
            var legacyIds = new TreeSet<String>();
            var wantedIds = new TreeSet<String>();
            proof.getAsJsonArray("mods").forEach(row -> legacyIds.add(row.getAsJsonObject().get("id").getAsString().toLowerCase(java.util.Locale.ROOT)));
            entry.getAsJsonArray("ids").forEach(id -> wantedIds.add(id.getAsString()));
            var loadedIds = new TreeSet<String>();
            modFile.getMods().forEach(row -> {
                if (mods.isLoaded(row.getModId())) loadedIds.add(row.getModId());
            });
            var meta = modFile.getFile().findResource("META-INF", "legacyconnector", "legacy-mods.json");
            var namespaces = new TreeSet<String>();
            if (Files.isRegularFile(meta)) {
                var bindings = JsonParser.parseString(Files.readString(meta)).getAsJsonObject().getAsJsonArray("mods");
                bindings.forEach(row -> namespaces.add(row.getAsJsonObject().get("modernId").getAsString()));
            } else {
                namespaces.addAll(loadedIds);
            }
            var items = BuiltInRegistries.ITEM.keySet().stream().filter(key -> namespaces.contains(key.getNamespace())).sorted().toList();
            var blocks = BuiltInRegistries.BLOCK.keySet().stream().filter(key -> namespaces.contains(key.getNamespace())).sorted().toList();
            var crafting = helper.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(recipe -> namespaces.contains(recipe.id().getNamespace())).map(recipe -> recipe.id()).sorted().toList();
            // Namespace counts are diagnostic, not proof of ownership when two releases share a namespace.
            System.out.printf("MCMODUP_CONNECTOR_IDENTITY %s: %s -> %s; namespaces=%s; items=%d %s; blocks=%d %s; crafting=%d %s%n",
                name, legacyIds, loadedIds, namespaces, items.size(), items.stream().limit(5).toList(), blocks.size(),
                blocks.stream().limit(5).toList(), crafting.size(), crafting.stream().limit(5).toList());
            if (!legacyIds.equals(wantedIds) || loadedIds.size() != wantedIds.size() || !matched.add(fingerprint)) {
                errors.add("Module mismatch for " + name + ": " + legacyIds + " -> " + loadedIds);
            }
        }
        expected.asMap().forEach((sha, row) -> {
            if (!matched.contains(sha)) errors.add("Missing " + row.getAsJsonObject().get("name").getAsString());
        });
        helper.assertTrue(errors.isEmpty(), String.join("; ", errors));
        helper.succeed();
    }
}

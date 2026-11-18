"""Generate NeoForge machine logic and a recipe table for the recovered Minechem catalog."""

from dataclasses import dataclass
import json
from pathlib import Path

from .catalogs import CatalogEntry
from .inputs import InputError


TEMPLATES = Path(__file__).with_name("templates") / "minechem"
JAVA_CLASSES = ("ChemistryMachines", "ChemistryMenu", "ChemistryScreen", "ChemistryRecipes")


@dataclass(frozen=True)
class MachineRecipe:
    machine: str
    id: str
    inputs: tuple[tuple[str, int], ...]
    outputs: tuple[tuple[str, int], ...]
    energy: int
    ticks: int
    source: str

    def describe(self) -> dict:
        return {"machine": self.machine, "id": self.id, "energy_fe": self.energy, "ticks": self.ticks,
                "inputs": [{"id": name, "count": count} for name, count in self.inputs],
                "outputs": [{"id": name, "count": count} for name, count in self.outputs],
                "legacy_source": self.source}


def _decomposition(source_item: str, products: tuple[tuple[str, int], ...], source: str) -> MachineRecipe:
    # The legacy decomposer spent one unit of power per output item. Emit the batch atomically.
    amount = sum(count for _, count in products)
    return MachineRecipe("decomposer", source_item, ((source_item, 1),), products, amount, amount, source)


def machine_recipes(namespace: str, entries: list[CatalogEntry]) -> list[MachineRecipe]:
    recipes = []
    for entry in sorted(entries, key=lambda item: item.path):
        if not entry.components:
            continue
        compound = f"{namespace}:{entry.path}"
        parts = tuple((f"{namespace}:{item}", count) for item, count in entry.components)
        if sum((count + 63) // 64 for _, count in parts) > 18:
            raise InputError(f"Recipe for {compound} does not fit the machine's 18 inventory slots.")
        recipes.extend((
            MachineRecipe("synthesizer", compound, parts, ((compound, 1),), 500, 20, "MoleculeEnum"),
            _decomposition(compound, parts, "MoleculeEnum"),
        ))
    # Reviewed deterministic 5.0.5 FINAL rules from registerVanillaChemicalRecipes. This is
    # not a generic evaluator for the old registry method or the pack's config/ore recipes.
    catalog = {entry.path for entry in entries}
    for vanilla, path, amount in (("water_bucket", "molecule_water", 16),
                                  ("iron_ingot", "element_fe", 16), ("gold_ingot", "element_au", 16)):
        if path in catalog:
            original, chemical = f"minecraft:{vanilla}", f"{namespace}:{path}"
            recipes.append(_decomposition(original, ((chemical, amount),), "MinechemRecipes (reviewed starter mapping)"))
            if vanilla != "water_bucket":
                recipes.append(MachineRecipe("synthesizer", original, ((chemical, amount),), ((original, 1),),
                                             10_000, 20, "MinechemRecipes (reviewed starter mapping)"))
    return recipes


def _java_recipes(package: str, namespace: str, entries: list[CatalogEntry], recipes: list[MachineRecipe]) -> str:
    def parts(values: tuple[tuple[str, int], ...]) -> str:
        return "List.of(" + ", ".join(f'new Part("{item}", {count})' for item, count in values) + ")"

    def rows(machine: str) -> str:
        return ",\n".join(f'        new Recipe("{r.id}", {r.energy}, {r.ticks}, {parts(r.inputs)}, {parts(r.outputs)})'
                           for r in recipes if r.machine == machine)

    chemical_ids = ",\n".join(f'        "{namespace}:{entry.path}"' for entry in entries)
    return f'''package {package};

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

final class ChemistryRecipes {{
    record Part(String id, int count) {{
        Item item() {{ return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }}
    }}

    record Recipe(String id, int energy, int ticks, List<Part> inputs, List<Part> outputs) {{}}

    static final Set<String> CHEMICALS = Set.of(
{chemical_ids}
    );
    static final List<Recipe> SYNTHESIZE = List.of(
{rows('synthesizer')}
    );
    static final Map<String, Recipe> BY_RESULT = index(SYNTHESIZE);
    static final Map<String, Recipe> DECOMPOSE = index(List.of(
{rows('decomposer')}
    ));

    private static Map<String, Recipe> index(List<Recipe> recipes) {{
        return recipes.stream().collect(Collectors.toUnmodifiableMap(Recipe::id, Function.identity()));
    }}

    static int indexOf(String id) {{
        for (int i = 0; i < SYNTHESIZE.size(); i++) if (SYNTHESIZE.get(i).id().equals(id)) return i;
        return 0;
    }}
}}
'''


def machine_project(namespace: str, package: str, entries: list[CatalogEntry], recipes: list[MachineRecipe]) -> tuple[dict[str, str], dict[str, str]]:
    def document(value: dict) -> str:
        return json.dumps(value, indent=2) + "\n"

    java = "src/main/java/" + package.replace(".", "/")
    source = "src/main/resources"
    assets = f"{source}/assets/{namespace}"
    data = f"{source}/data"
    files = {f"{java}/{name}.java": (TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
             .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace)
             for name in JAVA_CLASSES if name != "ChemistryRecipes"}
    files[f"{java}/ChemistryRecipes.java"] = _java_recipes(package, namespace, entries, recipes)
    locales = {f"gui.{namespace}.{key}": label for key, label in {
        "process": "Process", "decompose": "Insert one material to split", "charge": "Optional fuel: Redstone Dust (+500 FE when needed)",
        "requirement": "%s × %s", "recipe_help": "Recipe ingredients (hold Shift to skip 10 recipes)",
        "state_0": "Ready · %s FE", "state_1": "Working · %s FE", "state_2": "Need input · %s FE",
        "state_3": "Need power · %s FE", "state_4": "Output full · %s FE", "state_5": "Done · %s FE",
    }.items()}
    for block, name, texture, pattern, keys in (
        ("synthesizer", "Chemical Synthesizer", "minecraft:block/lapis_block", ["IRI", "IPI", "IDI"], {"D": {"item": "minecraft:diamond"}}),
        ("decomposer", "Chemical Decomposer", "minecraft:block/iron_block", ["III", "IPI", "IRI"], {}),
    ):
        model = f"{namespace}:block/{block}"
        identifier = f"{namespace}:{block}"
        locales[f"block.{namespace}.{block}"] = name
        files[f"{assets}/blockstates/{block}.json"] = document({"variants": {"": {"model": model}}})
        files[f"{assets}/models/block/{block}.json"] = document({"parent": "minecraft:block/cube_all", "textures": {"all": texture}})
        files[f"{assets}/models/item/{block}.json"] = document({"parent": model})
        files[f"{data}/{namespace}/loot_table/blocks/{block}.json"] = document({
            "type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [{"condition": "minecraft:survives_explosion"}],
                                                  "entries": [{"type": "minecraft:item", "name": identifier}]}],
        })
        # Keep the machine craftable without the still-unported Atomic Manipulator.
        files[f"{data}/{namespace}/recipe/{block}.json"] = document({
            "type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
            "key": {"I": {"item": "minecraft:iron_ingot"}, "R": {"item": "minecraft:redstone"},
                    "P": {"item": "minecraft:piston"}, **keys}, "result": {"id": identifier, "count": 1},
        })
    files[f"{data}/minecraft/tags/block/mineable/pickaxe.json"] = document(
        {"replace": False, "values": [f"{namespace}:synthesizer", f"{namespace}:decomposer"]})
    return files, locales

"""Build an exact toy port and its add-on, then test the staged production JARs."""

import argparse
import json
from pathlib import Path
import shutil
import tempfile
import zipfile

from mcmodup.native_pack import REPORT_NAME, inventory, plan, stage
from mcmodup.port import _build_gradle, write_workspace
from mcmodup.source_ports import BuildOptions, digest
from native_pack_smoke import ADDON, LIB, fixture_metadata, run_gradle
from neoforge_smoke import empty_test_structure
from test_cli import PNG
from test_native_pack import legacy, native, setup_platform
from test_source_ports import recipe, write_manifest

BASE = "retro_ruby"
HOST = "mcmodup_source_check"
ASSET = "assets/retro_ruby/textures/item/ruby.png"
LANG = "assets/retro_ruby/lang/en_us.json"
OLD_LANG = "assets/retro_ruby/lang/en_US.lang"
MOD_VERSION = "1.0.0"


def prepare(root: Path, *, demo=False) -> Path:
    old, target = root / "Legacy source-build example", root / "NeoForge 1.21.1 example"
    setup_platform(old, "1.7.10")
    setup_platform(target)
    base = legacy(old, "mods/Retro Ruby.jar", BASE, name="Retro Ruby", owner="example/retroruby/RetroRuby")
    addon = legacy(old, "mods/Ruby Workshop.jar", ADDON, name="Ruby Workshop", requirements=("required-after:retro_ruby",))
    with zipfile.ZipFile(base, "a") as archive:
        archive.writestr(ASSET, PNG)
        archive.writestr(OLD_LANG, "item.ruby.name=Retro Ruby\n")
    if demo:
        native(target, "lib/Ruby-Tools.jar", LIB, version=MOD_VERSION)
        # For the read-only preview only. The executable smoke builds the native library below instead.
        legacy(old, "mods/Example without a recipe.jar", "old_unsupported", name="Example without a recipe")
    else:
        lib = make_project(root / "library", LIB, "RubyTools", "public static int multiplier() { return 2; }")
        run_gradle(lib, "jar")
        (target / "lib").mkdir()
        shutil.copyfile(lib / f"build/libs/{LIB}.jar", target / "lib/Ruby-Tools.jar")
    language = '{"item.retro_ruby.ruby":"Retro Ruby"}\n'
    base_project = make_project(root / "base", BASE, "RubyMod", f"""
    public static int stackSize() {{ return 8; }}
    public RubyMod(IEventBus bus) {{
        var items = DeferredRegister.createItems("{BASE}");
        items.registerSimpleItem("ruby");
        items.register(bus);
    }}
""", assertions=check_item("retro_ruby:ruby", 64), resources={
        LANG: language,
        "assets/retro_ruby/models/item/ruby.json": '{"parent":"minecraft:item/generated","textures":{"layer0":"retro_ruby:item/ruby"}}',
    })
    addon_deps = (BASE, LIB)
    addon_project = make_project(root / "addon", ADDON, "RubyWorkshop", f"""
    public RubyWorkshop(IEventBus bus) {{
        var items = DeferredRegister.createItems("{ADDON}");
        var limit = mcmodup.source.{BASE}.RubyMod.stackSize() * mcmodup.source.{LIB}.RubyTools.multiplier();
        items.registerSimpleItem("ruby_lens", new Item.Properties().stacksTo(limit));
        items.register(bus);
    }}
""", dependencies=addon_deps, assertions=check_item(f"{ADDON}:ruby_lens", 16), resources={
        f"assets/{ADDON}/lang/en_us.json": '{"item.mcmodup_update_addon.ruby_lens":"Ruby Lens"}',
        f"assets/{ADDON}/models/item/ruby_lens.json": '{"parent":"minecraft:item/generated","textures":{"layer0":"retro_ruby:item/ruby"}}',
    })
    lang = base_project / "src/main/resources" / LANG
    rows = [
        recipe(base, zip_project(base_project), BASE, ids={BASE: MOD_VERSION},
               features={"Ruby item registration": "nativecase"}, tasks=["jar", "runGameTestServer"],
               import_assets={ASSET: "src/main/resources/" + ASSET}, asset_map={OLD_LANG: {"path": LANG, "sha256": digest(lang)}}),
        recipe(addon, zip_project(addon_project), ADDON, ids={ADDON: MOD_VERSION},
               features={"Ruby lens uses native base and library APIs": "nativecase"}, tasks=["jar", "runGameTestServer"],
               dependencies=tuple((dep, "[1,2)") for dep in addon_deps)),
    ]
    return write_manifest(root / "reviewed-source-ports.json", *rows)


def check_item(item: str, stack: int) -> str:
    return f"""        var key = ResourceLocation.parse("{item}");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(key), "Missing " + key);
        var stack = new ItemStack(BuiltInRegistries.ITEM.get(key));
        helper.assertTrue(stack.getMaxStackSize() == {stack}, "Wrong stack limit for " + key);
"""


def make_project(project: Path, ids: str, cls: str, body: str = "", *, dependencies=(), assertions="", resources=None) -> Path:
    package = f"mcmodup.source.{ids}"
    source_path = "src/main/java/" + package.replace(".", "/")
    gradle = _build_gradle(ids, package, MOD_VERSION) + f"""
tasks.named('jar') {{
    archiveFileName = '{ids}.jar'
    exclude '**/SourceChecks.class', 'data/{ids}/structure/mcmodup_test.nbt'
}}
"""
    if dependencies:
        gradle += """
def packJars = providers.gradleProperty('mcmodupDependencyDir').get()
dependencies { implementation fileTree(dir: packJars, include: ['*.jar']) }
"""
    files = {"settings.gradle": f"rootProject.name = '{ids}'\n", "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
             "src/main/resources/META-INF/neoforge.mods.toml": fixture_metadata(ids, dependencies),
             f"{source_path}/{cls}.java": f"""package {package};
import net.minecraft.world.item.Item;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
@Mod("{ids}")
public final class {cls} {{ {body} }}
"""}
    files.update({f"src/main/resources/{name}": data for name, data in (resources or {}).items()})
    if assertions:
        gradle += f"""
neoForge.runs.create('gameTestServer') {{
    type = 'gameTestServer'
    gameDirectory = project.file('run')
    systemProperty 'neoforge.enabledGameTestNamespaces', '{ids}'
    systemProperty 'mcmodup.gameReport', layout.buildDirectory.file('gametest.xml').get().asFile.absolutePath
}}
"""
        files[f"src/main/resources/data/{ids}/structure/mcmodup_test.nbt"] = empty_test_structure()
        files[f"{source_path}/SourceChecks.java"] = f"""package {package};
import java.io.File;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
@GameTestHolder("{ids}")
@PrefixGameTestTemplate(false)
public final class SourceChecks {{
    static {{
        try {{
            GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(new File(System.getProperty("mcmodup.gameReport"))));
        }} catch (ParserConfigurationException e) {{ throw new IllegalStateException(e); }}
    }}
    @GameTest(template = "mcmodup_test")
    public static void nativeCase(GameTestHelper helper) {{
{assertions}
        helper.assertTrue(!ModList.get().isLoaded("legacyconnector"), "Connector should not be loaded");
        helper.succeed();
    }}
}}
"""
    files["build.gradle"] = gradle
    write_workspace(project, files)
    return project


def zip_project(project: Path) -> Path:
    archive = project.with_suffix(".zip")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(project.rglob("*")):
            if path.is_file():
                zipped.write(path, path.relative_to(project).as_posix())
    return archive


def test(root: Path) -> None:
    manifest = prepare(root)
    old, target = root / "Legacy source-build example", root / "NeoForge 1.21.1 example"
    before = {p: digest(p) for folder in (old, target) for p in folder.rglob("*") if p.is_file()}
    state = inventory(old, target, BuildOptions(manifest))
    selected = ["mods/Retro Ruby.jar"]
    update = plan(state, selected)
    assert update.report["ready"], update.problems
    assert update.report["build_order"] == selected + ["mods/Ruby Workshop.jar"]
    staged = stage(update, root / "stage")
    report = json.loads((staged / REPORT_NAME).read_text(encoding="utf-8"))
    [base, addon] = [report["native_builds"][f"{name}.jar"] for name in (BASE, ADDON)]
    assert base["legacy_assets"]["retained"] == 1 and base["legacy_assets"]["adapted"] == 1
    assert base["legacy_assets"]["unmapped"] == 0 and addon["gametests"]["passed"] == 1
    required = addon["dependencies_sha256"]
    assert required[f"{BASE}.jar"] == digest(staged / "mods" / f"{BASE}.jar")
    assert required["Ruby-Tools.jar"] == digest(target / "lib/Ruby-Tools.jar")
    for jar in (staged / "mods").glob("*.jar"):
        with zipfile.ZipFile(jar) as archive:
            assert not any("SourceChecks.class" in name for name in archive.namelist())
    server = make_project(root / "server", HOST, "ServerProbe", assertions=check_item(f"{ADDON}:ruby_lens", 16))
    shutil.copytree(staged / "mods", server / "run/mods")
    run = run_gradle(server, "runGameTestServer")
    assert "All 1 required tests passed" in run, run[-12000:]
    assert {p: digest(p) for p in before} == before
    print("Built the exact toy mod and add-on from source, retained both legacy assets, and played the staged native stack without Connector.")


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--demo", type=Path, help="create a read-only workbench example; do not launch Gradle")
    args = cli.parse_args()
    if args.demo:
        args.demo.mkdir()
        print(prepare(args.demo, demo=True))
    else:
        with tempfile.TemporaryDirectory(prefix="mcmodup-source-pack-test-") as directory:
            test(Path(directory))

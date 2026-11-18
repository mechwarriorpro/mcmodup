"""Prepare the native Tinkerer pair with optional CC: Tweaked support.

Apply only the ComputerCraft branch delta to alpha.7 and only the golem/machine
API delta to the tested Reload base. Do not replace either with its older
branch tip, execute the legacy JARs, or redistribute upstream source or binaries.
"""

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.error import URLError
from urllib.request import build_opener
from zipfile import ZipFile, ZipInfo

from mcmodup.inputs import InputError
from mcmodup.source_ports import _copy_limited, _NoRedirect, digest
from prepare_tinkerer_preview import EXAMPLES, TC_COMMIT, TC_SOURCE_HASH, TT_COMMIT, TT_SOURCE_HASH, change, prepare_pair

CC_VERSION = "1.116.1"
CC_JAR = f"cc-tweaked-1.21.1-forge-{CC_VERSION}.jar"  # 'forge' is the NeoForge release.
CC_SHA512 = "6f4e0329d8d9d54375241d0fab5219a6963e3ca678cb8d9fff8e7a39b1a825a5f62f3f5518e9a53f86de7f601797af6efdfdff74fc8fb6fc369e930eb639ec25"
RELOAD_BACKPORT_VERSION = "0.1.0-alpha.24-mcmodup.cc.1"
TINKERER_BACKPORT_VERSION = "0.1.0-alpha.7-mcmodup.cc.1"
CC_CASES = {
    "Optional ComputerCraft registration": "computercraftisloadedandtheglueregistered",
    "Add-on peripheral capabilities": "addonperipheralsarefoundattheirblocks",
    "Container and transport peripheral capabilities": "genericperipheralsarefoundatthaumcraftblocks",
    "Thaumcraft machine peripheral capabilities": "thaumcraftmachinesarefoundattheirblocks",
    "Lua drives the Dynamism Tablet": "luadrivesthedynamismtablet",
    "Lua drives both attractors": "luadrivestheattractors",
    "Lua reads the Aspectalyzer": "luareadstheaspectalyzer",
    "Lua handles an unbound Golem Connector": "luaasksthegolemconnectorwithoutagolem",
    "Lua steers a bound golem": "luareadsandsteersagolem",
    "Lua reads aspect containers and empty jar labels": "luareadsaspectcontainers",
    "Lua reads essentia transports": "luareadsessentiatransports",
    "Lua reads or tunes Thaumcraft machines": "luareadsthaumcraftmachines",
    "Fume Dissipator crafts onto turtles": "fumedissipatorisaturtleupgrade",
    "Lua turtle dissipates gas": "luaturtledissipatesgas",
}
TC_CASES = {
    "Golem API reads a real golem": "golemapiviewreadstherealgolem",
    "Golem API changes persist and sync": "golemapiwriteschangethegolemsyncandsurvivereload",
    "Golem API rejects invalid writes": "golemapirefusesinvalidinputandotherthreads",
    "Arcane Ear tuning persists and syncs": "machineapiarcaneearnoteistunedsyncedandsaved",
    "Brain in a Jar and Deconstruction Table API": "machineapibrainjaranddeconstructorreadtheirblockentities",
    "Arcane Bore peripheral statistics": "machineapiarcaneboreanswerstheoriginalperipheralquestions",
}


@dataclass(frozen=True)
class Pin:
    project: str
    commit: str
    sha256: str

    @property
    def root(self) -> str:
        return f"{self.project}-{self.commit}"

    def get(self, directory: Path, *, fetch: bool) -> Path:
        path = directory / (self.root + ".zip")
        limit = 100 * 1024 * 1024 if self.project == "Thaumcraft-1.21.1" else 8 * 1024 * 1024
        if fetch:
            url = f"https://codeload.github.com/acak1221/{self.project}/zip/{self.commit}"
            try:
                with build_opener(_NoRedirect()).open(url, timeout=60) as response:
                    _copy_limited(response, path, limit)
            except (URLError, TimeoutError) as exc:
                raise InputError(f"Could not fetch {self.root}: {exc}") from exc
        if not path.is_file() or path.stat().st_size > limit or digest(path) != self.sha256:
            raise InputError(f"Need the exact pinned source archive: {path}")
        return path


TC, TC_ANCESTOR, TC_API = (Pin("Thaumcraft-1.21.1", version, checksum) for version, checksum in (
    (TC_COMMIT, TC_SOURCE_HASH),
    ("4c38e3f3bb44c3455718d8f3e04073921ec2a932", "d30fea5feca0793e0e9258e2456b135a40c0041b2b0e27c7f49a9c218f41fadf"),
    ("316897d63fde06f10313ad6162426a906b636d77", "8b6fb348049d77ba86a9419a8acbe9c99652fca195e76e82eb46f8c10cec62d4"),
))
TT, TT_ANCESTOR, TT_CC = (Pin("Thaumic-Tinkerer-1.21.1", version, checksum) for version, checksum in (
    (TT_COMMIT, TT_SOURCE_HASH),
    ("35e40b9b7932e09dd2fba7182eb6e6b7269eba11", "76a9e7c58e57ce9567fbe4d80f02e3d8a86d5a61160a3c59cc7de762d957f456"),
    ("35c2fad848dd095e65263a6a163c0c964d021c00", "87ce3bd5d131f7e598432fe380d4f2484ed2b628cd61234129cbc4124db43180"),
))


def merge_file(name: str, ours: bytes | None, ancestor: bytes | None, theirs: bytes | None, tmp: Path) -> bytes | None:
    if theirs == ancestor or ours == theirs:
        return ours
    if ours == ancestor:
        return theirs
    if None in (ours, ancestor, theirs):
        raise InputError(f"Unexpected add/delete conflict: {name}")
    if name.startswith("lang-extra/tile_blocks."):
        # Separate translation keys changed, but on adjacent lines. Keep the release's Interface energy text.
        now, old, new = map(json.loads, (ours, ancestor, theirs))
        for key in old.keys() | new.keys():
            if old.get(key) == new.get(key):
                continue
            if now.get(key) not in (old.get(key), new.get(key)):
                raise InputError(f"Conflicting language key {key!r} in {name}")
            if key in new:
                now[key] = new[key]
            else:
                now.pop(key, None)
        return (json.dumps(now, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()
    if name == "fabric/src/main/resources/fabric.mod.json":
        return change(ours.decode(), '"team_reborn_energy": "*"',
                      '"team_reborn_energy": "*",\n    "computercraft": "*"').encode()
    if name.endswith("/TinkererApiOnlyTest.java"):
        # Alpha.7 added the Fabric Energy API check. Keep it and extend just the CC: Tweaked API allowlist.
        text = change(ours.decode(), '"snownee.jade.api.");', '"snownee.jade.api.", "dan200.computercraft.api.");')
        text = change(text, 'List.of(ENERGY_API + "api.")', 'List.of(ENERGY_API + "api.", "dan200.computercraft.api.")')
        return change(text, '"org.objectweb.", "org.spongepowered.", "com.llamalad7."))',
                      '"org.objectweb.", "org.spongepowered.", "com.llamalad7.", "dan200.computercraft.api."))').encode()
    paths = [tmp / basename for basename in ("ours", "ancestor", "theirs")]
    for path, data in zip(paths, (ours, ancestor, theirs)):
        path.write_bytes(data)
    result = subprocess.run(["git", "merge-file", "--stdout", *(str(p) for p in paths)], capture_output=True, check=False)
    if result.returncode:
        raise InputError(f"Backport does not merge cleanly: {name}")
    return result.stdout


def read_members(archive: ZipFile, root: str, selected) -> dict[str, bytes]:
    prefix = root + "/"
    return {name[len(prefix):]: archive.read(name) for name in archive.namelist()
            if name.startswith(prefix) and not name.endswith("/") and selected(name[len(prefix):])}


def merge_archive(pins: tuple[Pin, Pin, Pin], sources: Path, fetch: bool, output: Path, selected, finish=None) -> None:
    base, ancestor, feature = pins
    base_path, ancestor_path, feature_path = (pin.get(sources, fetch=fetch) for pin in pins)
    with ZipFile(base_path) as current, ZipFile(ancestor_path) as old, ZipFile(feature_path) as new:
        before, after = read_members(old, ancestor.root, selected), read_members(new, feature.root, selected)
        changed = {name for name in before.keys() | after.keys() if before.get(name) != after.get(name)}
        live = read_members(current, base.root, changed.__contains__)
        updates = {name: merge_file(name, live.get(name), before.get(name), after.get(name), output.parent)
                   for name in sorted(changed)}
        if finish:
            finish(updates, current, base.root)
        with ZipFile(output, "x") as archive:
            for info in current.infolist():
                name = info.filename.removeprefix(base.root + "/")
                if name not in updates:
                    archive.writestr(info, current.read(info))
                elif (data := updates.pop(name)) is not None:
                    archive.writestr(info, data)
            for name, data in sorted(updates.items()):
                if data is not None:
                    archive.writestr(ZipInfo(f"{base.root}/{name}"), data)


def finish_reload(updates: dict, current: ZipFile, root: str) -> None:
    # Preserve the difference between an empty labelled jar and one with residual suction only.
    tile = "common/src/main/java/thaumcraft/common/tiles/TileJar.java"
    if tile in updates:
        raise InputError("The upstream API delta also changed the jar; review the label backport")
    text = current.read(f"{root}/{tile}").decode()
    text = change(text, "import thaumcraft.api.aspects.IAspectSource;",
                  "import thaumcraft.api.aspects.IAspectSource;\nimport thaumcraft.api.aspects.IJarLabel;")
    text = change(text, "implements IAspectSource, EssentiaStorageEndpoint", "implements IAspectSource, EssentiaStorageEndpoint, IJarLabel")
    updates[tile] = change(text, "    public Aspect getFilter()", "    @Override public Aspect getFilter()").encode()
    updates["common/src/main/java/thaumcraft/api/aspects/IJarLabel.java"] = (
        EXAMPLES / "port-patches/thaumcraft/IJarLabel.java").read_bytes()
    gradle = current.read(f"{root}/gradle.properties").decode()
    updates["gradle.properties"] = change(gradle, "mod_version=0.1.0-alpha.24\n", f"mod_version={RELOAD_BACKPORT_VERSION}\n").encode()


def finish_tinkerer(updates: dict, current: ZipFile, root: str) -> None:
    def edit(name, old, new):
        text = updates[name] if name in updates else current.read(f"{root}/{name}")
        updates[name] = change(text.decode(), old, new).encode()

    edit("common/src/gameTest/java/thaumic/tinkerer/testing/ParityRuntimeChecks.java",
         "int DOCUMENTED_DIFFERENCES = 12;", "int DOCUMENTED_DIFFERENCES = 13;")
    edit("common/src/test/java/thaumic/tinkerer/parity/ParityComparatorTest.java",
         "assertEquals(12, result.allowed.size());", "assertEquals(13, result.allowed.size());")
    # Original IAspectContainerImplementation reports the label first, independently of storage.
    table = "compat/src/main/java/thaumic/tinkerer/compat/computer/AspectContainerTable.java"
    edit(table, "import thaumcraft.api.aspects.IAspectContainer;", "import thaumcraft.api.aspects.IAspectContainer;\nimport thaumcraft.api.aspects.IJarLabel;")
    edit(table, """ * <p>Not ported: 1.7.10 answered {@code getAspects()} of a labelled jar ({@code TileJarFillable} with an {@code aspectFilter})
 * with the label's aspect alone, even when the jar was empty. {@code thaumcraft.api} has no way to read a jar's label, so a
 * labelled empty jar answers an empty table (docs/deviations/ComputerCraft.md).</p>""", """ * <p>As in 1.7.10, a labelled jar reports its label alone, even when it is empty. This doesn't change getAspectCount.</p>""")
    edit(table, "        double i = 1;", """        if (container instanceof IJarLabel labelled && labelled.getFilter() != null) {
            returnStuff.put(1D, labelled.getFilter().getTag());
            return new Object[] { returnStuff };
        }
        double i = 1;""")
    cases = "common/src/gameTest/java/thaumic/tinkerer/testing/ComputerCraftRuntimeChecks.java"
    edit(cases, """     * the jar's aspects, as in 1.7.10), and under an empty jar labelled Aqua (1.7.10 answered the label; the API cannot read it, so
     * the port answers an empty table, docs/deviations/ComputerCraft.md).""", """     * the jar's aspects), and under an empty jar labelled Aqua, which gives its label but zero stored essentia as in 1.7.10.""")
    edit(cases, '            expectEmptyTable(test, l, "getAspects");', '''            JsonArray aspects = values(l, "getAspects");
            test.assertTrue(aspects.size() == 1 && strings(aspects.get(0)).equals(List.of("aqua")),
                    "getAspects() returns the Aqua label of an empty jar: " + aspects);''')
    for doc in ("docs/api-requests/ComputerCraft.md", "docs/deviations/ComputerCraft.md"):
        heading, _, body = updates[doc].decode().split("\n", 2)
        updates[doc] = (heading + "\n\n> In this backport, `IJarLabel` implements the jar-label request below. "
                        "`getAspects()` returns the label even for an empty jar.\n" + body).encode()
    # Use the user's staged CC JAR if present; otherwise use the test-only dependency. Never load both in Loom.
    edit("neoforge/build.gradle", ' modGameTestRuntimeOnly "cc.tweaked:cc-tweaked-1.21.1-forge:$rootProject.cc_tweaked_version"', ''' def installedComputers = fileTree(dir: providers.gradleProperty('mcmodupDependencyDir').get(), include: ['*.jar']).files.findAll { jar ->
  new java.util.zip.ZipFile(jar).withCloseable { it.getEntry('dan200/computercraft/shared/ModRegistry.class') != null }
 }
 if (installedComputers.size() > 1) throw new GradleException('Multiple CC: Tweaked JARs in the build inputs')
 if (installedComputers.isEmpty()) modGameTestRuntimeOnly "cc.tweaked:cc-tweaked-1.21.1-forge:$rootProject.cc_tweaked_version"''')
    edit("gradle.properties", "# Thaumcraft 4 Reload, for both loaders", f"cc_tweaked_version={CC_VERSION}\n\n# Thaumcraft 4 Reload, for both loaders")
    edit("gradle.properties", "mod_version=0.1.0-alpha.7\n", f"mod_version={TINKERER_BACKPORT_VERSION}\n")
    metadata = "neoforge/src/main/resources/META-INF/neoforge.mods.toml"
    edit(metadata, 'modId="computercraft"\ntype="optional"\nversionRange="*"',
         f'modId="computercraft"\ntype="optional"\nversionRange="[{CC_VERSION}]"')
    edit(metadata, 'modId="thaumcraft"\ntype="required"\nversionRange="*"',
         f'modId="thaumcraft"\ntype="required"\nversionRange="[{RELOAD_BACKPORT_VERSION}]"')


def configure_recipes(source: Path, output: Path, base: dict, add_on: dict) -> None:
    shutil.copyfile(source, output / source.name)
    del base["source_url"]
    base.update(source_zip=source.name, source_sha256=digest(source), minimum_tests=543 + len(TC_CASES),
                ids={"thaumcraft": RELOAD_BACKPORT_VERSION},
                artifact=f"neoforge/build/libs/Thaumcraft-neoforge-{RELOAD_BACKPORT_VERSION}.jar")
    base["features"].update(TC_CASES)
    base["limitations"].append(f"Includes the golem and machine API delta from Reload {TC_API.commit[:7]} and read-only jar labels.")
    add_on.update(ids={"thaumictinkerer": TINKERER_BACKPORT_VERSION},
                  artifact=f"neoforge/build/libs/ThaumicTinkerer-neoforge-{TINKERER_BACKPORT_VERSION}.jar")
    for dep in add_on["dependencies"]:
        if dep["id"] == "thaumcraft":
            dep["range"] = f"[{RELOAD_BACKPORT_VERSION}]"
    add_on["dependencies"].append({"id": "computercraft", "range": f"[{CC_VERSION}]", "type": "optional"})
    add_on["minimum_tests"] += len(CC_CASES)
    add_on["features"].update({key: f"computercraftgametests.{value}" for key, value in CC_CASES.items()})
    add_on["limitations"][2] = (f"CC: Tweaked {CC_VERSION} is optional. OpenComputers, IC2 and other old integrations are not restored; "
                               "see ComputerCraft.md in the source ZIP for changes to the Lua API and remaining gaps.")
    add_on["limitations"].append(f"Backports Tinkerer's ComputerCraft delta from {TT_CC.commit[:7]} without dropping the alpha.7 changes.")


def prepare(output: Path, inputs: Path | None = None) -> Path:
    with tempfile.TemporaryDirectory(prefix="mcmodup-computercraft-") as temp:
        tmp = Path(temp)
        sources = inputs if inputs is not None else tmp
        tc, tt = tmp / "reload-computercraft-source.zip", tmp / "tinkerer-computercraft-source.zip"
        merge_archive((TC, TC_ANCESTOR, TC_API), sources, inputs is None, tc,
                      lambda n: n.startswith(("common/src/", "neoforge/src/gameTest/", "fabric/src/gameTest/", "docs/api/reference/"))
                      or n == "scripts/porting/required-gametests.json", finish_reload)
        merge_archive((TT, TT_ANCESTOR, TT_CC), sources, inputs is None, tt,
                      lambda n: n.startswith(("common/", "compat/", "neoforge/", "fabric/", "lang-extra/"))
                      or n in {"NOTICE.md", "docs/deviations/ComputerCraft.md", "docs/api-requests/ComputerCraft.md"},
                      finish_tinkerer)
        return prepare_pair(tt, output, configure=lambda directory, base, addon: configure_recipes(tc, directory, base, addon))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    parser.add_argument("--inputs", type=Path, help="use an offline directory of the six pinned <project>-<commit>.zip files")
    options = parser.parse_args()
    try:
        print(prepare(options.output_directory, options.inputs))
    except (InputError, OSError) as exc:
        parser.exit(1, f"{exc}\n")

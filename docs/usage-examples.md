# Porting examples: Minechem, Thaumcraft, Atomic Science, and ICBM

**Current capability:** `convert-jar` builds partial NeoForge 1.21.1 JARs for Minechem and Thaumcraft. Atomic Science has legacy input and dependency examples. Source-build mode covers a [broader Thaumcraft 4 preview](source-port-builds.md#thaumcraft-4-reload-preview) and one [ICBM pressure plate](source-port-builds.md#voltz-glass-pressure-plate-executable-preview). None is a verified full port with all assets and add-ons.

Run the examples from the mcmodup checkout with Python 3.11+. Put your local inputs under `.mcmodup/` (ignored by Git). You can substitute an installed pack folder for either pack archive. A `.jar` or `.zip` pack must contain the actual mod files under `mods/` or `coremods/` (an enclosing instance folder is fine), or at the archive root. If a launcher export has only a download manifest, point to the installed instance instead.

Updating a full pack? Use the [native updater](native-pack-updates.md) to select mods and resolve local native dependencies. The [Legacy Connector evaluation](legacy-connector.md) is separate gameplay and translation evidence, not a requirement for native outputs.

## Build the partial Minechem and Thaumcraft JARs

With **JDK 21** on `PATH` or in `JAVA_HOME`, and Gradle network access. These examples use the released [Minechem 5.0.5 FINAL JAR](https://github.com/iopleke/Minechem/releases/tag/1.6.4-5.0.5-FINAL) and [Thaumcraft 4.2.3.5 JAR](https://www.curseforge.com/minecraft/mc-mods/thaumcraft/files/2227552).

```sh
python3 -m mcmodup convert-jar .mcmodup/Minechem-1.6.4-5.0.5.FINAL.jar \
  --pack .mcmodup/minechem-pack.zip --profile minechem-chemistry --allow-partial \
  -o ports/minechem-chemistry-1.21.1.jar

python3 -m mcmodup convert-jar .mcmodup/Thaumcraft-1.7.10-4.2.3.5.jar \
  --pack .mcmodup/thaumcraft-pack.jar --profile thaumcraft-aspects --allow-partial \
  -o ports/thaumcraft-aspects-1.21.1.jar
```

- **Minechem Chemistry:** Recovers 113 elements and 147 molecule formulas from the verified release. The new Chemical Synthesizer and Chemical Decomposer process those formulas in both directions. The latter also accepts water buckets, iron ingots, and gold ingots; the synthesizer can make the two ingots from their elements. A Microscope and Chemist's Journal let you inspect, record, and select recovered recipes. These are a subset of the old recipes, plus new machine crafting recipes. Decomposing a water bucket consumes it; inspecting it doesn't.
- **Thaumcraft Aspects:** Imports the 48 built-in aspect tags, colors, and component relationships and registers icon items. Combine `aspect_aer` and `aspect_ignis` to craft `aspect_lux`. There is no survival source for primal aspects. The recipes do not port Thaumcraft research, essentia, or magic, and they do not support old add-ons.
- Both JARs have new IDs and a **Partial Port** creative tab. Test them in a **new** NeoForge 1.21.1 world. Minechem's powered recipes and Thaumcraft's crafting recipe status are recorded in `data/<namespace>/mcmodup/catalog.json`. For Thaumcraft, duplicate ingredient combinations are skipped instead of silently overriding each other. No original JVM code or legacy dependency JAR is included.
- Only use sources and assets you are allowed to modify. A generated Thaumcraft adaptation is for private use unless the author has given permission to distribute it. `convert-jar` does not upload an artifact or publish a release.

### Try the Minechem machines

1. Craft a Chemical Decomposer. The recipe is `III / IPI / IRI`: `I` is iron ingot, `P` piston, `R` redstone dust. For the Synthesizer, use `IRI / IPI / IDI`, with `D` a diamond. The machine recipes use vanilla ingredients because the original Atomic Manipulator is not ported.
2. For the exact 5.0.5 FINAL build, connect a NeoForge FE source and insert a water bucket into the Decomposer's input. It starts automatically when charged, consumes the input at intake and releases 16 water vials one per tick. The top exposes one input and the other faces expose nine outputs. The saved output queue continues when a running machine runs out of charge, but a jam needs a free slot and charge to resume. There is no **Process** button or redstone-dust fuel slot on this Decomposer. Decompose one water vial to get two hydrogen vials and one oxygen vial.
3. In the Synthesizer, use **<** / **>** to select Water (hold Shift to skip ten; hover over the result name for inputs). Add two hydrogen and one oxygen vial, supply FE or a redstone dust, and click **Process**. For hopper/cable automation, switch **Manual** to **Auto**. Processing retries when ingredients, power, or output space become available. The machine saves its inventory, stored FE, selection, processing mode, and an in-flight job in **new** worlds.

### Research a recipe and use the journal

1. Craft a Chemist's Journal from a book and a glass block. Craft a Microscope with the pattern `_LI / _PI / III`: `_` is empty, `I` iron ingot, `L` a spyglass, and `P` a glass pane.
2. Open the Microscope and put one sample (such as a water vial) in the left slot and the journal in the right slot. Switch between **Synthesis** and **Decomposition** to inspect the ingredients or products. Inspection costs no power, doesn't consume either item, and records each supported sample once. The preview icons aren't items you can take.
3. Retrieve the book and right-click in the air. Use **<** / **>** to select a discovery. Insert the journal in the slot at the upper right of an idle Synthesizer to load that recipe. It still needs the actual ingredients and FE. A discovery with only a decomposition recipe won't change the Synthesizer's selection.

The [NeoForge GameTests](https://github.com/mechwarriorpro/mcmodup/actions/runs/44328458928) exercised both synthetic inputs and the JAR built from Minechem 5.0.5 FINAL: discoveries were deduplicated, both microscope slots survived save/reload, and a saved journal selected a synthesis job that used the normal ingredients and FE. These are dedicated server and menu checks, not a comparison of the original screens or a full survival playthrough.

These are adapted machines: new menus and models, a spyglass instead of the original lens chain, and manual or repeating processing and a redstone fallback (+500 FE) in the Synthesizer. The [exact FINAL Forge run](https://github.com/mechwarriorpro/mcmodup/actions/runs/44366937376/job/135023092554) measured the iron-ingot decomposition queue, one-unit output ticks, reception limit and jam recovery. The [NeoForge release-derived JAR checks](https://github.com/mechwarriorpro/mcmodup/actions/runs/44366937370/job/135023710792) repeated the iron intake/output trace, buffer save/load and jam recovery with the checksum-pinned FINAL build. The later [.394 build](#minechem-505394-iron-decomposer-preview) has a different cost and uses a separate mod ID.

The original journal cloning and GUI art are not ported. FE is not a conversion of Universal Electricity voltage or power units. Most material, ore dictionary/tag, chance, and fluid recipes are not implemented, nor are integrations, radiation, reactors, the rest of Minechem's machines, and legacy-world migration. Only recovered formulas and the listed starter materials can be processed.

## Minechem 5.0.5.394 iron decomposer preview

Build from the [original 1.7.10 release](https://www.curseforge.com/minecraft/mc-mods/minechem/files/2230655). This profile requires the exact SHA-256, not just the same filename or mod version. The JAR contains both catalog classes, so `--pack` can point to the same input:

```sh
jar=.mcmodup/Minechem-1.7.10-5.0.5.394.jar
python3 -m mcmodup convert-jar "$jar" --pack "$jar" \
  --profile minechem-chemistry-394 --allow-partial -o ports/minechem-chemistry-394.jar
```

This is `minechem_chemistry_394`, separate from FINAL's `minechem_chemistry`. Its adapted decomposer uses the same starter crafting recipe above, takes only iron ingots, and exposes one input at the top and 18 outputs on the other sides. It deducts **1,000 FE at intake**, compacts existing outputs, then emits 16 iron tubes one per tick. The queue survives a save and load in a **new** world. Unlike FINAL, .394 can resume a paid, jammed queue as soon as a matching output stack has room, even at zero charge. The [exact .394 Forge check](https://github.com/mechwarriorpro/mcmodup/actions/runs/44371974329/job/135040909716) measured those rules and the original 100 RF receive limit. The new machine accepts up to 100 FE per receive and needs a compatible NeoForge FE source; it has no redstone-dust fallback. CoFH API linkage and original RF configurations aren't supported.

The 113 element and 173 molecule entries are recovered from the .394 binary, including their explicit metadata IDs. Other than the iron output they are catalog items, not added syntheses or decompositions. There are no .394 synthesizer, research, fluid or other machine ports here.

The [paired NeoForge 21.1.267 GameTests](https://github.com/mechwarriorpro/mcmodup/actions/runs/44371974288/job/135040896089) ran both release-derived machines in one world and checked distinct items and registrations, intake timing, compaction, saved queues, and jam recovery. Unchanged CB Multipart `3.5.0.155` and CodeChickenLib `4.6.1.526` also passed the native stone-cover check in the same server. For local testing, place the **generated** JARs, not the originals, in a fresh compatible instance. These partial JARs are not providers the updater can select as drop-in replacements.

## Thaumcraft 4.2.3.5: Forge 1.7.10 → NeoForge 1.21.1

```sh
mkdir -p .mcmodup/reports
python3 -m mcmodup scan-pack .mcmodup/thaumcraft-pack.jar \
  --mod-jar .mcmodup/Thaumcraft-1.7.10-4.2.3.5.jar \
  --source-version 1.7.10 --json > .mcmodup/reports/thaumcraft.json
```

In the report, start with `declared_dependencies`, `binary_dependencies`, and `api_checks`. Check whether the pack provides the Baubles classes your copy references. If you need to inspect one of those dependency classes and its license allows it, pass its **exact reported name** to `decompile-deps`:

```sh
python3 -m mcmodup decompile-deps .mcmodup/thaumcraft-pack.jar \
  --mod-jar .mcmodup/Thaumcraft-1.7.10-4.2.3.5.jar \
  --source-version 1.7.10 --vineflower /path/to/vineflower-1.12.0.jar \
  --class 'CLASS_FROM_BINARY_DEPENDENCIES' -o .mcmodup/references/thaumcraft
```

That command decompiles the **dependency**, not Thaumcraft itself. If you have a permitted private copy of the Thaumcraft Java source, you can run:

```sh
python3 -m mcmodup port .mcmodup/thaumcraft-source -o ports/thaumcraft-1.21.1 \
  --source-version 1.7.10 --mod-id thaumcraft --name Thaumcraft
```

Start the rewrite with registry entries and aspect/essentia data. Treat research, wands, infusion, packets, and world generation as separate work. Do not assume modern accessory APIs are drop-in replacements for Baubles.

The [Thaumcraft license](https://www.curseforge.com/minecraft/mc-mods/thaumcraft/license) permits private decompilation and modification but requires advance written permission for public distribution of modified versions. Do not push Thaumcraft source, assets, or a modified JAR to a public repository or public build artifact without permission.

## Minechem 5.0.5 FINAL: Forge 1.6.4 → NeoForge 1.21.1

```sh
mkdir -p .mcmodup/reports
python3 -m mcmodup scan-pack .mcmodup/minechem-pack.zip \
  --mod-jar .mcmodup/Minechem-1.6.4-5.0.5.FINAL.jar \
  --source-version 1.6.4 --json > .mcmodup/reports/minechem.json
```

Unlike Thaumcraft, this release has a [tagged source tree](https://github.com/iopleke/Minechem/tree/1.6.4-5.0.5-FINAL). Download the matching source ZIP, rather than decompiling the main mod:

```sh
curl -fL https://codeload.github.com/iopleke/Minechem/zip/refs/tags/1.6.4-5.0.5-FINAL \
  -o .mcmodup/minechem-1.6.4-source.zip
python3 -m mcmodup port .mcmodup/minechem-1.6.4-source.zip -o ports/minechem-1.21.1 \
  --source-version 1.6.4 --mod-id minechem --name Minechem \
  --mod-version 5.0.5-port --license CC-BY-SA-3.0
```

Use the tested Decomposer and Synthesizer as references when porting another machine end to end. It needs modern block entity, menu, energy, recipe, and save implementations. Replace old custom packets with NeoForge payloads where needed. Check dependencies against your report rather than copying any 1.6.4 API JARs into the new project. Follow the source tag's attribution and share-alike terms, and check bundled dependencies separately.

## Atomic Science 1.2.0 and ICBM 1.4.1.344: Forge 1.6.4 input examples

The original [Voltz 3.0.0c server ZIP in Technic's archive](https://github.com/TechnicPack/Technic/blob/8c4e20f9225bcd6612fca4cb29c53366317d3e13/servers/voltz/Voltz_Server_v3.0.0c.zip) contains both JARs. The archive's SHA-256 is `2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e`. Use a copy you are allowed to inspect. Do not run the legacy server, commit its files, or assume download availability establishes permission to redistribute a derivative. These binaries have no conversion profile yet.

| Input inside the pack | FML declaration | Resolved dependency evidence |
| --- | --- | --- |
| `mods/atomicscience.jar` | `AtomicScience` 1.2.0 | Requires Calclavia Core; references 74 classes in the pack's `calclavia-core-development-1.2.0.6-main.jar`. |
| `mods/icbm-1.4.1.344.jar` | `ICBM`, `ICBM\|Explosion`, `ICBM\|Sentry` 1.4.1 | Same Calclavia Core (73 class references); `EulerAngle` is in `universal-electricity-3.1.0.74-core.jar`. Atomic Science is an optional ordering dependency and is present. |

These are the exact Voltz binaries, not interchangeable builds of the same FML version. [OpenEye identifies the Atomic Science JAR by SHA-256](https://openeye.openmods.info/file/sha256:80cf5bd9d485377ecde3822154a5db47804fab3e8f78c388cfcf529239655938) as also known by the name `atomic-science-1.2.0.70-core.jar`. The `.344` in ICBM is likewise a build number from the packaged filename, not the FML version string. Neither JAR specifies Minecraft 1.6.4 in the inspected mod metadata; use the pack's profile.

The ICBM Classic tag `mc/1.6.4` is a later **1.4.2** snapshot. An [earlier 1.4.1 revision](https://github.com/BuiltBrokenModding/ICBM-Classic/tree/1891f8182daca5a1dc557505d2d5573648cb8dff) has the same 304 top-level Java class paths and 225 byte-identical resource paths as Voltz's ICBM JAR, but its `build.properties` requests Calclavia Core `1.1.+` instead of `1.2.+`. This is a reference for investigation, not a verified source match or a NeoForge port.

```sh
pack=.mcmodup/Voltz_Server_v3.0.0c.zip
mkdir -p .mcmodup/reports
python3 -m mcmodup scan-pack "$pack" --mod mods/atomicscience.jar \
  --source-version 1.6.4 --json > .mcmodup/reports/atomicscience.json
python3 -m mcmodup scan-pack "$pack" --mod mods/icbm-1.4.1.344.jar \
  --source-version 1.6.4 --json > .mcmodup/reports/icbm.json
```

If you already have the individual mod JAR, replace `--mod mods/<name>.jar` with `--mod-jar /path/to/<name>.jar`, keeping your pack as the first argument. To inspect the dependencies (where permitted):

```sh
python3 -m mcmodup decompile-deps "$pack" --mod mods/atomicscience.jar \
  --source-version 1.6.4 --vineflower /path/to/vineflower-1.12.0.jar \
  --class calclavia.lib.prefab.tile.TileElectricalInventory -o .mcmodup/references/atomicscience
python3 -m mcmodup decompile-deps "$pack" --mod mods/icbm-1.4.1.344.jar \
  --source-version 1.6.4 --vineflower /path/to/vineflower-1.12.0.jar \
  --class universalelectricity.api.vector.EulerAngle -o .mcmodup/references/icbm
```

Provider presence is not a check for signature or behavioral compatibility. For exact legacy or target signature checks, supply the corresponding compiled loader/game/dependency APIs with `--legacy-api` or `--target-api`; pass the matching MCP/SRG mappings when names are obfuscated. The outputs above are analysis, not NeoForge gameplay or new JARs. Calclavia Core/UE power, ticking and networking need separate replacements before the game features can be ported.

## Build a manual source port

After writing and reviewing the new Java and data files, use **JDK 21** and run the wrapper inside the selected workspace. For example:

```sh
cd ports/minechem-1.21.1
./gradlew build
```

On Windows, use `py -3` for the CLI and `gradlew.bat build` for Gradle. The build writes `build/libs/*.jar`. Test on a fresh client world and a dedicated NeoForge 1.21.1 server with the target dependencies. A scaffold can compile before it implements gameplay: neither a successful build nor a signature match proves you have a completed port. The tool does not migrate legacy worlds.

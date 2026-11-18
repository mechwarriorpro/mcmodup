# Porting examples: Thaumcraft and Minechem

**Current capability:** `convert-jar` builds new NeoForge JARs with recovered items and adapted crafting recipes. It is not a complete rewrite of either mod. The machines, research, and other gameplay still require separate ports.

Run the examples from the mcmodup checkout with Python 3.11+. Put your local inputs under `.mcmodup/` (ignored by Git). You can substitute an installed pack folder for either pack archive. A `.jar` or `.zip` pack must contain the actual mod files under `mods/` or `coremods/` (an enclosing instance folder is fine), or at the archive root. If a launcher export has only a download manifest, point to the installed instance instead.

## Automatically build the partial catalog JARs

With **JDK 21** on `PATH` or in `JAVA_HOME`, and Gradle network access. These examples use the released [Minechem 5.0.5 FINAL JAR](https://github.com/iopleke/Minechem/releases/tag/1.6.4-5.0.5-FINAL) and [Thaumcraft 4.2.3.5 JAR](https://www.curseforge.com/minecraft/mc-mods/thaumcraft/files/2227552).

```sh
python3 -m mcmodup convert-jar .mcmodup/Minechem-1.6.4-5.0.5.FINAL.jar \
  --pack .mcmodup/minechem-pack.zip --profile minechem-chemistry --allow-partial \
  -o ports/minechem-chemistry-1.21.1.jar

python3 -m mcmodup convert-jar .mcmodup/Thaumcraft-1.7.10-4.2.3.5.jar \
  --pack .mcmodup/thaumcraft-pack.jar --profile thaumcraft-aspects --allow-partial \
  -o ports/thaumcraft-aspects-1.21.1.jar
```

- **Minechem Chemistry:** Imports elements, molecule formulas, and legacy metadata; registers vials using the legacy icons. Put two hydrogen vials and an oxygen vial in separate crafting slots to make a water vial. This adapts the molecule synthesizer's ingredient counts to table crafting. It does **not** recover ore or fluid processing, powered machines, reactors, radiation effects, or other legacy recipes.
- **Thaumcraft Aspects:** Imports the built-in aspect tags, colors, and component relationships and registers icon items. Try combining `aspect_aer` and `aspect_ignis` to make `aspect_lux` in the crafting grid. This uses the original aspect relationships but is **not** a port of the research table, essentia system, or magic. It does not support old Thaumcraft add-ons.
- Both JARs use separate namespaces and a **Partial Port** creative tab. In a **new** NeoForge 1.21.1 world get primitives from the creative tab or with `/give @s minechem_chemistry:element_h` or `/give @s thaumcraft_aspects:aspect_aer`. No survival acquisition is added. Recipes that take more than nine slots or share an identical input with another output are not generated; the item and ingredients are still listed in `data/<namespace>/mcmodup/catalog.json` with a skip reason. No original code or old dependency JAR is included.
- Only use sources and assets you are allowed to modify. A generated Thaumcraft adaptation is for private use unless the author has given permission to distribute it. `convert-jar` does not upload an artifact or publish a release.

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

Start with the elements and compounds, then port **one** decomposer or synthesizer recipe end to end. The machine needs modern block entity, menu, energy, and recipe implementations. Replace `@NetworkMod` packets with NeoForge payloads. Check dependencies against your report rather than copying any 1.6.4 API JARs into the new project. Follow the source tag's attribution and share-alike terms, and check bundled dependencies separately.

## Build a manual source port

After writing and reviewing the new Java and data files, use **JDK 21** and run the wrapper inside the selected workspace. For example:

```sh
cd ports/minechem-1.21.1
./gradlew build
```

On Windows, use `py -3` for the CLI and `gradlew.bat build` for Gradle. The build writes `build/libs/*.jar`. Test on a fresh client world and a dedicated NeoForge 1.21.1 server with the target dependencies. A scaffold can compile before it implements gameplay: neither a successful build nor a signature match proves you have a completed port. The tool does not migrate legacy worlds.

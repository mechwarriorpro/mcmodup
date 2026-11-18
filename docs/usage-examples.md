# Porting examples: Thaumcraft and Minechem

**Current capability:** There is a real JAR-to-JAR path for the two static content catalogs below. It is not a complete rewrite of either mod. For the rest of the gameplay, the dependency report and source workspace are starting points for a manual port.

Run the examples from the mcmodup checkout with Python 3.11+. Put your local inputs under `.mcmodup/` (ignored by Git). You can substitute an installed pack folder for either pack archive. A `.jar` or `.zip` pack must contain the actual mod files under `mods/` or `coremods/` (an enclosing instance folder is fine), or at the archive root. If a launcher export has only a download manifest, point to the installed instance instead.

## Automatically build the partial catalog JARs

With **JDK 21** on `PATH` or in `JAVA_HOME`, and Gradle network access:

```sh
python3 -m mcmodup convert-jar .mcmodup/Minechem-1.6.4-5.0.5.FINAL.jar \
  --pack .mcmodup/minechem-pack.zip --profile minechem-elements --allow-partial \
  -o ports/minechem-elements-1.21.1.jar

python3 -m mcmodup convert-jar .mcmodup/Thaumcraft-1.7.10-4.2.3.5.jar \
  --pack .mcmodup/thaumcraft-pack.jar --profile thaumcraft-aspects --allow-partial \
  -o ports/thaumcraft-aspects-1.21.1.jar
```

- **Minechem Elements:** Copies element names, atomic numbers, legacy metadata, classification, state, and radioactivity labels into the catalog. Registers individual element vials with the legacy test-tube icon. It does **not** simulate radiation or port machines, compounds, recipes, power, or fluids.
- **Thaumcraft Aspects:** Copies built-in aspect tags, colors, and component relationships into the catalog. Registers icon items with the recovered details. This is **not** the original essentia item, research system, wands, infusion, or other magic. It does not support old Thaumcraft add-ons.
- Both JARs have separate namespaces and a **Partial Port** creative tab. If installed on NeoForge 1.21.1, try `/give @s minechem_elements:element_h` or `/give @s thaumcraft_aspects:aspect_aer` in a **new** world. They include a notice and a `data/<namespace>/mcmodup/catalog.json` record with original-to-new data. No original code or old dependency JAR is included.
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

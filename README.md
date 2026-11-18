# mcmodup

Prepare standalone **Minecraft 1.21.1 / NeoForge** modpack updates from installed files. Analyze legacy Forge mods, stage compatible native builds, or compile standalone ports from reviewed source recipes. This is not a general-purpose converter for arbitrary binaries.

## Choose updates with checkboxes

```sh
python3 -m mcmodup pack-ui /path/to/LegacyPack --target /path/to/NeoForgePack \
  --output-pack .mcmodup/NewPack
```

Open the local URL to select legacy mods under `mods/` and `coremods/`. mcmodup reads the legacy platform evidence, finds native builds and their dependencies in the local packs, and creates a new instance without changing either input. Use `-o` instead to stage a replacement `mods/` folder for an existing target. **No Legacy Connector in the output or required at launch.** Missing native builds and dependencies are reported as blockers, never replaced by relabeled legacy code. See the [folder layout, CLI commands and limitations](docs/native-pack-updates.md).

## Try automatic recipe translation from an original archive

`update-pack` can read a small subset of single-class Forge mods from **1.5.2, 1.6.4, or 1.7.10** and generate a data-only JAR for **1.21.1 / NeoForge 21.1.267**. This is an opt-in **recipe preview**, not a full-parity update. It uses the original binary, not a modern replacement, source port, or Legacy Connector.

```sh
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --mod mods/Original.jar --allow-recipe-preview --output-pack .mcmodup/RecipePreview
```

See [eligibility, limitations, and the runtime checks](docs/native-pack-updates.md#preview-automatic-recipe-translation). Without the opt-in, the updater will not stage a recipe preview or use one as a native provider.

## Build native mods and add-ons from reviewed source

`build-jars` compiles the built-in source ports. With the exact **Thaumcraft 4.2.3.5** JAR in the legacy pack and native dependencies in the target pack, run:

```sh
python3 -m mcmodup build-jars LegacyPack --target NeoForgePack \
  --mod mods/Thaumcraft-1.7.10-4.2.3.5.jar --allow-preview-ports -o .mcmodup/native-jars
```

If the exact **GTNH Thaumic Tinkerer 2.13.0** is present and detected as an add-on, it is compiled against the just-built Thaumcraft JAR. The [source-build guide](docs/source-port-builds.md) lists prerequisites and optional [CC: Tweaked](docs/source-port-builds.md#optional-computercraft-integration) and [Ender Storage](docs/source-port-builds.md#optional-ender-storage-integration) support. For your own reviewed native source project, use `--source-ports TRUSTED.json` with `update-pack` or `pack-ui`.

The builder matches exact legacy archives, builds in dependency order, checks legacy assets and NeoForge GameTests, and stages only after those checks pass. It never substitutes a prebuilt approximation for an unsupported selection. **The built-in ports are not full-parity releases.** The command requires `--allow-preview-ports`; missing legacy behavior and integrations are not counted as ported.

The updater can reuse selected executable methods from exact originals. The [paired Minechem previews](docs/source-port-builds.md#both-exact-minechem-releases-linked-previews) stage both releases under separate IDs and adapt their output-slot logic to native inventories. The [Voltz glass plate preview](docs/source-port-builds.md#voltz-glass-pressure-plate-executable-preview) links the old redstone logic for one ICBM `1.4.1.344` block. These are NeoForge `21.1.267` previews, not complete ports or a playable update of the eight-archive selection.

## Build a new JAR from a supported part of a legacy mod

`convert-jar` takes the original mod JAR and a pack folder or archive. With JDK 21 and Gradle, it recovers data and icons from the legacy binary and compiles **new** NeoForge Java code. The explicitly partial profiles are:

- **Minechem 1.6.4:** chemistry catalog, powered synthesis and decomposition, and research for the recovered recipes.
- **Minechem 1.7.10 5.0.5.394:** checksum-pinned catalog and iron-ingot decomposer, under a separate mod ID from the 1.6.4 build.
- **Thaumcraft 4 1.7.10:** aspect items and crafting.

None replaces the original mod, loads original saves, or supports original add-ons. Atomic Science and ICBM do not have a `convert-jar` profile.

```sh
python3 -m mcmodup convert-jar Minechem-1.6.4-5.0.5.FINAL.jar --pack MyPack.zip \
  --profile minechem-chemistry --allow-partial -o ports/minechem-chemistry-1.21.1.jar
```

The command refuses to build without `--allow-partial`. It matches known bytecode declarations rather than executing legacy code. A class may be recovered from an unambiguous pack dependency if the primary JAR references it. Other dependencies are inventoried, not bundled or ported. A missing or mismatched input, unknown declaration shape, or failed Gradle build leaves no new output JAR. First-run Gradle downloads need internet access. See [example commands and coverage](docs/usage-examples.md) for scope and licensing before using private assets.

The [Legacy Connector evaluation](docs/legacy-connector.md) is a separate reference for mappings, original behavior and compatibility gaps. Its runtime and cached JARs are not inputs to `update-pack` or `pack-ui`.

## Try it

From this checkout, with **Python 3.11 or newer** (no Python dependencies):

```sh
python3 -m mcmodup inspect examples/retro-ruby
python3 -m mcmodup port examples/retro-ruby -o ports/retro-ruby --plain-item ruby
```

Open `ports/retro-ruby/PORTING.md`. The demo registers a plain `retro_ruby:ruby` item and reuses its legacy display name. It uses a paper texture because the example has no art.

To build or launch any generated project, install **JDK 21** and allow Gradle to download its dependencies on the first run:

```sh
cd ports/retro-ruby
./gradlew build
./gradlew runClient
```

On Windows, use `py -3` instead of `python3` and `gradlew.bat` instead of `./gradlew`. In a new creative test world, run `/give @s retro_ruby:ruby` to check the demo item. Use `./gradlew runServer` separately to check dedicated-server loading. A successful build is a check on the workspace, not proof that the original mod's gameplay has been ported.

You can also install the tool into a Python environment with `python -m pip install .` and run `mcmodup` directly.

## Scan a modpack for dependencies and API changes

See the [Minechem, Thaumcraft, Atomic Science, and ICBM examples](docs/usage-examples.md) for what the tool can recover from an original JAR and where automatic conversion is not yet supported.

Point `scan-pack` at an installed instance, its `mods` folder, or a `.jar`/`.zip` with embedded mod archives. It scans `mods/` and `coremods/` recursively (including versioned subfolders such as `mods/1.7.10/`) and accepts legacy `.zip` mods. In launcher-era instances it can also read the exact local Minecraft and Forge APIs from `bin/minecraft.jar` and `bin/modpack.jar`. It does not run the pack or change your inputs. A launcher export with only a download manifest is not a dependency source; use the installed instance.

```sh
python3 -m mcmodup scan-pack /path/to/instance --source-version 1.7.10
python3 -m mcmodup scan-pack /path/to/instance --source-version 1.7.10 \
  --mod mods/OldMod.jar --json > pack-report.json
python3 -m mcmodup scan-pack /path/to/pack.jar --source-version 1.7.10 \
  --mod-jar /path/to/OldMod.jar --json > pack-report.json
```

Use `--mod` for a mod already in the pack, or `--mod-jar` to check a separately selected file against the pack. Both options also work with `decompile-deps`. An identical copy already present in the pack is not counted twice. Patched Minecraft/Forge classes buried in coremods are not treated as game API providers; supply those APIs explicitly.

The report separates **declared dependencies** (`mcmod.info` and compiled FML `@Mod` annotations) from **binary dependencies** identified by JVM class references. It shows missing or duplicate providers, class and member references, and which legacy API patterns need a rewrite. Declaration presence isn't version-range resolution. Reflection, dynamically loaded classes, shaded or nested libraries, and behavior won't all be visible in a static scan.

### Check exact APIs and names

Built-in guidance distinguishes, for example, 1.5.2/1.6.4 packet handlers from 1.7.10 SimpleImpl and explains where each needs to move to NeoForge payloads. Source audits provide API guidance with file/line references. For **signature verification**, point the pack scanner to local compiled artifacts on both sides:

```sh
python3 -m mcmodup scan-pack /path/to/instance --source-version 1.7.10 \
  --legacy-api /local/forge-1.7.10-universal.jar \
  --legacy-api /local/minecraft-1.7.10.jar \
  --legacy-srg /local/mcp/joined.srg \
  --legacy-methods /local/mcp/methods.csv --legacy-fields /local/mcp/fields.csv \
  --target-api /local/neoforge-21.1.267-universal.jar \
  --target-api /local/mojang-named-minecraft-1.21.1.jar \
  --port-map examples/reviewed-port-map.json --json > pack-report.json
```

- Use **compiled** JARs or class directories, not source ZIPs or installers. Get the target jars from the NeoForge 1.21.1 development classpath. Include the FML loader and event bus jars separately when checking those APIs. Multiple `--legacy-api` and `--target-api` flags are supported. Nothing is downloaded or installed by the scan.
- Use mappings for the exact legacy release: `joined.srg` in obfuscated → SRG direction; optional MCP CSVs with `searge,name` columns. The scanner normalizes legacy declarations and references, including types in descriptors. It does **not** assume legacy obfuscated names match modern names. If an archive declares a conflicting version, the chosen legacy index/mappings are not applied to it.
- A [reviewed port map](examples/reviewed-port-map.json) explicitly pairs old and new references. Keys are JVM `package/Class`, `package/Class#method(I)V`, or `package/Class#field:I`, and the file is tied to a single source version. Native checks use full descriptors, not name-only comparisons. `same_signature_found` and `mapped_signature_found` establish signature presence in the supplied target index, not compatible behavior. `rewrite` is a migration recommendation; a missing signature or unverified mapping is **not** proof that a symbol is absent from an entire release.

### Read a dependency implementation locally

When you have permission, give mcmodup a trusted [Vineflower release JAR](https://github.com/Vineflower/vineflower/releases) outside the modpack and a supported Java runtime (JDK 21 works with Vineflower 1.12):

```sh
python3 -m mcmodup decompile-deps /path/to/instance --mod mods/OldMod.jar \
  --vineflower /local/tools/vineflower-1.12.0.jar \
  --class vendor.lib.Widget -o .mcmodup/references/old-mod
```

The command stages only unambiguously referenced classes from other scanned mods or explicitly supplied legacy API artifacts, plus needed enclosing/nested classes. `--class` is repeatable; omit it to inspect all resolved references up to the limit. Use the same `--source-version`, `--legacy-api`, and mapping flags as the scan to resolve Forge/Minecraft classes. It runs **Vineflower**, not any mod code. Inspect `decompile-report.json` for provenance and name aliases; the Java text is approximate and isn't automatically renamed or compiled. These references stay local and are ignored by Git. Don't commit pack JARs, mappings you can't redistribute, or decompiled code.

## Read a mod with its original Minecraft names

Use [`decompile-mod`](docs/mapped-decompilation.md) to read the selected mod with **same-version MCP names** for Minecraft 1.5.2, 1.6.4, or 1.7.10. Supply a local MCP config, SpecialSource and Vineflower; the mod and the mapping inputs are not modified. This writes Java references and a native port checklist, not an updated JAR. `plan-port` generates the checklist without Java and can use a local Legacy Connector `CheckJar` JSON report and same-release gameplay notes. A Connector `links` result is not proof of gameplay or native parity.

## Generate an optional development workspace

```sh
# Audit a source directory or a source ZIP. Add --json for tools/scripts.
python3 -m mcmodup inspect /path/to/legacy-source

# If the source declares its Minecraft version and a valid mod ID:
python3 -m mcmodup port /path/to/legacy-source -o ports/my-mod

# For an older MCP/Forge checkout without reliable metadata:
python3 -m mcmodup port /path/to/legacy-source -o ports/my-mod \
  --source-version 1.5.2 --mod-id my_mod

# If you only have the binary, inspect it for metadata and coremod flags:
python3 -m mcmodup inspect /path/to/legacy-mod.jar
```

- **`inspect`** reads `mcmod.info`, recognizable legacy Gradle settings, and literal legacy `@Mod` options. Filename versions are hints only. The audit flags common APIs with file and line references, including registries, lifecycle events, recipes, networking, rendering, and world generation. For binary-only JARs it checks metadata and manifest markers without decompiling or running the mod.
- **`port`** needs readable Java sources. It writes a **new** project outside the input, with `neoforge.mods.toml`, a `@Mod` entry point, a Gradle wrapper, and `PORTING.md`. It never overwrites the input or an existing output folder. Use `--mod-id` to choose a mod in a multi-mod checkout or replace a single mod's invalid legacy ID. `--name`, `--mod-version`, and `--license` override metadata. Verify the original license before porting or distributing anyone else's work.
- **Translations**: Converts simple UTF-8 `key=value` `.lang` files to lowercase-locale JSON. It recognizes `assets/<modid>/lang`, `mods/<modid>/lang`, and common bare `lang` folders for single-mod inputs. Old keys are kept for review; conflicting keys and unsupported lines (such as Java properties escapes) are reported rather than guessed.
- **`--plain-item ITEM_ID`** is repeatable. Use it only when you want a plain replacement with that registry name. It generates a `DeferredRegister` entry, a 1.21.1 item model, and a modern translation key. A unique matching standalone PNG under `textures/items` or `textures/item` is reused; otherwise it uses a clearly reported paper placeholder. The tool does not reproduce custom item behavior, properties, or atlas/animated textures.

## Current automatic-update coverage

The goal is a runnable NeoForge JAR from the original mod without a manual rewrite. Today `update-pack` can stage an existing native build, run a matching reviewed build recipe, or emit an opt-in recipe preview. It cannot automatically rewrite an arbitrary legacy JAR with full parity. Registries, world generation, packets, client rendering and coremods can require behavioral changes that version metadata and name mappings alone do not provide. A blocked update is a gap in the tool, not a successful conversion. The optional `port` workspace is a development scaffold, not an update result. **Legacy saves are not migrated.**

The [sampled updater workflow](.github/workflows/legacy-update-sample.yml) measures original-only updates on all three legacy releases. The original-only result was **0/3** (Xaero’s Minimap, BdLib, Twilight Forest). Pre-existing native JARs are controls; neither they nor the separately selected recipe previews count as full-parity conversions.

## Build reviewed changes with GitHub Actions

Add `--github-actions` to `port` to generate `.github/workflows/build.yml` inside the new workspace. After you review the workspace and confirm redistribution rights, commit only authorized source and assets to your chosen branch in a repository you control. A push or pull request runs `./gradlew build` with JDK 21 and uploads the built mod JAR under **Actions**. The workflow does not commit, push, or release files for you. This repository's CI checks scoped decompilation with synthetic inputs and the archived Voltz pack. It builds the conversion profiles from the released originals and exercises the fixture-supported profiles on synthetic inputs as well. The headless NeoForge GameTests check power, automation, blocked outputs, machine saves, microscope discoveries, and journal recipe selection. They also test the two distinct Minechem decomposers side by side. Original releases and adaptations stay in runner storage; **only** the synthetic Retro Ruby demo JAR is uploaded. These tests do not establish legacy gameplay parity or test a user pack.

## Development

```sh
python3 -m unittest discover -s tests -v
```

The CLI inspection and generation steps are local, never execute legacy code, and do not unpack archive paths into your working tree. Tests exercise all three input versions, source ZIPs, pack inventories, JVM signatures and mappings, scoped decompiler staging, and generated metadata/assets. The target is pinned to NeoForge **21.1.267** with ModDevGradle **2.0.148**, based on the [1.21.1 MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/tree/d69737d705926b200c23769f0a674ff6379badb4). The bundled [Gradle 9.2.1](https://github.com/gradle/gradle/tree/v9.2.1) wrapper includes a pinned distribution checksum. Both upstream notices are included in generated workspaces.

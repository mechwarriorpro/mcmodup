# mcmodup

Move legacy Forge mods for **Minecraft 1.5.2, 1.6.4, or 1.7.10** into a source-porting workspace for **Minecraft 1.21.1 on NeoForge**. The tool migrates simple assets and metadata and tells you where to start rewriting the mod. It doesn't make an old JAR playable on a new loader.

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

Point `scan-pack` at the installed instance or its `mods` directory. It scans `mods/` and `coremods/` recursively (including versioned subfolders such as `mods/1.7.10/`), accepts legacy `.zip` mods, and doesn't run the pack or change any files.

```sh
python3 -m mcmodup scan-pack /path/to/instance --source-version 1.7.10
python3 -m mcmodup scan-pack /path/to/instance --source-version 1.7.10 \
  --mod mods/OldMod.jar --json > pack-report.json
```

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

## Port your own mod

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

## What still needs a manual port

The generated project does **not** compile legacy source, repack class files, or enable old dependencies. Use the audit as a checklist while rewriting against the [NeoForge 1.21.1 docs](https://docs.neoforged.net/docs/1.21.1/gettingstarted/). Check that each dependency has a 1.21.1 NeoForge version. Recreate recipes and loot, packets, screens and renderers, entities and world generation as needed. Mods with coremods or bytecode patches need special review. Use disposable worlds and a dedicated test server before release; **legacy saves are not migrated**.

If you only have a compiled JAR, check for an existing 1.21.1 port or obtain authorized source before trying to generate a workspace.

## Build reviewed changes with GitHub Actions

Add `--github-actions` to `port` to generate `.github/workflows/build.yml` inside the new workspace. After you review the workspace and confirm redistribution rights, commit only authorized source and assets to your chosen branch in a repository you control. A push or pull request runs `./gradlew build` with JDK 21 and uploads the built mod JAR under **Actions**. The workflow does not commit, push, or release files for you. This repository's CI also runs the Python tests and builds the included demo project, not any user's pack.

## Development

```sh
python3 -m unittest discover -s tests -v
```

The CLI inspection and generation steps are local and never execute legacy code or extract archive paths. Tests exercise all three input versions, source ZIPs, pack inventories, JVM signatures and mappings, scoped decompiler staging, and generated metadata/assets. The target is pinned to NeoForge **21.1.267** with ModDevGradle **2.0.148**, based on the [1.21.1 MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/tree/d69737d705926b200c23769f0a674ff6379badb4). The bundled [Gradle 9.2.1](https://github.com/gradle/gradle/tree/v9.2.1) wrapper includes a pinned distribution checksum. Both upstream notices are included in generated workspaces.

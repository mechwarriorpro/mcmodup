# Update an installed modpack to native NeoForge mods

`pack-ui` provides a checklist. `update-pack` is the equivalent scriptable command. Both select **native Minecraft 1.21.1 / NeoForge JARs** from local pack files. The output does not install, run, or require Legacy Connector, its shims, or its translated cache. Connector is [a separate reference for legacy behavior and mappings](legacy-connector.md), not this updater's translation engine.

## Folder layout

Use installed directories, not launcher export ZIPs or download manifests:

```text
LegacyPack/
  mods/                    Original .jar and .zip mods and add-ons
  coremods/                Optional additional legacy archives
  bin/                     Original Minecraft and Forge files, if available
  lib/ or libraries/       Additional local candidates
  mcmodup-updates.json     Optional reviewed mapping for renamed mod IDs
UpdatedPack/
  mods/                    The target's already installed native mods
  bin/                     Installed 1.21.1 Minecraft JAR and NeoForge 21.1.x JAR
  lib/ or libraries/       Native update and dependency candidates, not yet active
```

The updater reads the Minecraft release from the game JAR's `version.json`, a supported vanilla client fingerprint, or the modern server bundle's version list. It identifies the target loader using the installed `neoforge-21.1.x-universal.jar` (or `neoforge-21.1.x.jar`) and its NeoForge classes. An installer alone is not the installed loader. The original `bin/` also provides legacy version evidence for JARs without an exact declaration. Neither `bin/` is modified, and the old platform's JARs are never treated as modern mods or dependencies.

Put inactive native builds in `lib/`, `libraries/`, or `bin/` in either pack. To use one mixed folder containing a 1.21.1 installation and legacy JARs, omit `--target`. Legacy files already in the target must have replacements before a complete mods folder can be staged; unrelated active files are not silently dropped.

## Choose mods and stage the update

From the checkout, with Python 3.11+:

```sh
python3 -m mcmodup pack-ui /path/to/LegacyPack --target /path/to/UpdatedPack \
  -o .mcmodup/staged-1.21.1
```

Open the local URL printed by the command (normally `http://127.0.0.1:8765/`). Select a mod, then review the add-ons, dependencies and replacement files before clicking **Stage updated mods**. Omit `-o` for a read-only workbench. Keep a writable workbench local; it is not a public upload service.

The CLI also supports catalog, preview and stage without a browser:

```sh
# List candidates and blockers, with no changes
python3 -m mcmodup update-pack LegacyPack --target UpdatedPack --json

# Plan the selection, auto-including qualifying add-ons and dependencies
python3 -m mcmodup update-pack LegacyPack --target UpdatedPack --mod mods/OldMod.jar

# Write a complete replacement mods/ folder plus the report and instructions
python3 -m mcmodup update-pack LegacyPack --target UpdatedPack \
  --mod mods/OldMod.jar --mod mods/AnotherMod.jar -o .mcmodup/staged-1.21.1
```

Once the plan is ready, stop the target game, back up its `mods/`, and **replace** that folder with the staged `mods/`. Do not merge stale and replacement JARs. `update-report.json` records where the staged files came from, their SHA-256 hashes, and the old target files being replaced. Test a fresh world first. The updater does not change either pack or move saves and configs.

## How matches are checked

- A native candidate must contain compiled code and `META-INF/neoforge.mods.toml`. New candidates need a declared Minecraft or NeoForge range that includes the installed target. A filename alone does not make a JAR compatible. Local native builds of a renamed legacy mod can be matched using `LegacyPack/mcmodup-updates.json`, for example `{"oldmod": "new_mod_id"}`. Use this only for a reviewed successor, not an unrelated mod with similar features.
- Selecting a base mod includes old add-ons identified by required FML declarations or unambiguous class references. Explicitly optional FML integrations are not auto-selected. Every included mod ID needs a native counterpart. The selected native JARs' **modern** dependency declarations drive installation, including dependencies of dependencies. The planner does not install old Forge libraries that the native builds no longer use.
- The updater keeps target native mods where their versions satisfy the plan, selects an unambiguous compatible local replacement when needed, and checks the declared requirements of retained target mods as well. Literal versions and single numeric Maven intervals such as `[2.1,3)` are supported. Missing or ambiguous matches, incompatible requirements, and output filename collisions block staging. A plan does not verify reflection, optional integrations, loader-side behavior, or gameplay. The updater does not resolve Jar-in-Jar version negotiation; required dependencies not resolved via the top-level inventory may need a standalone local JAR.
- Known Connector derivatives, declared Connector dependencies, legacy FML references in modern output, and recognizable Connector code references are rejected. Existing partial mcmodup profiles are not sold as full replacements.

**When no native build exists:** The updater cannot turn an arbitrary old Forge binary and its add-ons into independent NeoForge mods. It lists those as blocked. The existing [`convert-jar` profiles](usage-examples.md) generate explicitly partial Minechem and Thaumcraft adaptations without Connector; they are not drop-in replacements and do not satisfy original add-ons. For missing full updates, supply a verified native build (including add-ons), or port the missing behavior natively before staging it.

## What was tested

The [native updater CI smoke test](https://github.com/mechwarriorpro/mcmodup/actions/runs/44291482139) staged compiled NeoForge builds of the Retro Ruby sample, a sample add-on, and its auto-selected library. A NeoForge `21.1.267` server loaded all three without Connector, and the add-on's GameTest found the base mod's registered item. That tests the update path, not gameplay compatibility for the original third-party mods.

# MCP-named references and a native port checklist

Use `decompile-mod` to read the **selected original mod**, with Minecraft classes and members named for that mod's exact Minecraft release. This is not a conversion to NeoForge. Use `plan-port` to get the same checklist without running Java, and [`decompile-deps`](../README.md#read-a-dependency-implementation-locally) to read specific dependency implementations separately.

## Choose local mappings

Use files you are allowed to use from the [Minecraft Wiki's MCP downloads index](https://minecraft.wiki/w/Tutorial:Programs_and_editors/Mod_Coder_Pack#Downloads). The app does not download or redistribute MCP or run its scripts.

| Mod's Minecraft version | MCP example | Mapping layout |
| --- | --- | --- |
| **1.5.2** | MCP 7.51 | `client.srg` / `server.srg` |
| **1.6.4** | MCP 8.11 | `client.srg` / `server.srg`, or `joined.srg` in [Forge FML's config](https://github.com/MinecraftForge/FML/tree/1.6.4/conf) |
| **1.7.10** | MCP 9.08, or [Forge FML's 9.05 config](https://github.com/MinecraftForge/FML/tree/1.7.10/conf) | `joined.srg` |

`--mcp` accepts a local ZIP, `conf/` folder, an unpacked MCP folder with `conf/`, or a catalog such as `~/mcp/` containing `1.5.2.zip`, `1.6.4.zip`, and `1.7.10.zip` (version-named directories also work). Each configuration needs `version.cfg`, the SRG file, `methods.csv`, and `fields.csv`. If supplied, FML's `packages.csv` describes the old `net/minecraft/src` repackaging. The mod's `mcmod.info` or exact `@Mod` declaration selects the version. If neither gives an exact release, verify it and add `--source-version`; the mod's own release number and the filename are not substitutes. Contradictory Minecraft declarations and mismatching MCP headers stop the run rather than falling back to a nearby version.

For separate client/server SRGs, the default is client. Use `--mcp-side server` only for a mod built against the dedicated-server names. The tool does not merge the two obfuscation tables. For a joined SRG it still uses `--mcp-side` to resolve differing MCP names for the same SRG ID.

## Recover readable Java

Use Java 21 and trusted local release JARs kept outside the modpack: [Vineflower 1.12.0](https://github.com/Vineflower/vineflower/releases/tag/1.12.0) and [SpecialSource 1.11.6 shaded](https://repo.maven.apache.org/maven2/net/md-5/SpecialSource/1.11.6/SpecialSource-1.11.6-shaded.jar). The tested hashes are pinned in [CI](../.github/workflows/ci.yml). With a verifiable `1.7.10` mod, for example:

```sh
python3 -m mcmodup decompile-mod /path/to/LegacyPack \
  --mod mods/Thaumcraft-1.7.10-4.2.3.5.jar --mcp ~/mcp \
  --specialsource .mcmodup/tools/SpecialSource-1.11.6-shaded.jar \
  --vineflower .mcmodup/tools/vineflower-1.12.0.jar \
  -o .mcmodup/thaumcraft-references
```

Pass an individual JAR as the input to omit the selector, or use `--mod-jar /path/to/separate.jar` instead of `--mod` to analyze a separate original against a pack. Use `--class package.OriginalClass` (repeatable) to read only specific class families. A size or class limit fails instead of silently dropping the rest. `--legacy-api`, `--target-api`, and `--port-map` are optional [signature inputs](../README.md#check-exact-apis-and-names). Supply only exact-release legacy APIs and review cross-version symbols rather than assuming names imply compatibility.

- **`sources/`**: best-effort Java references. SpecialSource remaps a temporary class-only JAR first; Vineflower reads those rewritten classes. It is not a text replacement over the decompiler output, so string literals such as reflection targets are not renamed. Hierarchy lookups use the selected mod, unambiguous peers identified as the same release, and supplied legacy API headers. Methods in an incomplete hierarchy, symbols outside the mappings, and debug names can remain unreadable. Review rather than running or compiling this text as a port.
- **`decompile-report.json`**: original JAR fingerprint and version, MCP inputs and SHA-256s, emitted sources, missing source families and remaining visible SRG symbols. The two tool logs are local diagnostics.
- **`PORTING.md` and `port-plan.json`**: the selected mod's prioritized work. The plan still covers the whole mod when `--class` narrows the Java output.

The new folder must be outside the packs and mapping/API inputs. Only Java and reports are retained, not the temporary remapped JAR or original assets. The folder has a `.gitignore`; keep the references private and honor the original mod and MCP licenses. The tools read bytecode but don't launch the legacy mod or load Connector. Use only trusted tool binaries: their scratch directory is not a sandbox.

## Use Connector evidence without importing its runtime

On the pinned [Legacy Connector checkout](https://github.com/kylepitcock/Legacy-Connector/tree/062c6e8d902083abae3adae2e856a657c7560ebb), run `CheckJar` with the exact original and its dependency JARs as one set, for example:

```sh
# In the trusted Connector checkout, with its own documented build prerequisites:
./gradlew checkLegacyMod \
  -PlegacyJar=/abs/LegacyPack/mods/Thaumcraft-1.7.10-4.2.3.5.jar,/abs/LegacyPack/mods/Baubles-1.7.10-1.0.1.10.jar \
  -PcheckJson=/abs/private/thaumcraft-check.json
```

Pass the **raw per-JAR array**, not the website's summarized `probe.json`. The CLI checks filename and Minecraft version and compares available mod IDs/release versions. CheckJar does not include input hashes; rerun it against your exact files and peers. For scoped historical observations, optionally pass a local copy of the pinned [`docs/data/mods.json`](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/data/mods.json). The planner matches Minecraft version, display name and mod release and excludes offline probe/corpus rows. Different releases (e.g. upstream ICBM **1.4.2** vs Voltz **1.4.1.344**) are not evidence for one another.

```sh
python3 -m mcmodup plan-port /abs/LegacyPack \
  --mod mods/Thaumcraft-1.7.10-4.2.3.5.jar --mcp ~/mcp \
  --connector-report /abs/private/thaumcraft-check.json \
  --connector-gameplay /abs/Legacy-Connector/docs/data/mods.json \
  > .mcmodup/thaumcraft-port-plan.md
```

Add `--json` instead for the full evidence, including symbols, use sites, verifier groups and original notes. `decompile-mod` accepts both Connector options too. Neither option is required: missing evidence is reported as unmeasured.

1. **P0 — dependencies and blockers.** Resolve exact providers and build order. Check missing dependencies, unresolved references, verifier findings and coremods before remaking higher-level features. A Connector-specific translation problem isn't automatically a native API defect.
2. **P1 — behavior and API rewrites.** Trace generated placeholders and static API use back to the legacy behavior, then implement it with native APIs. For example, a stubbed automation hook still needs a real implementation and a regression case even if the JAR `links`.
3. **P2 — assets, integrations and gameplay.** Compare original assets and rendered behavior, reproduce same-release observations as regression cases, and check optional integrations both installed and absent. Connector gameplay reports may cover only one feature, one side, or one pack.

Full parity is **not** the output of this workflow. Inventory the original content, establish a baseline, then compare against the native port in a NeoForge 1.21.1 dedicated server and rendered client **without Connector**. Test persistence, side effects, networking, and other behavior as applicable. Track deviations and untested features; test migration separately. The [native source build](source-port-builds.md) and [pack staging](native-pack-updates.md) paths do not consume the MCP config, decompiled Java, or Connector cache.

"""Turn scoped static and Connector observations into native porting work, not parity claims."""

from collections import defaultdict
import hashlib
from pathlib import Path

from . import MINECRAFT_VERSION, NEOFORGE_VERSION, SOURCE_VERSIONS
from . import connector_evidence
from .inputs import InputError
from .mcp import McpConfig
from .pack import ModPack, PackMod


def exact_minecraft(pack: ModPack, mod: PackMod) -> str:
    version = mod.audit.source_version
    declared = {e.version for e in mod.audit.version_evidence if e.kind in {"declared", "versioned folder"}}
    if len(declared) > 1 or (pack.supplied_profile and declared - {pack.supplied_profile}):
        raise InputError(f"Conflicting Minecraft version evidence for {mod.archive!r}: declared "
                         f"{', '.join(sorted(declared))}; requested {pack.supplied_profile or 'none'}. Verify the original first.")
    if version is None:
        raise InputError(f"No exact Minecraft version for {mod.archive!r}; verify the original and supply --source-version. "
                         "A filename, mod version, or Connector result is not enough.")
    if version not in SOURCE_VERSIONS:
        raise InputError(f"Minecraft {version} is not a supported source version for this workflow.")
    return version


def _worklist(static: dict, link: dict | None, observed: dict | None, archive_errors: list[dict]) -> list[dict]:
    work = []

    def add(priority: str, code: str, evidence: str, action: str, examples=(), count=None) -> None:
        work.append({"priority": priority, "id": code, "evidence": evidence, "action": action,
                     "count": count, "examples": list(examples)})

    if static["corrupt_classes"] or archive_errors:
        add("P0", "incomplete-scan", "static", "Recover unreadable inputs before assuming the inventory is complete.",
            [row["archive"] for row in archive_errors], static["corrupt_classes"] + len(archive_errors))
    if not static["classes_indexed"]:
        add("P0", "missing-bytecode", "static", "Find the actual original mod JAR; no compiled classes could be analyzed.")
    required = [row for row in static["declared_dependencies"] if row["required"] and row["status"] != "loader"]
    blockers = [f"{r['id']}: {r['status']} ({r['declaration']})" for r in required if r["status"] != "present"]
    if blockers:
        add("P0", "required-dependencies", "static", "Resolve missing or ambiguous required legacy providers and record exact releases.",
            blockers, len(blockers))
    providers = {r["archive"] for r in static["binary_dependencies"]}
    providers.update(a for r in required if r["status"] == "present" for a in r["archives"])
    providers = sorted(providers - {static["archive"]})
    if providers:
        add("P0", "provider-order", "static", "Review provider versions and optionality. Port required providers first, or replace them with tested native equivalents.",
            providers, len(providers))
    if static["ambiguous_dependency_classes"]:
        add("P0", "ambiguous-providers", "static", "Identify which archive actually supplies each duplicate class before translating dependent code.",
            static["ambiguous_dependency_classes"], len(static["ambiguous_dependency_classes"]))
    if static["manifest_findings"] or (link and link.get("coremod")):
        examples = [example for f in static["manifest_findings"] for example in f["examples"]]
        if link and link.get("coremod"):
            examples.append(link["coremod"])
        add("P0", "coremods", "static/Connector", "Inventory patches and access changes; implement their intent with native hooks, not copied transformers.", examples)
    if link:
        if link["result"] == "missing-dependency" or link.get("missingDependencies"):
            add("P0", "connector-dependencies", "Connector linkage", "Recheck the original pack's exact dependency set; missing providers can masquerade as API gaps.",
                link.get("missingDependencies", ()))
        if link["result"] == "unresolved" or link.get("unresolved") or link.get("missing"):
            add("P0", "connector-unresolved", "Connector linkage", "Trace each unresolved symbol to a missing library, rewritten game API, or Connector-only gap. Implement only the needed native path.",
                link.get("missing", ()), link.get("unresolved"))
        verify = link.get("verify", {})
        if link.get("verifyFailures") or link.get("verifyErrors") or verify.get("findings") or verify.get("groups"):
            groups = sorted(verify.get("groups", []), key=lambda row: (-row.get("sites", 0), row["problem"]))
            examples = [row["problem"] + (f" ({row['examples'][0]})" if row.get("examples") else "") for row in groups]
            add("P0", "connector-verifier", "Connector linkage", "Inspect the flagged call sites and redesign changed types, access and inheritance in native code.",
                examples or link.get("verifyErrors", []), verify.get("findings", link.get("verifyFailures")))
        if link["result"] in {"obsolete", "skipped", "not-legacy"}:
            add("P1", "connector-not-checked", "Connector linkage", "No linked legacy result for this JAR. Check the reason and review the original behavior separately.",
                [link.get("reason", link["result"])])
        if link.get("synthesizedClasses") or link.get("synthesizedMembers") or link.get("stubbed"):
            add("P1", "connector-placeholders", "Connector linkage", "Replace synthesized classes and stubbed members with behavior, not another no-op. Add regression tests for the affected features.",
                link.get("stubbed", ()), (link.get("synthesizedMembers", 0) + link.get("synthesizedClasses", 0)) or None)
        assets = link.get("assets", {})
        if any(assets.get(k) for k in ("layer", "mod", "missingModels", "missingTextures", "badJson", "badPng")):
            add("P2", "connector-assets", "Connector resource scan", "Audit original assets vs native assets and check them in a rendered client. Connector's translation can cause its own misses.",
                assets.get("examples", ()))
        if link.get("optional"):
            add("P2", "connector-optional", "Connector linkage", "Test guarded integrations both absent and present; optional calls are not required-dependency failures.",
                link["optional"], len(link["optional"]))

    areas = defaultdict(dict)
    unverified = []
    for check in static["api_checks"]:
        ref = f"{check['legacy_names'][-1]} — {check['used_by']}"
        for rule in check["guidance"]:
            areas[rule["area"]].setdefault(ref, rule["advice"])
        if check["abi_changes"] or check["status"] in {"review_signature", "mapping_unverified", "target_not_indexed"}:
            unverified.append(ref)
    for area, refs in sorted(areas.items(), key=lambda entry: (-len(entry[1]), entry[0])):
        advice = " ".join(dict.fromkeys(refs.values()))
        add("P1", f"native-{area}", "static", advice, refs, len(refs))
    if unverified:
        add("P1", "target-signatures", "static", "Verify unindexed or changed signatures against the exact target APIs. Even a matching signature is not a behavior match.",
            unverified, len(unverified))
    external = static["unknown_external_classes"]
    if external["count"]:
        add("P1", "unattributed-references", "static", "Classify unknown classes as missing APIs, optional integrations, or unused references. Static use is not a runtime dependency trace.",
            external["examples"], external["count"])
    if observed and observed["observations"]:
        add("P2", "reproduce-observations", "Connector gameplay notes", "Turn these same-release observations and limitations into native regression cases; reproduce against the original when permitted.",
            [f"{r['name']} {r['version']}: {r['notes']}" for r in observed["observations"]], len(observed["observations"]))
    return sorted(work, key=lambda row: row["priority"])


def plan_port(pack: ModPack, mod: PackMod, version: str, mcp: McpConfig | None,
              connector_report: Path | None, connector_gameplay: Path | None) -> dict:
    with mod.path.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    scan = pack.report(mod.archive)
    static = scan["archives"][0]
    linkage = connector_evidence.linkage(connector_report, mod, version) if connector_report else None
    gameplay = connector_evidence.gameplay(connector_gameplay, mod, version) if connector_gameplay else None
    return {
        "schema_version": 1,
        "input": {"archive": mod.archive, "origin": pack.origin(mod.path), "sha256": sha, "minecraft": version},
        "target": {"minecraft": MINECRAFT_VERSION, "loader": "NeoForge", "neoforge": NEOFORGE_VERSION},
        "mcp": mcp.evidence() if mcp else None,
        "static": static, "legacy_api_inputs": scan["legacy_api_inputs"], "target_api_inputs": scan["target_api_inputs"],
        "scan_warnings": scan["warnings"], "archive_errors": scan["archive_errors"],
        "connector": {"linkage": linkage, "gameplay": gameplay},
        "work": _worklist(static, linkage["record"] if linkage else None, gameplay, scan["archive_errors"]),
        "native": {"gameplay_verified": False, "parity_verified": False},
        "verification_required": [
            "Record exact original and provider hashes. Inventory every registry entry, recipe, config, and feature; account for missing or substituted assets.",
            "Establish legacy behavior on a disposable instance or from reviewed exact-version source. Include failure cases and side effects, not just whether an action opens or responds.",
            "Test the native mod without Connector on a NeoForge 1.21.1 dedicated server and rendered client. Compare gameplay, persistence after save/reload, networking, worldgen, visuals, and integrations with and without optional mods, where applicable.",
            "Keep untested and deviating behavior on the checklist. A signature match, translated JAR, boot, or Connector status does not certify native parity. Old-save migration needs its own validation.",
        ],
    }


def _literal(text: str) -> str:
    # Prevent imported filenames and notes from becoming Markdown or terminal controls.
    return "`" + "".join(ch if ch.isprintable() and ch != "`" else ascii(ch)[1:-1].replace("`", "\\u0060")
                         for ch in text) + "`"


def render_plan(plan: dict) -> str:
    src, target = plan["input"], plan["target"]
    link, observations = plan["connector"]["linkage"], plan["connector"]["gameplay"]
    static = plan["static"]
    lines = ["# Native port checklist", "", f"- Original: {_literal(src['archive'])} (Minecraft {src['minecraft']})",
             f"- SHA-256: `{src['sha256']}`", f"- Target: Minecraft {target['minecraft']} / NeoForge {target['neoforge']}",
             "- Native gameplay and parity: **not verified**.", "", "## Evidence", "",
             f"- Static: {static['classes_indexed']} classes, {len(static['api_checks'])} API checks. No mod code was run."]
    if mcp := plan["mcp"]:
        lines.append(f"- Naming: MCP {_literal(mcp['mcp_version'])}, {_literal(mcp['srg'])}, {mcp['side']} CSV preference.")
    if link:
        row = link["record"]
        lines.append(f"- Connector CheckJar: {_literal(row['result'])}; "
                     f"{row.get('unresolved', 'unreported')} unresolved, {row.get('synthesizedClasses', 'unreported')} synthesized classes, "
                     f"{row.get('synthesizedMembers', 'unreported')} synthesized members, {row.get('verifyFailures', 'unreported')} verifier failures.")
        lines.append("  The report has filename/release context, not proof that it checked these exact bytes. This is offline linkage only.")
    else:
        lines.append("- Connector CheckJar: not supplied (unmeasured, not passing).")
    if observations and observations["observations"]:
        lines.append("- Connector gameplay: same-name, same-release observations, not tests of the native port.")
        for row in observations["observations"]:
            lines.append(f"  - {_literal(row.get('source', 'source unspecified'))} / {_literal(row.get('status', 'status unspecified'))}: "
                         f"{_literal(row['notes'])}")
    elif observations:
        lines.append("- Connector gameplay: no matching same-release observations in the supplied file.")
    else:
        lines.append("- Connector gameplay: no observations supplied.")
    lines += ["", "## Work in priority order", "", "P0: establish dependencies and resolve blockers. P1: implement and review APIs and behavior. P2: check assets, optional integrations, and gameplay.", ""]
    for row in plan["work"]:
        count = f" — {row['count']} reported" if row["count"] is not None else ""
        lines.append(f"- [ ] **{row['priority']} · {row['id']}** ({row['evidence']}{count}): {row['action']}")
        lines.extend(f"  - {_literal(example)}" for example in row["examples"][:4])
        if len(row["examples"]) > 4:
            lines.append(f"  - {len(row['examples']) - 4} more examples in JSON.")
    if not plan["work"]:
        lines.append("- No specific gaps identified by the inputs. Continue with verification below.")
    lines += ["", "## Verification required", "", *(f"- [ ] {step}" for step in plan["verification_required"])]
    return "\n".join(lines) + "\n"

"""Generate an isolated NeoForge 1.21.1 workspace, not a relabeled legacy mod."""

from dataclasses import dataclass
import gzip
import json
from pathlib import Path
import re
import shutil
import struct

from . import JAVA_VERSION, MINECRAFT_VERSION, MODDEV_VERSION, NEOFORGE_VERSION, SOURCE_VERSIONS
from .assets import ResourcePlan, plan_resources
from .audit import Audit, ModMetadata, render_audit
from .inputs import InputError, ModInput


MOD_ID = re.compile(r"[a-z][a-z0-9_]{1,63}")
MOD_VERSION = re.compile(r"[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")
ITEM_ID = re.compile(r"[a-z0-9_][a-z0-9_.-]{0,63}")
TEMPLATES = Path(__file__).with_name("templates")
PARTS = (
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "gradle/GRADLE-LICENSE.txt", "TEMPLATE_LICENSE.txt",
)


@dataclass
class PortOptions:
    output: Path
    mod_id: str | None = None
    name: str | None = None
    mod_version: str | None = None
    license: str | None = None
    plain_items: tuple[str, ...] = ()
    github_actions: bool = False


def _select_mod(result: Audit, override: str | None) -> tuple[ModMetadata, bool]:
    by_id = {}
    for mod in result.mods:
        if mod.mod_id:
            by_id.setdefault(mod.mod_id, mod)
    if len(by_id) > 1:
        matches = [mod for legacy_id, mod in by_id.items() if override and legacy_id.casefold() == override.casefold()]
        if len(matches) != 1:
            raise InputError("Multiple legacy mod IDs found: " + ", ".join(map(repr, sorted(by_id))) +
                             ". Choose one with --mod-id, or separate the sources before renaming it.")
        return matches[0], False
    return next(iter(by_id.values()), result.mods[0] if result.mods else ModMetadata("")), True


def _toml(value: str) -> str:
    # JSON escapes are compatible with TOML basic strings. Also escape DEL (invalid raw in TOML).
    return json.dumps(value, ensure_ascii=False).replace("\x7f", "\\u007f")


def _metadata(mod: ModMetadata, mod_id: str, name: str, version: str, license_name: str) -> str:
    lines = [
        'modLoader="javafml"', 'loaderVersion="[1,)"', f"license={_toml(license_name)}",
        "", "[[mods]]", f"modId={_toml(mod_id)}", f"version={_toml(version)}", f"displayName={_toml(name)}",
        f"description={_toml(mod.description or 'Porting workspace; gameplay may be incomplete.')}",
    ]
    if mod.authors:
        lines.append(f"authors={_toml(mod.authors)}")
    for dep, version_range in (("neoforge", f"[{NEOFORGE_VERSION},21.2)"), ("minecraft", f"[{MINECRAFT_VERSION}]")):
        lines += ["", f"[[dependencies.{mod_id}]]", f'modId="{dep}"', 'type="required"',
                  f"versionRange={_toml(version_range)}", 'ordering="NONE"', 'side="BOTH"']
    return "\n".join(lines) + "\n"


def _build_gradle(mod_id: str, package: str, version: str) -> str:
    return f'''plugins {{
    id 'java-library'
    id 'net.neoforged.moddev' version '{MODDEV_VERSION}'
}}

version = '{version}'
group = '{package}'
base {{ archivesName = '{mod_id}' }}
java.toolchain.languageVersion = JavaLanguageVersion.of({JAVA_VERSION})

tasks.withType(JavaCompile).configureEach {{
    options.encoding = 'UTF-8'
}}

neoForge {{
    version = '{NEOFORGE_VERSION}'
    runs {{
        client {{ client() }}
        server {{
            server()
            programArgument '--nogui'
        }}
    }}
    mods {{
        create('{mod_id}') {{ sourceSet(sourceSets.main) }}
    }}
}}

// Add modern dependency repositories and coordinates here after verifying 1.21.1 support.
'''


def _java(package: str, mod_id: str, items: list[str]) -> str:
    imports = "import net.neoforged.neoforge.registries.DeferredRegister;\n" if items else ""
    fields = "    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);\n" if items else ""
    registry = ""
    if items:
        registry = "        // Plain stubs only. Reimplement the legacy item behavior before release.\n"
        registry += "".join(f'        ITEMS.registerSimpleItem("{item}");\n' for item in items)
        registry += "        ITEMS.register(modBus);\n\n"
    return f'''package {package};

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
{imports}
@Mod(PortedMod.MOD_ID)
public final class PortedMod {{
    public static final String MOD_ID = "{mod_id}";
{fields}
    public PortedMod(IEventBus modBus) {{
{registry}        // TODO: port legacy gameplay, registries and event handlers. See PORTING.md.
    }}
}}
'''


def _code_block(value: str) -> str:
    fence = "`" * max(3, 1 + max(map(len, re.findall(r"`+", value)), default=0))
    return f"{fence}text\n{value}\n{fence}"


def _porting_report(result: Audit, mod_id: str, items: list[str], resources: ResourcePlan,
                    warnings: list[str], github_actions: bool) -> str:
    notes = [*warnings, *resources.warnings]
    details = ["Source language files converted to JSON for: " + (", ".join(resources.converted_locales) or "none") + ".",
               "Plain item stubs: " + (", ".join(items) or "none") + "."]
    if resources.copied_textures:
        details.append("Copied textures — " + "; ".join(resources.copied_textures) + ".")
    if resources.fallback_textures:
        details.append("Items using vanilla paper as a placeholder texture: " + ", ".join(resources.fallback_textures) + ".")
    details.extend("Review: " + warning for warning in notes)
    give = f" To try a stub in a fresh world with cheats, use `/give @s {mod_id}:{items[0]}`." if items else ""
    ci = ("\nThe generated GitHub Actions workflow runs `./gradlew build` with JDK 21 and uploads the built JAR. "
          "It does not push source, run legacy code, or publish a release. Commit only reviewed source and assets.\n"
          if github_actions else "")
    return f'''# Porting {mod_id} to Minecraft {MINECRAFT_VERSION} / NeoForge

This is a starting project, **not a finished port**. It includes generated metadata
and an entry point; it does not compile or package the legacy Java or classes.
Keep the original source checkout beside this project while porting it.

## What was generated

{_code_block(chr(10).join(details))}

Old language keys are kept unless they conflict. Review the new item keys and
copied assets. No legacy third-party dependencies are enabled automatically.

## Review and test

1. Verify that you have permission to port and redistribute the original code and assets.
2. Check the findings below and reproduce each gameplay feature in modern APIs.
   Verify 1.21.1 NeoForge versions of external dependencies before adding them.
3. With JDK {JAVA_VERSION}, run `./gradlew build` and `./gradlew runClient` (on Windows,
   use `gradlew.bat`).{give} Run `./gradlew runServer` as well to catch client-only imports.
4. Compare behavior on new test worlds first. Old worlds, numeric IDs, config files,
   and saved data are **not** migrated by this tool. Use backups for any save tests.
{ci}
## Legacy audit (whole input)

{_code_block(render_audit(result))}
'''


def generate_port(source: ModInput, result: Audit, options: PortOptions) -> Path:
    if not result.java_files:
        raise InputError("Porting requires readable Java source. Use inspect for compiled JARs, or obtain a source checkout/archive.")
    if result.source_version is None:
        raise InputError("Could not determine an exact Minecraft version. Verify it and pass --source-version {1.5.2,1.6.4,1.7.10}.")
    if result.source_version not in SOURCE_VERSIONS:
        raise InputError(f"Minecraft {result.source_version} is not a supported source version.")
    mod, single_mod = _select_mod(result, options.mod_id)
    mod_id = options.mod_id if options.mod_id is not None else mod.mod_id
    if not MOD_ID.fullmatch(mod_id) or mod_id in {"minecraft", "neoforge"}:
        raise InputError("Supply a valid --mod-id: [a-z][a-z0-9_]{1,63}; minecraft and neoforge are reserved.")
    items = sorted(set(options.plain_items))
    for item in items:
        if not ITEM_ID.fullmatch(item):
            raise InputError(f"Invalid --plain-item {item!r}: use a lowercase registry path without slashes.")

    output = options.output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError(f"Output already exists; choose a new directory: {output}")
    output = output.resolve()
    if not source.archive and output.is_relative_to(source.path):
        raise InputError("Output must be outside the input source tree.")

    warnings = []
    version = options.mod_version if options.mod_version is not None else mod.version
    if not MOD_VERSION.fullmatch(version):
        if options.mod_version is not None:
            raise InputError("Invalid --mod-version: use 1–64 letters, digits, dots, plus signs, hyphens or underscores (start alphanumeric).")
        version = "0.1.0-port"
        warnings.append("Used mod version 0.1.0-port; no usable legacy version was found.")
    name = options.name or mod.name or mod_id
    license_name = options.license or mod.license or "All Rights Reserved"
    if not (options.license or mod.license):
        warnings.append("Legacy license unknown; metadata defaults to All Rights Reserved. Verify the original license before sharing.")
    legacy_id = mod.mod_id or mod_id
    if mod.mod_id and mod_id != mod.mod_id:
        warnings.append(f"Mod ID changed from {mod.mod_id!r} to {mod_id!r}. Review registry IDs and any external references or save data.")

    resources = plan_resources(source, legacy_id, mod_id, items, allow_bare_lang=single_mod)
    package = f"mcmodup.generated.mod_{mod_id}"
    files = {
        ".gitignore": "/.gradle/\n/build/\n/run/\n/runs/\n/.idea/\n/.mcmodup/\n/references/\n",
        "settings.gradle": f"pluginManagement {{ repositories {{ gradlePluginPortal() }} }}\nrootProject.name = '{mod_id}'\n",
        "gradle.properties": "org.gradle.jvmargs=-Xmx2G\n",
        "build.gradle": _build_gradle(mod_id, package, version),
        "src/main/resources/META-INF/neoforge.mods.toml": _metadata(mod, mod_id, name, version, license_name),
        f"src/main/java/{package.replace('.', '/')}/PortedMod.java": _java(package, mod_id, items),
        "PORTING.md": _porting_report(result, mod_id, items, resources, warnings, options.github_actions),
    } | {f"src/main/resources/{path}": data for path, data in resources.files.items()}

    if options.github_actions:
        files[".github/workflows/build.yml"] = (TEMPLATES / "github-build.yml").read_text(encoding="utf-8")

    write_workspace(output, files)
    return output


def empty_test_structure(size: int = 1) -> bytes:
    """Minimal, empty cubic structure in named NBT for registry and gameplay checks."""
    nbt = (b"\x0a\x00\x00"                         # root compound
           b"\x03\x00\x0bDataVersion" + struct.pack(">i", 3955)
           + b"\x09\x00\x04size\x03" + struct.pack(">4i", 3, size, size, size)
           + b"\x09\x00\x07palette\x0a\x00\x00\x00\x01\x08\x00\x04Name\x00\x0dminecraft:air\x00"
           + b"\x09\x00\x06blocks\x0a\x00\x00\x00\x00"
           + b"\x09\x00\x08entities\x0a\x00\x00\x00\x00\x00")
    return gzip.compress(nbt, mtime=0)


def write_workspace(output: Path, files: dict[str, str | bytes]) -> None:
    # The mkdir is exclusive. Never overwrite an existing source or output project.
    output.mkdir(parents=True, exist_ok=False)
    try:
        for relative, data in files.items():
            path = output / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if isinstance(data, bytes):
                path.write_bytes(data)
            else:
                path.write_text(data, encoding="utf-8", newline="\n")
        for relative in PARTS:
            path = output / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(TEMPLATES / relative, path)
        (output / "gradlew").chmod(0o755)
    except BaseException:
        shutil.rmtree(output, ignore_errors=True)
        raise

"""Migrate simple translations and assets for explicitly selected plain-item stubs."""

from collections import defaultdict
from dataclasses import dataclass, field
import json
import re

from .inputs import InputError, ModInput


LOCALE_FILENAME = re.compile(r"([a-zA-Z]{2,3}_[a-zA-Z0-9]{2,8})\.lang")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


@dataclass
class ResourcePlan:
    files: dict[str, str | bytes] = field(default_factory=dict)
    copied_textures: list[str] = field(default_factory=list)
    fallback_textures: list[str] = field(default_factory=list)
    converted_locales: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def _json(data: dict) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _asset_files(source: ModInput, legacy_id: str, allow_bare_lang: bool) -> dict[str, list[str]]:
    matches = defaultdict(list)
    for path in source.files:
        parts = path.split("/")
        for i, part in enumerate(parts[:-2]):
            if part in {"assets", "mods"} and parts[i + 1].casefold() == legacy_id.casefold():
                matches["/".join(parts[i + 2:])].append(path)
                break
        else:
            # Older source trees sometimes load a bare lang directory with LanguageRegistry.
            if allow_bare_lang and tuple(parts[:-2]) in {(), ("resources",), ("src", "main", "resources")}:
                matches["/".join(parts[-2:])].append(path)
    return matches


def _read_lang(text: str) -> tuple[list[tuple[str, str]], int]:
    pairs = []
    skipped = 0
    continuation = False
    for line in text.splitlines():
        if not continuation and (not line.strip() or line.lstrip().startswith(("#", "!"))):
            continue
        key, sep, value = line.partition("=")
        if continuation or not sep or not key.strip() or "\\" in line:
            skipped += 1
            continuation = (len(line) - len(line.rstrip("\\"))) % 2 == 1
        else:
            pairs.append((key.strip(), value))
    return pairs, skipped


def plan_resources(source: ModInput, legacy_id: str, mod_id: str,
                   items: list[str], allow_bare_lang: bool) -> ResourcePlan:
    plan = ResourcePlan()
    assets = _asset_files(source, legacy_id, allow_bare_lang)
    locales: dict[str, dict[str, str]] = defaultdict(dict)
    conflicts: dict[str, set[str]] = defaultdict(set)
    for relative, paths in assets.items():
        if not relative.startswith("lang/") or not relative.endswith(".lang"):
            continue
        match = LOCALE_FILENAME.fullmatch(relative.removeprefix("lang/"))
        if not match:
            plan.warnings.append(f"Skipped unsupported locale filename: {', '.join(map(repr, paths))}.")
            continue
        locale = match[1].lower()
        for path in paths:
            try:
                pairs, skipped = _read_lang(source.text(path))
            except InputError as exc:
                plan.warnings.append(f"Could not migrate {path!r}: {exc}. Convert it to UTF-8 and review manually.")
                continue
            if skipped:
                plan.warnings.append(f"{path!r}: skipped {skipped} line(s) outside the simple key=value format.")
            if pairs:
                plan.converted_locales.append(locale)
            for key, value in pairs:
                if key in conflicts[locale]:
                    continue
                if key in locales[locale] and value != locales[locale][key]:
                    conflicts[locale].add(key)
                    del locales[locale][key]
                else:
                    locales[locale][key] = value
    for locale, keys in sorted(conflicts.items()):
        if keys:
            sample = ", ".join(map(repr, sorted(keys)[:4]))
            plan.warnings.append(f"{locale}: skipped {len(keys)} conflicting translation key(s), e.g. {sample}.")
    plan.converted_locales = sorted({locale for locale in plan.converted_locales if locales[locale]})

    prefix = f"assets/{mod_id}"
    for item in items:
        modern_key = f"item.{mod_id}.{item}"
        old_keys = {f"item.{item}.name", f"item.{legacy_id}.{item}.name"}
        for locale, translations in locales.items():
            if modern_key in translations:
                continue
            values = {translations[key] for key in old_keys if key in translations}
            if len(values) == 1 and modern_key not in conflicts[locale]:
                translations[modern_key] = values.pop()
            elif len(values) > 1:
                plan.warnings.append(f"{locale}: ambiguous legacy names for item {item!r}; review {modern_key!r}.")
        locales["en_us"].setdefault(modern_key, re.sub(r"[._-]+", " ", item).title())

        texture = _item_texture(source, assets, plan, prefix, mod_id, item)
        plan.files[f"{prefix}/models/item/{item}.json"] = _json({
            "parent": "minecraft:item/generated",
            "textures": {"layer0": texture},
        })
    for locale, translations in sorted(locales.items()):
        if translations:
            plan.files[f"{prefix}/lang/{locale}.json"] = _json(translations)
    return plan


def _item_texture(source: ModInput, assets: dict[str, list[str]], plan: ResourcePlan,
                  prefix: str, mod_id: str, item: str) -> str:
    paths = [*assets.get(f"textures/items/{item}.png", []), *assets.get(f"textures/item/{item}.png", [])]
    if len(paths) == 1:
        path = paths[0]
        try:
            if f"{path}.mcmeta" in source.files:
                raise InputError("animated/sidecar textures need manual review")
            texture = source.read(path, 8 * 1024 * 1024)
            if not texture.startswith(PNG_SIGNATURE):
                raise InputError("missing PNG signature")
            plan.files[f"{prefix}/textures/item/{item}.png"] = texture
            plan.copied_textures.append(f"{item}: {path!r}")
            return f"{mod_id}:item/{item}"
        except InputError as exc:
            plan.warnings.append(f"Could not copy {path!r}: {exc}.")
    elif paths:
        plan.warnings.append(f"Several textures match {item!r}; no texture copied: {', '.join(map(repr, paths))}.")
    plan.fallback_textures.append(item)
    return "minecraft:item/paper"

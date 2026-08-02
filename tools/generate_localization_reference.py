#!/usr/bin/env python3
"""Generate the canonical localization inventory and usage reference.

The generated JSON is deliberately data-first: translation agents can inspect every
default string, plural, and array together with Android formatting requirements and
the files that reference it. The companion Markdown document explains the workflow.
"""

from __future__ import annotations

import json
import re
import subprocess
import xml.etree.ElementTree as element_tree
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DOCS_DIRECTORY = REPOSITORY_ROOT / "docs"
MARKDOWN_OUTPUT = DOCS_DIRECTORY / "LOCALIZATION_REFERENCE.md"
JSON_OUTPUT = DOCS_DIRECTORY / "localization-resource-reference.json"
RESOURCE_TAGS = {"string", "plurals", "string-array", "integer-array"}
SOURCE_SUFFIXES = {".java", ".kt", ".xml"}
PRINTF_TOKEN = re.compile(
    r"%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT])?[a-zA-Z%]"
)
RESOURCE_CLASS_REFERENCE = re.compile(
    r"\b(?:[A-Za-z_][A-Za-z0-9_]*\.)*R\.(?:string|plurals|array|integer)\."
    r"([A-Za-z_][A-Za-z0-9_]*)"
)
RESOURCE_XML_REFERENCE = re.compile(
    r"@(?:string|plurals|array|integer)/([A-Za-z_][A-Za-z0-9_]*)"
)
TOOLS_NAMESPACE = "http://schemas.android.com/tools"


@dataclass(frozen=True)
class Locale:
    qualifier: str
    language: str
    scope: str


TARGET_LOCALES = (
    Locale("values-bg-rBG", "Bulgarian", "EU official language"),
    Locale("values-hr-rHR", "Croatian", "EU official language"),
    Locale("values-cs-rCZ", "Czech", "EU official language"),
    Locale("values-da-rDK", "Danish", "EU official language"),
    Locale("values-nl-rNL", "Dutch", "EU official language"),
    Locale("values-et-rEE", "Estonian", "EU official language"),
    Locale("values-fi-rFI", "Finnish", "EU official language"),
    Locale("values-fr-rFR", "French", "EU official language"),
    Locale("values-de-rDE", "German", "EU official language"),
    Locale("values-el-rGR", "Greek", "EU official language"),
    Locale("values-ga-rIE", "Irish", "EU official language"),
    Locale("values-hu-rHU", "Hungarian", "EU official language"),
    Locale("values-it-rIT", "Italian", "EU official language"),
    Locale("values-lv-rLV", "Latvian", "EU official language"),
    Locale("values-lt-rLT", "Lithuanian", "EU official language"),
    Locale("values-mt-rMT", "Maltese", "EU official language"),
    Locale("values-pl-rPL", "Polish", "EU official language"),
    Locale("values-pt-rPT", "Portuguese (Portugal)", "EU official language"),
    Locale("values-ro-rRO", "Romanian", "EU official language"),
    Locale("values-sk-rSK", "Slovak", "EU official language"),
    Locale("values-sl-rSI", "Slovenian", "EU official language"),
    Locale("values-es-rES", "Spanish", "EU official language"),
    Locale("values-sv-rSE", "Swedish", "EU official language"),
    Locale("values-ja-rJP", "Japanese", "Requested"),
    Locale("values-zh-rCN", "Chinese (Simplified)", "Requested"),
    Locale("values-zh-rTW", "Chinese (Traditional)", "Requested"),
    Locale("values-hi-rIN", "Hindi", "Requested"),
)


@dataclass(frozen=True)
class ResourceFile:
    path: Path
    module: str
    qualifier: str
    filename: str


def git_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=REPOSITORY_ROOT,
        check=True,
        capture_output=True,
        encoding="utf-8",
        text=True,
    )
    return [Path(path) for path in result.stdout.splitlines()]


def resource_file(relative_path: Path) -> ResourceFile | None:
    parts = relative_path.parts
    for index in range(len(parts) - 3):
        if parts[index : index + 3] != ("src", "main", "res"):
            continue
        qualifier = parts[index + 3]
        if not qualifier.startswith("values") or relative_path.suffix != ".xml":
            return None
        module = Path(*parts[:index]).as_posix()
        return ResourceFile(
            path=relative_path,
            module=module,
            qualifier=qualifier,
            filename=relative_path.name,
        )
    return None


def is_values_file(relative_path: Path) -> bool:
    return (info := resource_file(relative_path)) is not None and info.qualifier.startswith(
        "values"
    )


def read_resource_nodes(info: ResourceFile) -> list[element_tree.Element]:
    root = element_tree.parse(REPOSITORY_ROOT / info.path).getroot()
    return [
        node
        for node in root
        if node.tag in RESOURCE_TAGS and node.get("name") is not None
    ]


def attribute_name(name: str) -> str:
    if name == f"{{{TOOLS_NAMESPACE}}}ignore":
        return "tools:ignore"
    return name


def node_text(node: element_tree.Element) -> str:
    return "".join(node.itertext()).strip()


def node_value(node: element_tree.Element) -> str | list[dict[str, str]]:
    if node.tag == "string":
        return node_text(node)
    return [
        {
            "quantity": item.get("quantity", ""),
            "value": node_text(item),
        }
        for item in node
        if item.tag == "item"
    ]


def value_texts(value: str | list[dict[str, str]]) -> list[str]:
    if isinstance(value, str):
        return [value]
    return [item["value"] for item in value]


def source_usages(files: list[Path]) -> dict[str, list[str]]:
    usages: dict[str, set[str]] = defaultdict(set)
    for relative_path in files:
        if relative_path.suffix not in SOURCE_SUFFIXES:
            continue
        content = (REPOSITORY_ROOT / relative_path).read_text(encoding="utf-8")
        relative_name = relative_path.as_posix()
        for name in RESOURCE_XML_REFERENCE.findall(content):
            usages[name].add(relative_name)
        if not is_values_file(relative_path):
            for name in RESOURCE_CLASS_REFERENCE.findall(content):
                usages[name].add(relative_name)
    return {name: sorted(paths) for name, paths in usages.items()}


def locale_index(
    infos: list[ResourceFile],
) -> dict[tuple[str, str, str, str], list[str]]:
    index: dict[tuple[str, str, str, str], list[str]] = defaultdict(list)
    for info in infos:
        if info.qualifier == "values":
            continue
        for node in read_resource_nodes(info):
            if node.get("translatable") == "false":
                continue
            key = (info.module, info.filename, node.tag, node.get("name", ""))
            index[key].append(info.qualifier)
    return {key: sorted(qualifiers) for key, qualifiers in index.items()}


def resource_records(
    default_infos: list[ResourceFile],
    usage_index: dict[str, list[str]],
    existing_locale_index: dict[tuple[str, str, str, str], list[str]],
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for info in sorted(default_infos, key=lambda item: item.path.as_posix()):
        for node in read_resource_nodes(info):
            name = node.get("name", "")
            value = node_value(node)
            text_values = value_texts(value)
            records.append(
                {
                    "module": info.module,
                    "default_path": info.path.as_posix(),
                    "resource_file": info.filename,
                    "name": name,
                    "kind": node.tag,
                    "translatable": node.get("translatable") != "false",
                    "formatted": node.get("formatted", "true") != "false",
                    "attributes": {
                        attribute_name(key): value
                        for key, value in sorted(node.attrib.items())
                        if key != "name"
                    },
                    "value": value,
                    "format_tokens": (
                        []
                        if node.get("formatted") == "false"
                        else sorted(
                            {
                                token
                                for text in text_values
                                for token in PRINTF_TOKEN.findall(text)
                                if token != "%%"
                            }
                        )
                    ),
                    "usage_files": usage_index.get(name, []),
                    "existing_locale_qualifiers": existing_locale_index.get(
                        (info.module, info.filename, node.tag, name),
                        [],
                    ),
                }
            )
    return records


def markdown_document(
    records: list[dict[str, Any]],
    existing_locale_counts: Counter[str],
) -> str:
    module_counts = Counter(record["module"] for record in records)
    translatable_count = sum(record["translatable"] for record in records)
    target_rows = "\n".join(
        f"| {locale.qualifier} | {locale.language} | {locale.scope} |"
        for locale in TARGET_LOCALES
    )
    module_rows = "\n".join(
        f"| {module} | {count} |"
        for module, count in sorted(module_counts.items())
    )
    existing_rows = "\n".join(
        f"| {qualifier} | {count} |"
        for qualifier, count in sorted(existing_locale_counts.items())
    )
    return f"""# Localization Reference

This document and localization-resource-reference.json are generated by
tools/generate_localization_reference.py. Regenerate both after adding, deleting,
or moving default resources.

## Scope

The canonical source contains **{len(records)}** string-like Android resources
(strings, plurals, string arrays, and integer arrays), of which
**{translatable_count}** are user-facing and eligible for translation. English is
the default resource set and is not duplicated as a locale bucket.

| Qualifier | Language | Scope |
| --- | --- | --- |
{target_rows}

## Translation contract

1. Use tools/prepare_locale_resources.py <qualifier> to create or refresh the
   locale's complete resource schema before translating.
2. Translate every resource whose translatable field is true. Do not copy resources
   marked translatable="false" into locale directories; Android falls back to the
   default values definition for them.
3. Preserve resource names, XML structure, formatted and tools:ignore attributes,
   positional placeholders (for example %1$s and %2$d), escaped apostrophes,
   markup, and literal %% characters.
4. Translate plural values using Android quantity rules for the target locale.
   Keep the same resource name and any supplied quantity keys; add locale-required
   quantities when Android expects them.
5. Retain reviewed existing translations where they are still accurate. The JSON
   inventory records which locale qualifiers already define each resource.
6. Run tools/validate_locale_resources.py <qualifier> before handing the locale
   back. It checks XML, resource parity, attributes, and format tokens.
7. Do not change Kotlin or Java call sites while translating. The usage_files field
   is a usage map for context only; a resource name may appear in more than one
   module.

## Default resource inventory

| Module | Resources |
| --- | ---: |
{module_rows}

## Current localization coverage

Counts are localizable, user-facing resource definitions across all modules, not a
translation-quality rating. The target locale qualifiers listed above are maintained
as complete translations; other locale buckets may remain partial.

| Qualifier | Localizable resource definitions |
| --- | ---: |
{existing_rows}

## JSON reference schema

Each record includes the default XML path, resource name and kind, text or plural
items, formatting tokens, attributes, direct source/XML usage files, and existing
localized qualifiers. Translation agents should read the JSON together with the
canonical default XML file whenever a value contains XML markup or formatted text.
"""


def main() -> None:
    files = git_files()
    infos = [info for path in files if (info := resource_file(path)) is not None]
    default_infos = [info for info in infos if info.qualifier == "values"]
    records = resource_records(
        default_infos=default_infos,
        usage_index=source_usages(files),
        existing_locale_index=locale_index(infos),
    )
    existing_locale_counts = Counter(
        info.qualifier
        for info in infos
        for node in read_resource_nodes(info)
        if info.qualifier != "values" and node.get("translatable") != "false"
    )
    payload = {
        "schema_version": 1,
        "target_locales": [
            {
                "qualifier": locale.qualifier,
                "language": locale.language,
                "scope": locale.scope,
            }
            for locale in TARGET_LOCALES
        ],
        "resources": records,
    }
    DOCS_DIRECTORY.mkdir(parents=True, exist_ok=True)
    JSON_OUTPUT.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    MARKDOWN_OUTPUT.write_text(
        markdown_document(records, existing_locale_counts),
        encoding="utf-8",
    )
    print(f"Wrote {len(records)} resource records to {JSON_OUTPUT.relative_to(REPOSITORY_ROOT)}")
    print(f"Wrote {MARKDOWN_OUTPUT.relative_to(REPOSITORY_ROOT)}")


if __name__ == "__main__":
    main()

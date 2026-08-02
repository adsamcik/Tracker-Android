#!/usr/bin/env python3
"""Validate one Android locale against the default resource schema."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as element_tree
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RESOURCE_TAGS = {"string", "plurals", "string-array", "integer-array"}
VALID_QUANTITIES = {"zero", "one", "two", "few", "many", "other"}
PRINTF_TOKEN = re.compile(
    r"%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT])?[a-zA-Z%]"
)


@dataclass(frozen=True)
class ResourceFile:
    default_path: Path
    locale_path: Path


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


def resource_files(qualifier: str) -> list[ResourceFile]:
    files: list[ResourceFile] = []
    for path in git_files():
        parts = path.parts
        for index in range(len(parts) - 3):
            if parts[index : index + 4] != ("src", "main", "res", "values"):
                continue
            if path.suffix != ".xml":
                continue
            locale_parts = list(parts)
            locale_parts[index + 3] = qualifier
            files.append(
                ResourceFile(
                    default_path=path,
                    locale_path=Path(*locale_parts),
                )
            )
            break
    return sorted(files, key=lambda file: file.default_path.as_posix())


def resources(path: Path) -> dict[tuple[str, str], element_tree.Element]:
    root = element_tree.parse(path).getroot()
    result: dict[tuple[str, str], element_tree.Element] = {}
    for node in root:
        if node.tag not in RESOURCE_TAGS or (name := node.get("name")) is None:
            continue
        key = (node.tag, name)
        if key in result:
            raise ValueError(f"Duplicate resource {node.tag} {name} in {path}")
        result[key] = node
    return result


def text(node: element_tree.Element) -> str:
    return "".join(node.itertext()).strip()


def format_tokens(node: element_tree.Element) -> Counter[str]:
    if node.get("formatted") == "false":
        return Counter()
    return Counter(
        token
        for item in node.iter()
        for token in PRINTF_TOKEN.findall(item.text or "")
        if token != "%%"
    )


def resource_content(node: element_tree.Element) -> tuple[object, ...]:
    return (
        node.tag,
        tuple(sorted(node.attrib.items())),
        text(node),
        tuple(
            (
                child.tag,
                tuple(sorted(child.attrib.items())),
                text(child),
            )
            for child in node
            if isinstance(child.tag, str)
        ),
    )


def plural_items(node: element_tree.Element) -> dict[str, element_tree.Element]:
    result: dict[str, element_tree.Element] = {}
    for item in node:
        if item.tag != "item":
            continue
        quantity = item.get("quantity")
        if quantity is None:
            raise ValueError(f"Plural item without quantity in {node.get('name')}")
        if quantity not in VALID_QUANTITIES:
            raise ValueError(
                f"Invalid plural quantity {quantity} in {node.get('name')}"
            )
        if quantity in result:
            raise ValueError(
                f"Duplicate plural quantity {quantity} in {node.get('name')}"
            )
        result[quantity] = item
    return result


def errors_for_file(resource_file: ResourceFile) -> tuple[list[str], int, int]:
    default_path = REPOSITORY_ROOT / resource_file.default_path
    locale_path = REPOSITORY_ROOT / resource_file.locale_path
    relative_locale = resource_file.locale_path.as_posix()
    if not locale_path.exists():
        return ([f"{relative_locale}: missing locale resource file"], 0, 0)

    default_nodes = resources(default_path)
    locale_nodes = resources(locale_path)
    errors: list[str] = []
    english_matches = 0
    translatable_nodes = 0

    missing = sorted(default_nodes.keys() - locale_nodes.keys())
    unexpected = sorted(locale_nodes.keys() - default_nodes.keys())
    for kind, name in missing:
        errors.append(f"{relative_locale}: missing {kind} {name}")
    for kind, name in unexpected:
        errors.append(f"{relative_locale}: unexpected {kind} {name}")

    for key in sorted(default_nodes.keys() & locale_nodes.keys()):
        default = default_nodes[key]
        locale = locale_nodes[key]
        kind, name = key
        if default.attrib != locale.attrib:
            errors.append(f"{relative_locale}: attributes changed for {kind} {name}")

        if default.tag in {"plurals", "string-array", "integer-array"}:
            default_items = plural_items(default) if default.tag == "plurals" else {
                str(index): item for index, item in enumerate(default)
            }
            locale_items = plural_items(locale) if locale.tag == "plurals" else {
                str(index): item for index, item in enumerate(locale)
            }
            missing_items = sorted(default_items.keys() - locale_items.keys())
            for item_key in missing_items:
                errors.append(
                    f"{relative_locale}: missing item {item_key} in {kind} {name}"
                )
            if default.tag != "plurals" and set(default_items) != set(locale_items):
                errors.append(
                    f"{relative_locale}: item structure changed for {kind} {name}"
                )
            for item_key, locale_item in locale_items.items():
                source_item = default_items.get(
                    item_key,
                    default_items.get("other"),
                )
                if source_item is None:
                    continue
                if format_tokens(source_item) != format_tokens(locale_item):
                    errors.append(
                        f"{relative_locale}: format tokens changed for "
                        f"{kind} {name} item {item_key}"
                    )
        elif format_tokens(default) != format_tokens(locale):
            errors.append(
                f"{relative_locale}: format tokens changed for {kind} {name}"
            )

        if default.get("translatable") == "false":
            if resource_content(default) != resource_content(locale):
                errors.append(
                    f"{relative_locale}: translatable=false value changed for "
                    f"{kind} {name}"
                )
        else:
            translatable_nodes += 1
            if text(default) == text(locale) and text(default):
                english_matches += 1

    return errors, translatable_nodes, english_matches


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate one locale resource schema and formatting contract."
    )
    parser.add_argument(
        "qualifier",
        help="Android values qualifier, for example values-bg-rBG",
    )
    args = parser.parse_args()
    if not args.qualifier.startswith("values-"):
        parser.error("qualifier must begin with values-")

    errors: list[str] = []
    translatable_nodes = 0
    english_matches = 0
    for resource_file in resource_files(args.qualifier):
        try:
            file_errors, file_translatable, file_english = errors_for_file(
                resource_file
            )
        except (element_tree.ParseError, ValueError) as error:
            file_errors = [str(error)]
            file_translatable = 0
            file_english = 0
        errors.extend(file_errors)
        translatable_nodes += file_translatable
        english_matches += file_english

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1

    print(
        f"{args.qualifier}: validated {translatable_nodes} translatable resources "
        f"across {len(resource_files(args.qualifier))} files."
    )
    if english_matches:
        print(
            f"Warning: {english_matches} translatable values match the default text; "
            "review product names, abbreviations, and accidental English placeholders."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())

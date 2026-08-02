#!/usr/bin/env python3
"""Create locale-only Android resource schemas from default values XML.

Only translatable strings, plurals, and arrays belong in a locale directory.
Existing localized content is retained by resource key while the current default
schema supplies resource and root attributes.
"""

from __future__ import annotations

import argparse
import copy
import subprocess
import xml.etree.ElementTree as element_tree
from dataclasses import dataclass
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RESOURCE_TAGS = {"string", "plurals", "string-array", "integer-array"}
TOOLS_NAMESPACE = "http://schemas.android.com/tools"
element_tree.register_namespace("tools", TOOLS_NAMESPACE)


@dataclass(frozen=True)
class ResourceFile:
    default_path: Path
    target_path: Path


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


def default_resource_mappings(qualifier: str) -> list[ResourceFile]:
    """Map tracked default values XML files to their locale-file counterparts."""
    files: list[ResourceFile] = []
    for path in git_files():
        parts = path.parts
        for index in range(len(parts) - 3):
            if parts[index : index + 4] != ("src", "main", "res", "values"):
                continue
            if path.suffix != ".xml":
                continue
            target_parts = list(parts)
            target_parts[index + 3] = qualifier
            files.append(
                ResourceFile(
                    default_path=path,
                    target_path=Path(*target_parts),
                )
            )
            break
    return sorted(files, key=lambda file: file.default_path.as_posix())


def xml_parser() -> element_tree.XMLParser:
    return element_tree.XMLParser(
        target=element_tree.TreeBuilder(insert_comments=True)
    )


def resource_key(node: element_tree.Element) -> tuple[str, str] | None:
    if not isinstance(node.tag, str) or node.tag not in RESOURCE_TAGS:
        return None
    name = node.get("name")
    if name is None:
        return None
    return (node.tag, name)


def is_translatable_resource(node: element_tree.Element) -> bool:
    return (
        resource_key(node) is not None
        and node.get("translatable") != "false"
    )


def default_has_translatable_resources(default_path: Path) -> bool:
    root = element_tree.parse(default_path, parser=xml_parser()).getroot()
    return any(is_translatable_resource(node) for node in root)


def default_resource_files(qualifier: str) -> list[ResourceFile]:
    """Return only default XML files that contain user-facing resources."""
    return [
        resource_file
        for resource_file in default_resource_mappings(qualifier)
        if default_has_translatable_resources(
            REPOSITORY_ROOT / resource_file.default_path
        )
    ]


def stale_static_resource_files(qualifier: str) -> list[ResourceFile]:
    """Return mapped locale files whose tracked default has no localizable nodes."""
    return [
        resource_file
        for resource_file in default_resource_mappings(qualifier)
        if not default_has_translatable_resources(
            REPOSITORY_ROOT / resource_file.default_path
        )
    ]


def existing_resources(
    target_path: Path,
) -> dict[tuple[str, str], element_tree.Element]:
    """Index existing localized nodes, including stale attributes for normalization."""
    if not target_path.exists():
        return {}

    root = element_tree.parse(target_path, parser=xml_parser()).getroot()
    result: dict[tuple[str, str], element_tree.Element] = {}
    for node in root:
        key = resource_key(node)
        if key is None:
            continue
        if key in result:
            kind, name = key
            raise ValueError(
                f"Duplicate localized resource {kind} {name} in {target_path}"
            )
        result[key] = copy.deepcopy(node)
    return result


def localized_node(
    default: element_tree.Element,
    existing: element_tree.Element | None,
) -> element_tree.Element:
    """Retain localized content and markup while normalizing default attributes."""
    localized = (
        copy.deepcopy(existing)
        if existing is not None
        else copy.deepcopy(default)
    )
    localized.attrib.clear()
    localized.attrib.update(default.attrib)
    return localized


def build_target_tree(
    default_path: Path,
    target_path: Path,
) -> tuple[element_tree.ElementTree, int, int]:
    default_tree = element_tree.parse(default_path, parser=xml_parser())
    default_root = default_tree.getroot()
    existing = existing_resources(target_path)
    target_root = element_tree.Element(
        default_root.tag,
        copy.deepcopy(default_root.attrib),
    )
    retained = 0
    placeholders = 0

    for node in default_root:
        if not is_translatable_resource(node):
            continue
        key = resource_key(node)
        if key is None:
            continue
        existing_node = existing.get(key)
        target_root.append(localized_node(node, existing_node))
        if existing_node is None:
            placeholders += 1
        else:
            retained += 1

    return element_tree.ElementTree(target_root), retained, placeholders


def write_target(resource_file: ResourceFile) -> tuple[int, int]:
    default_path = REPOSITORY_ROOT / resource_file.default_path
    target_path = REPOSITORY_ROOT / resource_file.target_path
    tree, retained, placeholders = build_target_tree(default_path, target_path)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    element_tree.indent(tree, space="    ")
    tree.write(target_path, encoding="utf-8", xml_declaration=True)
    return retained, placeholders


def prune_stale_static_files(qualifier: str) -> list[Path]:
    """Remove only mapped static locale XML files when explicitly requested."""
    pruned: list[Path] = []
    for resource_file in stale_static_resource_files(qualifier):
        target_path = REPOSITORY_ROOT / resource_file.target_path
        if target_path.is_file():
            target_path.unlink()
            pruned.append(resource_file.target_path)
    return pruned


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create or refresh one Android locale resource schema."
    )
    parser.add_argument(
        "qualifier",
        help="Android values qualifier, for example values-bg-rBG",
    )
    parser.add_argument(
        "--prune",
        action="store_true",
        help=(
            "remove mapped locale XML files whose tracked default values file "
            "contains no translatable resource nodes"
        ),
    )
    args = parser.parse_args()
    if not args.qualifier.startswith("values-"):
        parser.error("qualifier must begin with values-")

    files = default_resource_files(args.qualifier)
    retained = 0
    placeholders = 0
    for resource_file in files:
        file_retained, file_placeholders = write_target(resource_file)
        retained += file_retained
        placeholders += file_placeholders

    pruned = prune_stale_static_files(args.qualifier) if args.prune else []
    print(
        f"Prepared {len(files)} localizable files for {args.qualifier}: "
        f"{retained} existing translations retained, "
        f"{placeholders} English placeholders to translate."
    )
    if pruned:
        print(f"Pruned {len(pruned)} mapped static locale resource files.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Create a complete locale resource schema from the default Android values files.

Existing localized values are retained by resource name. Missing translatable values
start as English placeholders and must be replaced before a locale is committed.
Resources marked translatable="false" always retain the default value.
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


def default_resource_files(qualifier: str) -> list[ResourceFile]:
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


def existing_resources(target_path: Path) -> dict[tuple[str, str], element_tree.Element]:
    if not target_path.exists():
        return {}
    root = element_tree.parse(target_path, parser=xml_parser()).getroot()
    return {
        key: copy.deepcopy(node)
        for node in root
        if (key := resource_key(node)) is not None
    }


def is_translatable(node: element_tree.Element) -> bool:
    return node.get("translatable") != "false"


def build_target_tree(
    default_path: Path,
    target_path: Path,
) -> tuple[element_tree.ElementTree, int, int]:
    default_tree = element_tree.parse(default_path, parser=xml_parser())
    default_root = default_tree.getroot()
    existing = existing_resources(target_path)
    retained = 0
    placeholders = 0

    for index, node in enumerate(default_root):
        key = resource_key(node)
        if key is None:
            continue
        if is_translatable(node) and key in existing:
            default_root[index] = existing[key]
            retained += 1
        elif is_translatable(node):
            placeholders += 1

    return default_tree, retained, placeholders


def write_target(
    resource_file: ResourceFile,
) -> tuple[int, int]:
    default_path = REPOSITORY_ROOT / resource_file.default_path
    target_path = REPOSITORY_ROOT / resource_file.target_path
    tree, retained, placeholders = build_target_tree(default_path, target_path)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    element_tree.indent(tree, space="    ")
    tree.write(target_path, encoding="utf-8", xml_declaration=True)
    return retained, placeholders


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create or refresh one Android locale resource schema."
    )
    parser.add_argument(
        "qualifier",
        help="Android values qualifier, for example values-bg-rBG",
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

    print(
        f"Prepared {len(files)} files for {args.qualifier}: "
        f"{retained} existing translations retained, "
        f"{placeholders} English placeholders to translate."
    )


if __name__ == "__main__":
    main()

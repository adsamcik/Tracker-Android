#!/usr/bin/env python3
"""Fail when a default string resource is no longer referenced in the repository.

Android lint catches most unused resources in the app graph. This lightweight check
also runs before Gradle is configured, and follows references between values files
so a stale string cannot silently persist across every translation.

Run with: python3 tools/check_unused_string_resources.py
"""

from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as element_tree
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RESOURCE_TAGS = {"string", "plurals", "string-array", "integer-array"}
SOURCE_SUFFIXES = {".gradle", ".java", ".kt", ".pro", ".xml"}
RESOURCE_CLASS_REFERENCE = re.compile(
    r"\b(?:[A-Za-z_][A-Za-z0-9_]*\.)*R\.(?:string|plurals|array|integer)\."
    r"([A-Za-z_][A-Za-z0-9_]*)"
)
RESOURCE_XML_REFERENCE = re.compile(
    r"@(?:string|plurals|array|integer)/([A-Za-z_][A-Za-z0-9_]*)"
)


@dataclass(frozen=True)
class Resource:
    path: Path
    kind: str
    name: str


def tracked_source_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=REPOSITORY_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [
        REPOSITORY_ROOT / relative_path
        for relative_path in result.stdout.splitlines()
        if Path(relative_path).suffix in SOURCE_SUFFIXES
    ]


def is_values_file(path: Path) -> bool:
    parts = path.relative_to(REPOSITORY_ROOT).parts
    return any(
        parts[index] == "res" and parts[index + 1].startswith("values")
        for index in range(len(parts) - 1)
    )


def is_default_values_file(path: Path) -> bool:
    parts = path.relative_to(REPOSITORY_ROOT).parts
    return any(
        parts[index : index + 4] == ("src", "main", "res", "values")
        for index in range(len(parts) - 3)
    )


def default_resources(files: Iterable[Path]) -> list[Resource]:
    resources: list[Resource] = []
    for path in files:
        if not is_default_values_file(path):
            continue
        try:
            root = element_tree.parse(path).getroot()
        except element_tree.ParseError as error:
            relative_path = path.relative_to(REPOSITORY_ROOT)
            raise RuntimeError(f"Could not parse {relative_path}") from error
        for node in root:
            if node.tag in RESOURCE_TAGS and (name := node.get("name")):
                resources.append(Resource(path, node.tag, name))
    return resources


def referenced_resource_names(files: Iterable[Path]) -> set[str]:
    names: set[str] = set()
    for path in files:
        content = path.read_text(encoding="utf-8")
        names.update(RESOURCE_XML_REFERENCE.findall(content))
        if not is_values_file(path):
            names.update(RESOURCE_CLASS_REFERENCE.findall(content))
    return names


def main() -> int:
    files = tracked_source_files()
    resources = default_resources(files)
    references = referenced_resource_names(files)
    unused = sorted(
        (resource for resource in resources if resource.name not in references),
        key=lambda resource: (resource.path, resource.name),
    )

    if not unused:
        print(f"Checked {len(resources)} default string resources: all are referenced.")
        return 0

    print("Unused default string resources:")
    for resource in unused:
        relative_path = resource.path.relative_to(REPOSITORY_ROOT)
        print(f"  {relative_path}: {resource.kind} {resource.name}")
    print(
        "Remove the resource from its default and localized values files, or add a "
        "tools:keep reference when it is reached through a dynamic lookup."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())

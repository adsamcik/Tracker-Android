#!/usr/bin/env python3
"""Reduce Gradle verification metadata to the reviewed release-risk closure."""

from __future__ import annotations

import argparse
import io
import json
import re
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Iterable, Mapping

NAMESPACE = "https://schema.gradle.org/dependency-verification"
XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
NS = {"v": NAMESPACE}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
TRACEBOX_GROUP = "io.github.tracebox"
TRACEBOX_REQUIRED_MODULES = frozenset(
    {"tracebox", "tracebox-native", "tracebox-ui-compose"}
)

# Gradle dependency verification is global. These trust rules intentionally leave only
# the reviewed non-central, native-bearing, and release-tool coordinates untrusted, so
# their retained checksums remain mandatory in strict mode without locking the full graph.
TRUST_RULES = (
    {
        "group": (
            r"^(?!(io[.]github[.]tracebox|org[.]maplibre[.]compose|"
            r"org[.]maplibre[.]gl|androidx[.]graphics|androidx[.]datastore|"
            r"com[.]android[.]tools[.]build)$).+$"
        ),
        "regex": "true",
        "reason": "Outside the selective release-risk verification policy",
    },
    {
        "group": "org[.]maplibre[.]compose",
        "name": r"^(?!(maplibre-compose|maplibre-compose-android)$).+$",
        "regex": "true",
        "reason": "Only the packaged MapLibre Compose inputs are release-critical",
    },
    {
        "group": "org[.]maplibre[.]gl",
        "name": r"^(?!android-sdk$).+$",
        "regex": "true",
        "reason": "Only the default packaged MapLibre native SDK is release-critical",
    },
    {
        "group": "androidx[.]graphics",
        "name": r"^(?!graphics-path$).+$",
        "regex": "true",
        "reason": "Only graphics-path contributes a packaged native library",
    },
    {
        "group": "androidx[.]datastore",
        "name": r"^(?!datastore-core-android$).+$",
        "regex": "true",
        "reason": "Only datastore-core-android contributes a packaged native library",
    },
    {
        "group": "com[.]android[.]tools[.]build",
        "name": r"^(?!bundletool$).+$",
        "regex": "true",
        "reason": "Only bundletool directly creates the representative APK set",
    },
)


class SelectiveVerificationError(RuntimeError):
    """Raised when generated metadata cannot support the reviewed policy."""


def parse_coordinate(value: str, label: str) -> tuple[str, str, str]:
    parts = value.split(":")
    if len(parts) != 3 or not all(parts):
        raise SelectiveVerificationError(f"{label} is not a fixed group:name:version coordinate")
    if "SNAPSHOT" in parts[2].upper() or "+" in parts[2]:
        raise SelectiveVerificationError(f"{label} is not a fixed release coordinate: {value}")
    return parts[0], parts[1], parts[2]


def expected_coordinates(release_inputs: Mapping[str, Any]) -> set[tuple[str, str, str]]:
    tracebox_version = str(release_inputs["tracebox"]["version"])
    if tracebox_version != "0.1.0-alpha.3" or "SNAPSHOT" in tracebox_version.upper():
        raise SelectiveVerificationError("Tracebox must remain fixed at 0.1.0-alpha.3")

    expected = {
        (TRACEBOX_GROUP, module, tracebox_version)
        for module in TRACEBOX_REQUIRED_MODULES
    }
    expected.add(
        parse_coordinate(
            str(release_inputs["maplibre"]["composeCoordinate"]),
            "maplibre.composeCoordinate",
        )
    )
    compose_group, _, compose_version = parse_coordinate(
        str(release_inputs["maplibre"]["composeCoordinate"]),
        "maplibre.composeCoordinate",
    )
    expected.add((compose_group, "maplibre-compose-android", compose_version))
    expected.add(
        parse_coordinate(
            str(release_inputs["maplibre"]["resolvedAndroidCoordinate"]),
            "maplibre.resolvedAndroidCoordinate",
        )
    )
    expected.add(
        parse_coordinate(
            str(release_inputs["tooling"]["bundletoolCoordinate"]),
            "tooling.bundletoolCoordinate",
        )
    )

    for native in release_inputs["nativeLibraries"]:
        coordinate = str(native["coordinate"])
        if coordinate.startswith("vendored:") or coordinate.startswith(TRACEBOX_GROUP + ":"):
            continue
        if coordinate.startswith("androidx.graphics:") or coordinate.startswith("androidx.datastore:"):
            expected.add(parse_coordinate(coordinate, f"nativeLibraries[{native['name']}]"))
    return expected


def component_coordinate(component: ET.Element) -> tuple[str, str, str]:
    return (
        component.attrib.get("group", ""),
        component.attrib.get("name", ""),
        component.attrib.get("version", ""),
    )


def sha256_artifacts(components: Iterable[ET.Element]) -> dict[str, set[str]]:
    artifacts: dict[str, set[str]] = {}
    for component in components:
        for artifact in component.findall("v:artifact", NS):
            name = artifact.attrib.get("name", "")
            values = {
                checksum.attrib.get("value", "")
                for checksum in artifact.findall("v:sha256", NS)
            }
            if not values or any(not SHA256_RE.fullmatch(value) for value in values):
                coordinate = ":".join(component_coordinate(component))
                raise SelectiveVerificationError(
                    f"{coordinate}:{name} has no valid SHA-256 checksum"
                )
            artifacts.setdefault(name, set()).update(values)
    return artifacts


def validate_tracebox_digests(
    release_inputs: Mapping[str, Any], artifacts: Mapping[str, set[str]]
) -> None:
    for name, expected_digest in release_inputs["tracebox"]["artifactSha256"].items():
        if artifacts.get(name) != {expected_digest}:
            found = ", ".join(sorted(artifacts.get(name, set()))) or "missing"
            raise SelectiveVerificationError(
                f"Tracebox digest mismatch for {name}: expected {expected_digest}, found {found}"
            )


def select_metadata(
    metadata: Path, release_inputs_path: Path, *, check: bool = False
) -> tuple[int, int]:
    release_inputs = json.loads(release_inputs_path.read_text(encoding="utf-8"))
    expected = expected_coordinates(release_inputs)

    ET.register_namespace("", NAMESPACE)
    ET.register_namespace("xsi", XSI_NAMESPACE)
    tree = ET.parse(metadata)
    root = tree.getroot()
    configuration = root.find("v:configuration", NS)
    components_element = root.find("v:components", NS)
    if configuration is None or components_element is None:
        raise SelectiveVerificationError("verification metadata is missing configuration/components")

    generated = list(components_element.findall("v:component", NS))
    tracebox_version = str(release_inputs["tracebox"]["version"])
    selected: list[ET.Element] = []
    for component in generated:
        coordinate = component_coordinate(component)
        group, _, version = coordinate
        if group == TRACEBOX_GROUP:
            if version != tracebox_version:
                raise SelectiveVerificationError(
                    "generated metadata contains an unexpected Tracebox coordinate: "
                    + ":".join(coordinate)
                )
            selected.append(component)
        elif coordinate in expected:
            selected.append(component)

    selected_coordinates = {component_coordinate(component) for component in selected}
    missing = sorted(expected - selected_coordinates)
    if missing:
        raise SelectiveVerificationError(
            "generated metadata is missing reviewed coordinates: "
            + ", ".join(":".join(coordinate) for coordinate in missing)
        )

    artifacts = sha256_artifacts(selected)
    validate_tracebox_digests(release_inputs, artifacts)
    for component in selected:
        for checksum in component.findall("v:artifact/v:sha256", NS):
            checksum.set("origin", "Reviewed release graph")

    existing_trust = configuration.find("v:trusted-artifacts", NS)
    if existing_trust is not None:
        configuration.remove(existing_trust)
    trusted_artifacts = ET.Element(f"{{{NAMESPACE}}}trusted-artifacts")
    for attributes in TRUST_RULES:
        ET.SubElement(trusted_artifacts, f"{{{NAMESPACE}}}trust", attributes)
    insert_at = len(configuration)
    verify_signatures = configuration.find("v:verify-signatures", NS)
    if verify_signatures is not None:
        insert_at = list(configuration).index(verify_signatures) + 1
    configuration.insert(insert_at, trusted_artifacts)

    for component in generated:
        components_element.remove(component)
    for component in sorted(selected, key=component_coordinate):
        components_element.append(component)

    ET.indent(tree, space="   ")
    rendered = io.BytesIO()
    tree.write(rendered, encoding="utf-8", xml_declaration=True)
    rendered_bytes = rendered.getvalue()
    if check:
        if metadata.read_bytes() != rendered_bytes:
            raise SelectiveVerificationError(
                "verification metadata does not match the reviewed selective policy; "
                "regenerate, reduce, and review it"
            )
        return len(generated), len(selected)

    with tempfile.NamedTemporaryFile(
        "wb", prefix=metadata.name + ".", suffix=".tmp", dir=metadata.parent, delete=False
    ) as temporary:
        temporary_path = Path(temporary.name)
        temporary.write(rendered_bytes)
    temporary_path.replace(metadata)
    return len(generated), len(selected)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--metadata", type=Path, default=Path("gradle/verification-metadata.xml")
    )
    parser.add_argument(
        "--release-inputs", type=Path, default=Path("release/release-inputs.json")
    )
    parser.add_argument(
        "--check", action="store_true", help="Fail if metadata is not already canonical"
    )
    args = parser.parse_args()
    generated, selected = select_metadata(
        args.metadata, args.release_inputs, check=args.check
    )
    if args.check:
        print(f"Verified {selected} reviewed components in canonical selective metadata.")
    else:
        print(
            f"Retained {selected} reviewed components from {generated}; "
            "all other artifacts are explicitly trusted by the selective policy."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

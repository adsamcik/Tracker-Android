#!/usr/bin/env python3
"""Generate and validate the compact release-evidence manifest."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from release_native import (
    PAGE_ALIGNMENT_16K,
    SIXTEEN_KB_ABIS,
    ReleaseValidationError,
)

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_OBJECT_RE = re.compile(r"^[0-9a-f]{40,64}$")
FIXED_TRACEBOX_VERSION = "0.1.0-alpha.7"
BUNDLETOOL_COORDINATE = "com.android.tools.build:bundletool:1.18.3"
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
SPECIAL_USE_SUBTYPE_PROPERTY = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
SENSITIVE_PERMISSIONS = frozenset(
    {
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_PHONE_STATE",
        "com.google.android.gms.permission.ACTIVITY_RECOGNITION",
    }
)


def _android_attribute(name: str) -> str:
    return f"{{{ANDROID_NAMESPACE}}}{name}"


def _required_attribute(element: ET.Element, name: str, label: str) -> str:
    value = element.get(name)
    if value is None or not value.strip():
        raise ReleaseValidationError(f"{label} is missing")
    return value.strip()


def _positive_integer(value: str, label: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ReleaseValidationError(f"{label} is not an integer: {value}") from exc
    if parsed <= 0:
        raise ReleaseValidationError(f"{label} must be positive: {parsed}")
    return parsed


def inspect_merged_manifest(path: Path) -> dict[str, Any]:
    """Record Play-relevant declarations from AGP's merged release manifest."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise ReleaseValidationError(f"invalid merged release manifest {path}: {exc}") from exc
    if root.tag != "manifest":
        raise ReleaseValidationError(f"{path}: root element must be manifest")

    application_id = _required_attribute(root, "package", "manifest package")
    version_name = _required_attribute(
        root, _android_attribute("versionName"), "manifest versionName"
    )
    version_code = _positive_integer(
        _required_attribute(
            root, _android_attribute("versionCode"), "manifest versionCode"
        ),
        "manifest versionCode",
    )

    uses_sdk = root.findall("uses-sdk")
    if len(uses_sdk) != 1:
        raise ReleaseValidationError(
            f"{path}: expected exactly one uses-sdk element, found {len(uses_sdk)}"
        )
    target_sdk = _positive_integer(
        _required_attribute(
            uses_sdk[0],
            _android_attribute("targetSdkVersion"),
            "merged manifest targetSdkVersion",
        ),
        "merged manifest targetSdkVersion",
    )

    permission_elements = [
        *root.findall("uses-permission"),
        *root.findall("uses-permission-sdk-23"),
    ]
    requested_permissions = sorted(
        {
            _required_attribute(
                element,
                _android_attribute("name"),
                "merged manifest permission name",
            )
            for element in permission_elements
        }
    )
    requested_sensitive_permissions = sorted(
        set(requested_permissions).intersection(SENSITIVE_PERMISSIONS)
    )

    applications = root.findall("application")
    if len(applications) != 1:
        raise ReleaseValidationError(
            f"{path}: expected exactly one application element, found {len(applications)}"
        )
    foreground_services: list[dict[str, Any]] = []
    for service in applications[0].findall("service"):
        raw_types = service.get(_android_attribute("foregroundServiceType"))
        if raw_types is None:
            continue
        service_name = _required_attribute(
            service,
            _android_attribute("name"),
            "foreground service name",
        )
        service_types = sorted(
            {value.strip() for value in raw_types.split("|") if value.strip()}
        )
        if not service_types:
            raise ReleaseValidationError(
                f"foreground service {service_name} declares no service type"
            )
        special_use_values = [
            child.get(_android_attribute("value"), "").strip()
            for child in service.findall("property")
            if child.get(_android_attribute("name")) == SPECIAL_USE_SUBTYPE_PROPERTY
        ]
        if "specialUse" in service_types:
            if len(special_use_values) != 1 or not special_use_values[0]:
                raise ReleaseValidationError(
                    f"foreground service {service_name} must declare exactly one non-empty "
                    f"{SPECIAL_USE_SUBTYPE_PROPERTY}"
                )
            special_use_subtype: str | None = special_use_values[0]
        else:
            if special_use_values:
                raise ReleaseValidationError(
                    f"foreground service {service_name} declares a specialUse subtype "
                    "without the specialUse type"
                )
            special_use_subtype = None
        foreground_services.append(
            {
                "name": service_name,
                "types": service_types,
                "specialUseSubtype": special_use_subtype,
            }
        )

    return {
        "applicationId": application_id,
        "versionName": version_name,
        "versionCode": version_code,
        "targetSdk": target_sdk,
        "requestedPermissions": requested_permissions,
        "requestedSensitivePermissions": requested_sensitive_permissions,
        "foregroundServices": sorted(
            foreground_services, key=lambda item: str(item["name"])
        ),
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _run_git(repo_root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo_root), *args],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ReleaseValidationError(
            f"git {' '.join(args)} failed with exit {result.returncode}: "
            f"{(result.stdout + result.stderr).strip()}"
        )
    return result.stdout.strip()


def source_identity(repo_root: Path, *, allow_dirty: bool = False) -> dict[str, Any]:
    commit = _run_git(repo_root, "rev-parse", "HEAD")
    tree = _run_git(repo_root, "rev-parse", "HEAD^{tree}")
    dirty_lines = [
        line
        for line in _run_git(
            repo_root, "status", "--porcelain", "--untracked-files=no"
        ).splitlines()
        if line
    ]
    if dirty_lines and not allow_dirty:
        raise ReleaseValidationError(
            "release validation requires a clean tracked source tree:\n"
            + "\n".join(dirty_lines)
        )
    return {
        "commit": commit,
        "tree": tree,
        "clean": not dirty_lines,
    }


def parse_application_identity(app_build_file: Path) -> dict[str, Any]:
    text = app_build_file.read_text(encoding="utf-8")
    patterns = {
        "applicationId": r'applicationId\s*=\s*"([^"]+)"',
        "versionName": r'versionName\s*=\s*"([^"]+)"',
        "versionCode": r"versionCode\s*=\s*(\d+)",
    }
    values: dict[str, Any] = {}
    for key, pattern in patterns.items():
        matches = re.findall(pattern, text)
        if len(matches) != 1:
            raise ReleaseValidationError(
                f"{app_build_file}: expected exactly one {key}, found {len(matches)}"
            )
        values[key] = int(matches[0]) if key == "versionCode" else matches[0]
    return values


def _dependency_coordinate(item: Mapping[str, Any]) -> str:
    return f"{item.get('group')}:{item.get('name')}:{item.get('version')}"


def load_dependencies(path: Path) -> list[dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, list):
        raise ReleaseValidationError(f"{path}: dependency metadata is not a JSON list")
    dependencies = [dict(item) for item in value if isinstance(item, dict)]
    if not dependencies:
        raise ReleaseValidationError(f"{path}: dependency metadata is empty")
    return dependencies


def validate_high_value_inputs(
    repo_root: Path,
    release_inputs: Mapping[str, Any],
    dependencies: Sequence[Mapping[str, Any]],
) -> dict[str, list[str]]:
    coordinates = sorted({_dependency_coordinate(item) for item in dependencies})

    tracebox = release_inputs["tracebox"]
    tracebox_version = tracebox["version"]
    if tracebox_version != FIXED_TRACEBOX_VERSION or "SNAPSHOT" in tracebox_version.upper():
        raise ReleaseValidationError(
            f"Tracebox must remain fixed at {FIXED_TRACEBOX_VERSION} and must never resolve as a SNAPSHOT"
        )
    tracebox_coordinates = sorted(
        coordinate
        for coordinate in coordinates
        if coordinate.startswith("io.github.tracebox:")
    )
    if not tracebox_coordinates:
        raise ReleaseValidationError("release dependency metadata contains no Tracebox artifacts")
    invalid_tracebox = [
        coordinate
        for coordinate in tracebox_coordinates
        if not coordinate.endswith(":" + FIXED_TRACEBOX_VERSION)
        or "SNAPSHOT" in coordinate.upper()
    ]
    if invalid_tracebox:
        raise ReleaseValidationError(
            "unexpected Tracebox release coordinates: " + ", ".join(invalid_tracebox)
        )
    for module in ("tracebox", "tracebox-native", "tracebox-ui-compose"):
        expected = f"io.github.tracebox:{module}:{FIXED_TRACEBOX_VERSION}"
        if expected not in tracebox_coordinates:
            raise ReleaseValidationError(f"missing direct Tracebox coordinate {expected}")

    maplibre = release_inputs["maplibre"]
    required_maplibre = {
        maplibre["composeCoordinate"],
        maplibre["resolvedAndroidCoordinate"],
    }
    missing_maplibre = required_maplibre - set(coordinates)
    if missing_maplibre:
        raise ReleaseValidationError(
            "release dependency metadata is missing MapLibre inputs: "
            + ", ".join(sorted(missing_maplibre))
        )

    sqlite = release_inputs["sqlite"]
    sqlite_aar = repo_root / sqlite["aar"]
    if not sqlite_aar.is_file():
        raise ReleaseValidationError(f"vendored SQLite AAR is missing: {sqlite_aar}")
    actual_sqlite = sha256_file(sqlite_aar)
    if actual_sqlite != sqlite["sha256"]:
        raise ReleaseValidationError(
            f"vendored SQLite SHA-256 mismatch: expected {sqlite['sha256']}, "
            f"found {actual_sqlite}"
        )

    return {
        "tracebox": tracebox_coordinates,
        "maplibre": sorted(required_maplibre),
    }


def room_schema_evidence(repo_root: Path) -> dict[str, Any]:
    result = subprocess.run(
        [
            "git",
            "-C",
            str(repo_root),
            "ls-files",
            "-z",
            "--",
            ":(glob)**/schemas/**/*.json",
        ],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise ReleaseValidationError(f"unable to enumerate committed Room schemas: {message}")
    relative_paths = sorted(
        Path(raw.decode("utf-8"))
        for raw in result.stdout.split(b"\0")
        if raw
    )
    schema_paths = [repo_root / relative for relative in relative_paths]
    if not schema_paths:
        raise ReleaseValidationError("no committed Room schema JSON files were found")
    for path in schema_paths:
        if not path.is_file():
            raise ReleaseValidationError(f"committed Room schema is missing: {path}")
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ReleaseValidationError(f"invalid Room schema JSON {path}: {exc}") from exc
    schemas = [
        {
            "path": path.relative_to(repo_root).as_posix(),
            "sha256": sha256_file(path),
        }
        for path in schema_paths
    ]
    canonical = json.dumps(schemas, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {
        "count": len(schemas),
        "sha256": hashlib.sha256(canonical).hexdigest(),
        "schemas": schemas,
    }


def _relative_path(path: Path, repo_root: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(repo_root.resolve()).as_posix()
    except ValueError:
        return str(resolved)


def artifact_record(kind: str, path: Path, repo_root: Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size <= 0:
        raise ReleaseValidationError(f"{kind} evidence is missing or empty: {path}")
    return {
        "kind": kind,
        "path": _relative_path(path, repo_root),
        "size": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def copy_evidence(source: Path, destination: Path) -> Path:
    if not source.is_file() or source.stat().st_size <= 0:
        raise ReleaseValidationError(f"required evidence is missing or empty: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return destination


def ci_context(source_commit: str) -> dict[str, Any]:
    server = os.environ.get("GITHUB_SERVER_URL")
    repository = os.environ.get("GITHUB_REPOSITORY")
    run_id = os.environ.get("GITHUB_RUN_ID")
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT")
    run_url = (
        f"{server}/{repository}/actions/runs/{run_id}"
        if server and repository and run_id
        else None
    )
    return {
        "provider": "github-actions" if run_id else "local",
        "runId": run_id,
        "runAttempt": attempt,
        "runUrl": run_url,
        "workflowSha": os.environ.get("GITHUB_SHA"),
        "sourceMatchesWorkflowSha": (
            os.environ.get("GITHUB_SHA") == source_commit
            if os.environ.get("GITHUB_SHA")
            else None
        ),
    }


def _require_sha256(value: Any, label: str) -> None:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise ReleaseValidationError(f"{label} is not a lowercase SHA-256")


def _require_git_object(value: Any, label: str) -> None:
    if not isinstance(value, str) or not GIT_OBJECT_RE.fullmatch(value):
        raise ReleaseValidationError(f"{label} is not a Git object id")


def validate_release_manifest(manifest: Mapping[str, Any]) -> None:
    if manifest.get("schemaVersion") != 1:
        raise ReleaseValidationError("release manifest schemaVersion must be 1")

    source = manifest.get("source")
    if not isinstance(source, dict):
        raise ReleaseValidationError("release manifest source block is missing")
    _require_git_object(source.get("commit"), "source.commit")
    _require_git_object(source.get("tree"), "source.tree")
    if source.get("clean") is not True:
        raise ReleaseValidationError("release manifest must represent a clean tracked source tree")

    version = manifest.get("version")
    if not isinstance(version, dict):
        raise ReleaseValidationError("release manifest version block is missing")
    release_id = manifest.get("releaseId")
    if not isinstance(release_id, str) or source["commit"][:12] not in release_id:
        raise ReleaseValidationError("releaseId must include the source commit prefix")
    for key in ("applicationId", "versionName", "versionCode"):
        if key not in version:
            raise ReleaseValidationError(f"release manifest version.{key} is missing")

    android_manifest = manifest.get("androidManifest")
    if not isinstance(android_manifest, dict):
        raise ReleaseValidationError("structured Android manifest evidence is missing")
    for key in ("applicationId", "versionName", "versionCode"):
        if android_manifest.get(key) != version.get(key):
            raise ReleaseValidationError(
                f"androidManifest.{key} does not match version.{key}"
            )
    if not isinstance(android_manifest.get("targetSdk"), int) or android_manifest["targetSdk"] <= 0:
        raise ReleaseValidationError("androidManifest.targetSdk must be a positive integer")
    requested_permissions = android_manifest.get("requestedPermissions")
    sensitive_permissions = android_manifest.get("requestedSensitivePermissions")
    if not isinstance(requested_permissions, list) or not all(
        isinstance(value, str) and value for value in requested_permissions
    ):
        raise ReleaseValidationError("Android requested-permission evidence is missing")
    if not isinstance(sensitive_permissions, list) or not sensitive_permissions:
        raise ReleaseValidationError("Android sensitive-permission evidence is missing")
    if not set(sensitive_permissions).issubset(requested_permissions):
        raise ReleaseValidationError(
            "Android sensitive-permission evidence is not a subset of requested permissions"
        )
    foreground_services = android_manifest.get("foregroundServices")
    if not isinstance(foreground_services, list) or not foreground_services:
        raise ReleaseValidationError("Android foreground-service evidence is missing")
    special_use_services = 0
    for index, service in enumerate(foreground_services):
        if not isinstance(service, dict) or not service.get("name"):
            raise ReleaseValidationError(
                f"androidManifest.foregroundServices[{index}] is incomplete"
            )
        service_types = service.get("types")
        if not isinstance(service_types, list) or not service_types:
            raise ReleaseValidationError(
                f"androidManifest.foregroundServices[{index}].types is empty"
            )
        if "specialUse" in service_types:
            special_use_services += 1
            if not service.get("specialUseSubtype"):
                raise ReleaseValidationError(
                    f"androidManifest.foregroundServices[{index}] is missing specialUse subtype"
                )
    if special_use_services == 0:
        raise ReleaseValidationError("Android specialUse foreground-service evidence is missing")

    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list):
        raise ReleaseValidationError("release manifest artifacts must be a list")
    required_kinds = {"aab", "apk-set", "apk"}
    found_kinds = {item.get("kind") for item in artifacts if isinstance(item, dict)}
    if not required_kinds.issubset(found_kinds):
        raise ReleaseValidationError(
            f"release artifacts are missing kinds {sorted(required_kinds - found_kinds)}"
        )
    for index, item in enumerate(artifacts):
        if not isinstance(item, dict) or not item.get("path") or item.get("size", 0) <= 0:
            raise ReleaseValidationError(f"artifacts[{index}] is incomplete")
        _require_sha256(item.get("sha256"), f"artifacts[{index}].sha256")

    evidence = manifest.get("evidence")
    if not isinstance(evidence, list):
        raise ReleaseValidationError("release manifest evidence must be a list")
    evidence_kinds = {item.get("kind") for item in evidence if isinstance(item, dict)}
    required_evidence = {
        "merged-manifest",
        "bundle-config-json",
        "bundle-config-proto",
        "r8-mapping",
        "native-symbols",
        "dependency-metadata",
        "source-commit",
    }
    missing_evidence = required_evidence - evidence_kinds
    if missing_evidence:
        raise ReleaseValidationError(
            "release evidence is missing: " + ", ".join(sorted(missing_evidence))
        )
    for index, item in enumerate(evidence):
        _require_sha256(item.get("sha256"), f"evidence[{index}].sha256")

    room = manifest.get("room")
    if not isinstance(room, dict) or room.get("count", 0) <= 0:
        raise ReleaseValidationError("Room schema evidence is empty")
    _require_sha256(room.get("sha256"), "room.sha256")

    tooling = manifest.get("tooling")
    if not isinstance(tooling, dict) or tooling.get("bundletoolCoordinate") != BUNDLETOOL_COORDINATE:
        raise ReleaseValidationError("pinned bundletool evidence is missing")

    signing = manifest.get("signingAndPublication")
    if not isinstance(signing, dict):
        raise ReleaseValidationError("signing and publication evidence is missing")
    if signing.get("representativeApkSetSigning") != "bundletool-debug-key-only":
        raise ReleaseValidationError("representative APK-set signing is not debug-only")
    if signing.get("releaseSigning") != "manual-not-captured":
        raise ReleaseValidationError("release signing must remain manual and uncaptured")
    if signing.get("publication") != "manual-not-performed":
        raise ReleaseValidationError("release evidence must not claim publication")

    sqlite = manifest.get("sqlite")
    if not isinstance(sqlite, dict):
        raise ReleaseValidationError("SQLite evidence is missing")
    _require_sha256(sqlite.get("sha256"), "sqlite.sha256")
    _require_sha256(sqlite.get("sha3_256"), "sqlite.sha3_256")
    if not sqlite.get("sourceId"):
        raise ReleaseValidationError("SQLite sourceId is missing")

    tracebox = manifest.get("tracebox")
    if not isinstance(tracebox, dict):
        raise ReleaseValidationError("Tracebox evidence is missing")
    version_value = tracebox.get("version")
    if version_value != FIXED_TRACEBOX_VERSION or "SNAPSHOT" in str(version_value).upper():
        raise ReleaseValidationError(
            f"Tracebox release evidence is not fixed {FIXED_TRACEBOX_VERSION}"
        )
    _require_git_object(tracebox.get("sourceCommit"), "tracebox.sourceCommit")
    _require_git_object(tracebox.get("sourceTree"), "tracebox.sourceTree")
    for name, digest in tracebox.get("artifactSha256", {}).items():
        _require_sha256(digest, f"tracebox.artifactSha256[{name}]")

    native = manifest.get("native")
    if not isinstance(native, dict) or not native.get("abis"):
        raise ReleaseValidationError("native ABI evidence is missing")
    for collection_name in ("aab", "apks"):
        records = native.get(collection_name)
        if not isinstance(records, list) or not records:
            raise ReleaseValidationError(f"native.{collection_name} evidence is empty")
        for index, record in enumerate(records):
            _require_sha256(
                record.get("sha256"), f"native.{collection_name}[{index}].sha256"
            )
            if record.get("relro") is not True:
                raise ReleaseValidationError(
                    f"native.{collection_name}[{index}] is missing RELRO"
                )
            abi = record.get("abi")
            required_alignment = record.get("requiredLoadAlignment")
            expected_alignment = PAGE_ALIGNMENT_16K if abi in SIXTEEN_KB_ABIS else 4096
            if required_alignment != expected_alignment:
                raise ReleaseValidationError(
                    f"native.{collection_name}[{index}] has inconsistent required alignment"
                )
            if min(record.get("loadAlignments", [0])) < required_alignment:
                raise ReleaseValidationError(
                    f"native.{collection_name}[{index}] is below required alignment"
                )
            if record.get("loadAlignmentResult") != "PASS":
                raise ReleaseValidationError(
                    f"native.{collection_name}[{index}] alignment result is not PASS"
                )
    apk_zip_alignment = native.get("apkZipAlignment")
    if not isinstance(apk_zip_alignment, list) or not apk_zip_alignment:
        raise ReleaseValidationError("APK ZIP alignment evidence is missing")
    for index, record in enumerate(apk_zip_alignment):
        if (
            not isinstance(record, dict)
            or not record.get("path")
            or record.get("pageSize") != PAGE_ALIGNMENT_16K
            or record.get("result") != "PASS"
        ):
            raise ReleaseValidationError(
                f"native.apkZipAlignment[{index}] is incomplete or not PASS"
            )
    symbols = native.get("symbols")
    if not isinstance(symbols, dict) or symbols.get("format") != "AGP_SYMBOL_TABLE":
        raise ReleaseValidationError("native symbol coverage evidence is missing")
    symbol_entries = symbols.get("entries")
    if not isinstance(symbol_entries, list) or not symbol_entries:
        raise ReleaseValidationError("native symbol coverage is empty")
    for index, entry in enumerate(symbol_entries):
        _require_sha256(entry.get("sha256"), f"native.symbols.entries[{index}].sha256")
    if not isinstance(symbols.get("unavailable"), list):
        raise ReleaseValidationError("unavailable native symbol coverage is missing")


def write_release_manifest(manifest: Mapping[str, Any], destination: Path) -> None:
    validate_release_manifest(manifest)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def build_release_manifest(
    *,
    repo_root: Path,
    release_inputs: Mapping[str, Any],
    aab: Path,
    apk_set: Path,
    apks: Sequence[Path],
    merged_manifest: Path,
    evidence_files: Sequence[tuple[str, Path]],
    dependency_metadata: Path,
    native_inventory: Mapping[str, Any],
    allow_dirty: bool = False,
) -> dict[str, Any]:
    source = source_identity(repo_root, allow_dirty=allow_dirty)
    configured_version = parse_application_identity(
        repo_root / "app" / "build.gradle.kts"
    )
    android_manifest = inspect_merged_manifest(merged_manifest)
    version = {
        key: android_manifest[key]
        for key in ("applicationId", "versionName", "versionCode")
    }
    if version != configured_version:
        raise ReleaseValidationError(
            f"merged manifest identity {version} does not match app configuration "
            f"{configured_version}"
        )
    application_inputs = release_inputs["application"]
    if android_manifest["applicationId"] != application_inputs["applicationId"]:
        raise ReleaseValidationError(
            "merged manifest application ID does not match reviewed release inputs"
        )
    if android_manifest["targetSdk"] != application_inputs["targetSdk"]:
        raise ReleaseValidationError(
            "merged manifest target SDK does not match reviewed release inputs"
        )
    dependencies = load_dependencies(dependency_metadata)
    resolved = validate_high_value_inputs(
        repo_root, release_inputs, dependencies
    )
    release_id = (
        f"{version['applicationId']}:{version['versionName']}"
        f"({version['versionCode']})@{source['commit'][:12]}"
    )

    artifacts = [
        artifact_record("aab", aab, repo_root),
        artifact_record("apk-set", apk_set, repo_root),
    ]
    artifacts.extend(artifact_record("apk", apk, repo_root) for apk in apks)

    evidence = [
        artifact_record(kind, path, repo_root)
        for kind, path in evidence_files
    ]

    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "releaseId": release_id,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "version": version,
        "androidManifest": android_manifest,
        "ci": ci_context(source["commit"]),
        "artifacts": artifacts,
        "evidence": evidence,
        "room": room_schema_evidence(repo_root),
        "tooling": dict(release_inputs["tooling"]),
        "sqlite": dict(release_inputs["sqlite"]),
        "maplibre": {
            **dict(release_inputs["maplibre"]),
            "resolvedCoordinates": resolved["maplibre"],
        },
        "tracebox": {
            **dict(release_inputs["tracebox"]),
            "resolvedCoordinates": resolved["tracebox"],
        },
        "native": {
            "abis": list(native_inventory["abis"]),
            "aab": list(native_inventory["aab"]),
            "apks": list(native_inventory["apks"]),
            "symbols": dict(native_inventory["symbols"]),
            "zipalign": native_inventory["zipalign"],
            "apkZipAlignment": [
                {
                    "path": _relative_path(Path(item["path"]), repo_root),
                    "pageSize": item["pageSize"],
                    "result": item["result"],
                }
                for item in native_inventory["apkArtifacts"]
            ],
            "pageAlignment": "PAGE_ALIGNMENT_16K",
        },
        "signingAndPublication": {
            "releaseSigning": "manual-not-captured",
            "representativeApkSetSigning": "bundletool-debug-key-only",
            "publication": "manual-not-performed",
            "note": (
                "Bundletool debug-signs only the representative APK set. Release signing "
                "and Play publication remain manual and outside this non-deploying task."
            ),
        },
    }
    validate_release_manifest(manifest)
    return manifest

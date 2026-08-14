#!/usr/bin/env python3
"""Build representative APKs and collect non-deploying release evidence."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Iterable, Sequence

from release_manifest import (
    build_release_manifest,
    copy_evidence,
    write_release_manifest,
)
from release_native import (
    ReleaseValidationError,
    archive_native_symbols,
    find_android_build_tool,
    validate_native_archives,
)

BUNDLETOOL_MAIN = "com.android.tools.build.bundletool.BundleToolMain"


def _single_file(paths: Iterable[Path], label: str) -> Path:
    files = sorted({path.resolve() for path in paths if path.is_file()})
    if len(files) != 1:
        rendered = ", ".join(str(path) for path in files) or "<none>"
        raise ReleaseValidationError(
            f"expected exactly one {label}, found {len(files)}: {rendered}"
        )
    if files[0].stat().st_size <= 0:
        raise ReleaseValidationError(f"{label} is empty: {files[0]}")
    return files[0]


def _first_file(paths: Sequence[Path], label: str) -> Path:
    for path in paths:
        if path.is_file() and path.stat().st_size > 0:
            return path.resolve()
    raise ReleaseValidationError(
        f"{label} is missing; checked: " + ", ".join(str(path) for path in paths)
    )


def _java_executable() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / (
            "java.exe" if os.name == "nt" else "java"
        )
        if candidate.is_file():
            return str(candidate)
    executable = shutil.which("java")
    if executable:
        return executable
    raise ReleaseValidationError("Java was not found for the pinned bundletool invocation")


def _run_bundletool(
    classpath: str,
    arguments: Sequence[str],
    *,
    stdout_path: Path | None = None,
) -> None:
    command = [_java_executable(), "-cp", classpath, BUNDLETOOL_MAIN, *arguments]
    if stdout_path is None:
        result = subprocess.run(command, check=False)
    else:
        stdout_path.parent.mkdir(parents=True, exist_ok=True)
        with stdout_path.open("wb") as output:
            result = subprocess.run(command, check=False, stdout=output)
    if result.returncode != 0:
        raise ReleaseValidationError(
            f"bundletool {' '.join(arguments[:2])} failed with exit {result.returncode}"
        )


def _reset_output(repo_root: Path, output: Path) -> None:
    resolved_repo = repo_root.resolve()
    resolved_output = output.resolve()
    required_parent = (resolved_repo / "build").resolve()
    if required_parent not in resolved_output.parents:
        raise ReleaseValidationError(
            f"refusing to replace release evidence outside {required_parent}: {resolved_output}"
        )
    if resolved_output.name != "release-evidence":
        raise ReleaseValidationError(
            f"release evidence output must end in release-evidence: {resolved_output}"
        )
    if resolved_output.exists():
        shutil.rmtree(resolved_output)
    resolved_output.mkdir(parents=True)


def _extract_bundle_config(aab: Path, destination: Path) -> None:
    with zipfile.ZipFile(aab) as archive:
        try:
            data = archive.read("BundleConfig.pb")
        except KeyError as exc:
            raise ReleaseValidationError(f"{aab}: BundleConfig.pb is missing") from exc
    if not data:
        raise ReleaseValidationError(f"{aab}: BundleConfig.pb is empty")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)


def _git_commit(repo_root: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ReleaseValidationError(
            "unable to capture source commit: " + (result.stdout + result.stderr).strip()
        )
    return result.stdout.strip()


def collect_release_evidence(args: argparse.Namespace) -> Path:
    repo_root = args.repo_root.resolve()
    output = args.output.resolve()
    _reset_output(repo_root, output)

    release_inputs_path = repo_root / "release" / "release-inputs.json"
    release_inputs = json.loads(release_inputs_path.read_text(encoding="utf-8"))

    aab = _single_file(
        (repo_root / "app" / "build" / "outputs" / "bundle" / "release").glob("*.aab"),
        "release AAB",
    )

    artifacts_dir = output / "artifacts"
    artifacts_dir.mkdir(parents=True)
    apk_set = artifacts_dir / "release-universal.apks"
    aapt2 = find_android_build_tool("aapt2", args.aapt2)
    _run_bundletool(
        args.bundletool_classpath,
        [
            "build-apks",
            f"--bundle={aab}",
            f"--output={apk_set}",
            "--mode=universal",
            "--overwrite",
            f"--aapt2={aapt2}",
        ],
    )

    bundle_config_json = output / "bundle" / "BundleConfig.json"
    _run_bundletool(
        args.bundletool_classpath,
        ["dump", "config", f"--bundle={aab}"],
        stdout_path=bundle_config_json,
    )
    try:
        bundle_config = json.loads(bundle_config_json.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ReleaseValidationError(
            f"bundletool produced invalid bundle configuration JSON: {exc}"
        ) from exc

    bundle_config_proto = output / "bundle" / "BundleConfig.pb"
    _extract_bundle_config(aab, bundle_config_proto)

    native_inventory = validate_native_archives(
        aab,
        apk_set,
        bundle_config,
        release_inputs,
        output / "apks",
        zipalign=args.zipalign,
    )
    apk_paths = [
        item["path"] for item in native_inventory["apkArtifacts"]
    ]

    app_build = repo_root / "app" / "build"
    merged_manifest = _first_file(
        [
            app_build
            / "intermediates"
            / "merged_manifests"
            / "release"
            / "processReleaseManifest"
            / "AndroidManifest.xml",
            app_build
            / "intermediates"
            / "packaged_manifests"
            / "release"
            / "processReleaseManifestForPackage"
            / "AndroidManifest.xml",
        ],
        "merged release manifest",
    )
    manifest_report = _first_file(
        [app_build / "outputs" / "logs" / "manifest-merger-release-report.txt"],
        "manifest merger report",
    )
    r8_mapping = _first_file(
        [app_build / "outputs" / "mapping" / "release" / "mapping.txt"],
        "R8 mapping",
    )
    native_symbol_root = (
        app_build
        / "intermediates"
        / "native_symbol_tables"
        / "release"
        / "extractReleaseNativeSymbolTables"
        / "out"
    )
    native_symbols = output / "native-symbols" / "native-debug-symbols.zip"
    native_inventory["symbols"] = archive_native_symbols(
        native_symbol_root,
        native_symbols,
        native_inventory["aab"],
    )
    dependency_json = _first_file(
        [
            app_build
            / "generated"
            / "third_party_licenses"
            / "release"
            / "dependencies.json"
        ],
        "release dependency JSON",
    )
    sdk_dependencies = _first_file(
        [
            app_build
            / "outputs"
            / "sdk-dependencies"
            / "release"
            / "sdkDependencies.txt"
        ],
        "SDK dependency metadata",
    )
    bundle_dependencies = _first_file(
        [
            app_build
            / "intermediates"
            / "bundle_dependency_report"
            / "release"
            / "configureReleaseDependencies"
            / "dependencies.pb"
        ],
        "bundle dependency metadata",
    )

    copied_evidence: list[tuple[str, Path]] = [
        (
            "merged-manifest",
            copy_evidence(merged_manifest, output / "manifest" / "AndroidManifest.xml"),
        ),
        (
            "manifest-merger-report",
            copy_evidence(
                manifest_report,
                output / "manifest" / "manifest-merger-report.txt",
            ),
        ),
        ("bundle-config-json", bundle_config_json),
        ("bundle-config-proto", bundle_config_proto),
        (
            "r8-mapping",
            copy_evidence(r8_mapping, output / "r8" / "mapping.txt"),
        ),
        (
            "native-symbols",
            native_symbols,
        ),
        (
            "dependency-metadata",
            copy_evidence(
                dependency_json,
                output / "dependencies" / "dependencies.json",
            ),
        ),
        (
            "sdk-dependency-metadata",
            copy_evidence(
                sdk_dependencies,
                output / "dependencies" / "sdkDependencies.txt",
            ),
        ),
        (
            "bundle-dependency-metadata",
            copy_evidence(
                bundle_dependencies,
                output / "dependencies" / "dependencies.pb",
            ),
        ),
        (
            "release-inputs",
            copy_evidence(
                release_inputs_path,
                output / "inputs" / "release-inputs.json",
            ),
        ),
    ]

    source_commit_file = output / "source" / "commit.txt"
    source_commit_file.parent.mkdir(parents=True)
    source_commit_file.write_text(
        _git_commit(repo_root) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    copied_evidence.append(("source-commit", source_commit_file))

    manifest = build_release_manifest(
        repo_root=repo_root,
        release_inputs=release_inputs,
        aab=aab,
        apk_set=apk_set,
        apks=apk_paths,
        evidence_files=copied_evidence,
        dependency_metadata=dependency_json,
        native_inventory=native_inventory,
        allow_dirty=args.allow_dirty,
    )
    manifest_path = output / "release-manifest.json"
    write_release_manifest(manifest, manifest_path)
    return manifest_path


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bundletool-classpath", required=True)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--zipalign", type=Path)
    parser.add_argument(
        "--allow-dirty",
        action="store_true",
        help="Fixture/debug escape hatch; never use for CI release evidence.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = parse_args(sys.argv[1:] if argv is None else argv)
        manifest = collect_release_evidence(args)
        print(f"Release evidence: {manifest}")
        return 0
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, ReleaseValidationError) as exc:
        print(f"release validation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

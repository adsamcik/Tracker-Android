from __future__ import annotations

import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_manifest import room_schema_evidence, validate_release_manifest  # noqa: E402
from release_native import ReleaseValidationError  # noqa: E402

HASH = "a" * 64
COMMIT = "b" * 40
TREE = "c" * 40


def record(kind: str) -> dict:
    return {
        "kind": kind,
        "path": f"build/release-evidence/{kind}",
        "size": 1,
        "sha256": HASH,
    }


def manifest_fixture() -> dict:
    native_record = {
        "abi": "arm64-v8a",
        "name": "libfixture.so",
        "path": "lib/arm64-v8a/libfixture.so",
        "coordinate": "fixture:native:1",
        "sha256": HASH,
        "elfClass": 64,
        "loadAlignments": [16384],
        "requiredLoadAlignment": 16384,
        "relro": True,
    }
    return {
        "schemaVersion": 1,
        "releaseId": f"example.app:1.0(1)@{COMMIT[:12]}",
        "source": {"commit": COMMIT, "tree": TREE, "clean": True},
        "version": {
            "applicationId": "example.app",
            "versionName": "1.0",
            "versionCode": 1,
        },
        "artifacts": [record("aab"), record("apk-set"), record("apk")],
        "evidence": [
            record("merged-manifest"),
            record("bundle-config-json"),
            record("bundle-config-proto"),
            record("r8-mapping"),
            record("native-symbols"),
            record("dependency-metadata"),
            record("source-commit"),
        ],
        "room": {"count": 1, "sha256": HASH, "schemas": []},
        "tooling": {"bundletoolCoordinate": "com.android.tools.build:bundletool:1.18.3"},
        "sqlite": {"sha256": HASH, "sha3_256": HASH, "sourceId": "fixed"},
        "tracebox": {
            "version": "0.1.0-alpha.7",
            "sourceCommit": COMMIT,
            "sourceTree": TREE,
            "artifactSha256": {"tracebox.aar": HASH},
        },
        "native": {
            "abis": ["arm64-v8a"],
            "aab": [copy.deepcopy(native_record)],
            "apks": [copy.deepcopy(native_record)],
            "symbols": {
                "format": "AGP_SYMBOL_TABLE",
                "entries": [
                    {
                        "abi": "arm64-v8a",
                        "name": "libfixture.so",
                        "path": "arm64-v8a/libfixture.so.sym",
                        "sha256": HASH,
                    }
                ],
                "unavailable": [],
            },
        },
        "signingAndPublication": {
            "releaseSigning": "manual-not-captured",
            "representativeApkSetSigning": "bundletool-debug-key-only",
            "publication": "manual-not-performed",
        },
    }


class ReleaseManifestValidationTest(unittest.TestCase):
    def test_collects_nested_tracked_room_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = Path("module/schemas/example.Database/3.json")
            schema = root / relative
            schema.parent.mkdir(parents=True)
            schema.write_text('{"formatVersion": 1}', encoding="utf-8")
            git_result = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout=(relative.as_posix() + "\0").encode("utf-8"),
                stderr=b"",
            )
            with patch("release_manifest.subprocess.run", return_value=git_result):
                evidence = room_schema_evidence(root)

            self.assertEqual(evidence["count"], 1)
            self.assertEqual(evidence["schemas"][0]["path"], relative.as_posix())

    def test_accepts_complete_fixture(self) -> None:
        validate_release_manifest(manifest_fixture())

    def test_rejects_controlled_unpinned_bundletool(self) -> None:
        manifest = manifest_fixture()
        manifest["tooling"]["bundletoolCoordinate"] = "com.android.tools.build:bundletool:latest"
        with self.assertRaisesRegex(ReleaseValidationError, "pinned bundletool"):
            validate_release_manifest(manifest)

    def test_rejects_release_signed_representative_apk_set(self) -> None:
        manifest = manifest_fixture()
        manifest["signingAndPublication"]["representativeApkSetSigning"] = "release"
        with self.assertRaisesRegex(ReleaseValidationError, "APK-set signing"):
            validate_release_manifest(manifest)

    def test_rejects_missing_native_symbol_coverage(self) -> None:
        manifest = manifest_fixture()
        del manifest["native"]["symbols"]
        with self.assertRaisesRegex(ReleaseValidationError, "symbol coverage"):
            validate_release_manifest(manifest)

    def test_rejects_controlled_snapshot_tracebox_coordinate(self) -> None:
        manifest = manifest_fixture()
        manifest["tracebox"]["version"] = "0.1.0-alpha.7-SNAPSHOT"
        with self.assertRaisesRegex(ReleaseValidationError, "fixed 0.1.0-alpha.7"):
            validate_release_manifest(manifest)

    def test_rejects_controlled_bad_artifact_hash(self) -> None:
        manifest = manifest_fixture()
        manifest["artifacts"][0]["sha256"] = "not-a-hash"
        with self.assertRaisesRegex(ReleaseValidationError, "SHA-256"):
            validate_release_manifest(manifest)

    def test_rejects_controlled_missing_bundle_configuration(self) -> None:
        manifest = manifest_fixture()
        manifest["evidence"] = [
            item
            for item in manifest["evidence"]
            if item["kind"] != "bundle-config-json"
        ]
        with self.assertRaisesRegex(ReleaseValidationError, "bundle-config-json"):
            validate_release_manifest(manifest)

    def test_rejects_controlled_unaligned_native_manifest(self) -> None:
        manifest = manifest_fixture()
        manifest["native"]["aab"][0]["loadAlignments"] = [4096]
        with self.assertRaisesRegex(ReleaseValidationError, "below required alignment"):
            validate_release_manifest(manifest)


if __name__ == "__main__":
    unittest.main()

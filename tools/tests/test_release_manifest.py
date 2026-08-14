from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_manifest import validate_release_manifest  # noqa: E402
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
        "sqlite": {"sha256": HASH, "sha3_256": HASH, "sourceId": "fixed"},
        "tracebox": {
            "version": "0.1.0-alpha.3",
            "sourceCommit": COMMIT,
            "sourceTree": TREE,
            "artifactSha256": {"tracebox.aar": HASH},
        },
        "native": {
            "abis": ["arm64-v8a"],
            "aab": [copy.deepcopy(native_record)],
            "apks": [copy.deepcopy(native_record)],
        },
    }


class ReleaseManifestValidationTest(unittest.TestCase):
    def test_accepts_complete_fixture(self) -> None:
        validate_release_manifest(manifest_fixture())

    def test_rejects_controlled_snapshot_tracebox_coordinate(self) -> None:
        manifest = manifest_fixture()
        manifest["tracebox"]["version"] = "0.1.0-alpha.3-SNAPSHOT"
        with self.assertRaisesRegex(ReleaseValidationError, "fixed alpha.3"):
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
        with self.assertRaisesRegex(ReleaseValidationError, "below 16 KiB"):
            validate_release_manifest(manifest)


if __name__ == "__main__":
    unittest.main()

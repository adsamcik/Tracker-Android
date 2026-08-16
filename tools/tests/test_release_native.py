from __future__ import annotations

import os
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_native import (  # noqa: E402
    ReleaseValidationError,
    archive_native_symbols,
    find_android_build_tool,
    inspect_elf,
    validate_native_entries,
)


def elf64(*, alignment: int = 0x4000, relro: bool = True) -> bytes:
    ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8)
    phnum = 2 if relro else 1
    header = struct.pack(
        "<16sHHIQQQIHHHHHH",
        ident,
        3,
        183,
        1,
        0,
        64,
        0,
        0,
        64,
        56,
        phnum,
        0,
        0,
        0,
    )
    load = struct.pack(
        "<IIQQQQQQ",
        1,
        5,
        0,
        0,
        0,
        64 + 56 * phnum,
        64 + 56 * phnum,
        alignment,
    )
    if not relro:
        return header + load
    gnu_relro = struct.pack(
        "<IIQQQQQQ",
        0x6474E552,
        4,
        0,
        0,
        0,
        0,
        0,
        1,
    )
    return header + load + gnu_relro


def release_inputs() -> dict:
    return {
        "expectedAbis": ["arm64-v8a"],
        "nativeLibraries": [
            {
                "name": "libknown.so",
                "coordinate": "example:known:1",
                "abis": ["arm64-v8a"],
            }
        ],
    }


class NativeReleaseValidationTest(unittest.TestCase):
    def test_archives_exact_native_symbol_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            symbol = root / "symbols" / "arm64-v8a" / "libknown.so.sym"
            symbol.parent.mkdir(parents=True)
            symbol.write_bytes(b"symbol-table")
            archive = root / "out" / "native-debug-symbols.zip"

            coverage = archive_native_symbols(
                root / "symbols",
                archive,
                [
                    {"abi": "arm64-v8a", "name": "libknown.so"},
                    {"abi": "x86_64", "name": "libknown.so"},
                ],
            )

            self.assertEqual(len(coverage["entries"]), 1)
            self.assertEqual(
                coverage["unavailable"],
                [{"abi": "x86_64", "name": "libknown.so"}],
            )
            with zipfile.ZipFile(archive) as generated:
                self.assertEqual(
                    generated.namelist(),
                    ["arm64-v8a/libknown.so.sym"],
                )

    def test_rejects_symbol_for_unknown_native_library(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            symbol = root / "symbols" / "arm64-v8a" / "libunknown.so.sym"
            symbol.parent.mkdir(parents=True)
            symbol.write_bytes(b"symbol-table")
            with self.assertRaisesRegex(ReleaseValidationError, "no packaged library"):
                archive_native_symbols(
                    root / "symbols",
                    root / "native-debug-symbols.zip",
                    [{"abi": "arm64-v8a", "name": "libknown.so"}],
                )

    def test_ignores_symbol_table_for_unshipped_abi(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arm64_symbol = root / "symbols" / "arm64-v8a" / "libknown.so.sym"
            arm64_symbol.parent.mkdir(parents=True)
            arm64_symbol.write_bytes(b"arm64-symbol-table")
            filtered_symbol = root / "symbols" / "armeabi-v7a" / "libknown.so.sym"
            filtered_symbol.parent.mkdir(parents=True)
            filtered_symbol.write_bytes(b"filtered-symbol-table")
            archive = root / "native-debug-symbols.zip"

            coverage = archive_native_symbols(
                root / "symbols",
                archive,
                [{"abi": "arm64-v8a", "name": "libknown.so"}],
            )

            self.assertEqual(
                [(entry["abi"], entry["name"]) for entry in coverage["entries"]],
                [("arm64-v8a", "libknown.so")],
            )
            self.assertEqual(coverage["unavailable"], [])
            with zipfile.ZipFile(archive) as generated:
                self.assertEqual(
                    generated.namelist(),
                    ["arm64-v8a/libknown.so.sym"],
                )

    def test_resolves_latest_sdk_build_tool(self) -> None:
        executable = "aapt2.exe" if os.name == "nt" else "aapt2"
        with tempfile.TemporaryDirectory() as temporary:
            sdk = Path(temporary)
            older = sdk / "build-tools" / "34.0.0" / executable
            newer = sdk / "build-tools" / "35.0.1" / executable
            older.parent.mkdir(parents=True)
            newer.parent.mkdir(parents=True)
            older.touch()
            newer.touch()
            with patch.dict(
                os.environ,
                {"ANDROID_HOME": str(sdk), "ANDROID_SDK_ROOT": str(sdk)},
            ):
                self.assertEqual(find_android_build_tool("aapt2"), newer.resolve())

    def test_rejects_missing_sdk_build_tool(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict(
                os.environ,
                {"ANDROID_HOME": temporary, "ANDROID_SDK_ROOT": temporary},
            ):
                with self.assertRaisesRegex(ReleaseValidationError, "aapt2 was not found"):
                    find_android_build_tool("aapt2")

    def test_accepts_16k_elf_with_relro(self) -> None:
        result = inspect_elf(elf64(), "good.so")
        self.assertEqual((0x4000,), result.load_alignments)
        self.assertTrue(result.has_relro)

    def test_rejects_controlled_4k_elf(self) -> None:
        with self.assertRaisesRegex(ReleaseValidationError, "below required 16 KiB"):
            inspect_elf(elf64(alignment=0x1000), "bad-4k.so")

    def test_rejects_controlled_missing_relro(self) -> None:
        with self.assertRaisesRegex(ReleaseValidationError, "GNU_RELRO"):
            inspect_elf(elf64(relro=False), "bad-relro.so")

    def test_rejects_controlled_unknown_native_file(self) -> None:
        with self.assertRaisesRegex(ReleaseValidationError, "unknown native file"):
            validate_native_entries(
                [("base/lib/arm64-v8a/libunreviewed.so", elf64())],
                release_inputs(),
                "fixture.aab",
            )

    def test_accepts_4k_alignment_for_32_bit_abi(self) -> None:
        inputs = release_inputs()
        inputs["expectedAbis"] = ["armeabi-v7a"]
        inputs["nativeLibraries"][0]["abis"] = ["armeabi-v7a"]
        result = validate_native_entries(
            [("base/lib/armeabi-v7a/libknown.so", elf64(alignment=0x1000))],
            inputs,
            "fixture.aab",
        )
        self.assertEqual(4096, result[0]["requiredLoadAlignment"])

    def test_rejects_4k_alignment_for_64_bit_abi(self) -> None:
        with self.assertRaisesRegex(ReleaseValidationError, "below required 16 KiB"):
            validate_native_entries(
                [("base/lib/arm64-v8a/libknown.so", elf64(alignment=0x1000))],
                release_inputs(),
                "fixture.aab",
            )

    def test_rejects_missing_reviewed_native_file(self) -> None:
        with self.assertRaisesRegex(ReleaseValidationError, "expected native files are missing"):
            validate_native_entries([], release_inputs(), "fixture.aab")


if __name__ == "__main__":
    unittest.main()

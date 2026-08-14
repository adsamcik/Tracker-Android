from __future__ import annotations

import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_native import (  # noqa: E402
    ReleaseValidationError,
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
    def test_accepts_16k_elf_with_relro(self) -> None:
        result = inspect_elf(elf64(), "good.so")
        self.assertEqual((0x4000,), result.load_alignments)
        self.assertTrue(result.has_relro)

    def test_rejects_controlled_4k_elf(self) -> None:
        with self.assertRaisesRegex(ReleaseValidationError, "below 16 KiB"):
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

    def test_rejects_missing_reviewed_native_file(self) -> None:
        with self.assertRaisesRegex(ReleaseValidationError, "expected native files are missing"):
            validate_native_entries([], release_inputs(), "fixture.aab")


if __name__ == "__main__":
    unittest.main()

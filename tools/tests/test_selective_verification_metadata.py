from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from selective_verification_metadata import canonical_content_matches  # noqa: E402


class CanonicalMetadataComparisonTest(unittest.TestCase):
    def test_accepts_windows_checkout_line_endings(self) -> None:
        generated = b"<?xml version='1.0'?>\n<checksum value='abc'/>\n"
        checked_out = generated.replace(b"\n", b"\r\n")

        self.assertTrue(canonical_content_matches(checked_out, generated))

    def test_rejects_changed_metadata_content(self) -> None:
        generated = b"<checksum value='abc'/>\n"
        changed = b"<checksum value='def'/>\r\n"

        self.assertFalse(canonical_content_matches(changed, generated))


if __name__ == "__main__":
    unittest.main()

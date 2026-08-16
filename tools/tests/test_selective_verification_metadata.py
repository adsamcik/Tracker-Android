from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from selective_verification_metadata import (  # noqa: E402
    canonical_content_matches,
    select_metadata,
)


class CanonicalMetadataComparisonTest(unittest.TestCase):
    def test_accepts_windows_checkout_line_endings(self) -> None:
        generated = b"<?xml version='1.0'?>\n<checksum value='abc'/>\n"
        checked_out = generated.replace(b"\n", b"\r\n")

        self.assertTrue(canonical_content_matches(checked_out, generated))

    def test_rejects_changed_metadata_content(self) -> None:
        generated = b"<checksum value='abc'/>\n"
        changed = b"<checksum value='def'/>\r\n"

        self.assertFalse(canonical_content_matches(changed, generated))

    def test_discards_stale_tracebox_version_during_rotation(self) -> None:
        current_version = "0.1.0-alpha.6"
        stale_version = "0.1.0-alpha.5"
        tracebox_modules = ("tracebox", "tracebox-native", "tracebox-ui-compose")
        fixed_coordinates = (
            ("org.maplibre.compose", "maplibre-compose", "0.13.1"),
            ("org.maplibre.compose", "maplibre-compose-android", "0.13.1"),
            ("org.maplibre.gl", "android-sdk", "13.4.1"),
            ("androidx.graphics", "graphics-path", "1.0.1"),
            ("androidx.datastore", "datastore-core-android", "1.2.1"),
            ("com.android.tools.build", "bundletool", "1.18.3"),
        )
        components = [
            f'<component group="io.github.tracebox" name="{module}" version="{version}" />'
            for version in (stale_version, current_version)
            for module in tracebox_modules
        ] + [
            f'<component group="{group}" name="{name}" version="{version}" />'
            for group, name, version in fixed_coordinates
        ]
        metadata_text = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<configuration><verify-metadata>true</verify-metadata>'
            '<verify-signatures>false</verify-signatures></configuration>'
            f'<components>{"".join(components)}</components>'
            '</verification-metadata>'
        )
        release_inputs = {
            "tracebox": {"version": current_version, "artifactSha256": {}},
            "maplibre": {
                "composeCoordinate": "org.maplibre.compose:maplibre-compose:0.13.1",
                "resolvedAndroidCoordinate": "org.maplibre.gl:android-sdk:13.4.1",
            },
            "nativeLibraries": [
                {
                    "coordinate": "androidx.graphics:graphics-path:1.0.1",
                    "name": "libgraphics.so",
                },
                {
                    "coordinate": "androidx.datastore:datastore-core-android:1.2.1",
                    "name": "libdatastore.so",
                },
            ],
            "tooling": {"bundletoolCoordinate": "com.android.tools.build:bundletool:1.18.3"},
        }

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = root / "verification-metadata.xml"
            inputs = root / "release-inputs.json"
            metadata.write_text(metadata_text, encoding="utf-8")
            inputs.write_text(json.dumps(release_inputs), encoding="utf-8")

            generated_count, selected_count = select_metadata(metadata, inputs)
            rendered = metadata.read_text(encoding="utf-8")

        self.assertEqual(generated_count, 12)
        self.assertEqual(selected_count, 9)
        self.assertIn(current_version, rendered)
        self.assertNotIn(stale_version, rendered)


if __name__ == "__main__":
    unittest.main()

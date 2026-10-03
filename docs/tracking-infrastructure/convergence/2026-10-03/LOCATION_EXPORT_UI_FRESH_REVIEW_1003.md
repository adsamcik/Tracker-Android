# Location export UI: independent static source review

Date: 2026-10-03. Status: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**.

Original user requests, follow-ups and approvals were inherited, including one chosen-format file, ordinary backup of every saved Location point with honest unknown origins, local calendar dates, no tracking activation, simple localized copy, and transfer cancellation/cleanup. No production edits, Git operations, recursive delegation, compilation, tests, lint, schema, device or other validation gates were performed. This receipt covers a coherent source selection, not integration or release readiness.

## Exact selection

Reviewed `LOCATION_EXPORT_UI_FILES.json` at final SHA-256 `66ba0977011c63cbd64e6a118504a17abd84cffd3ce2621dc5e6a5b6816d17e3` (base recorded by the coordinator: `31d5340b0cb390294f4170ab2a6b7564e2292a12`). All **34** declared source pins matched their actual selected source or explicit after-snapshot in the initial freeze. The final narrow recheck below verifies the three changed sources; the other 31 pins are unchanged. The manifest contains the complete SHA-256 inventory for all 28 `feature/import-export/src/main/res/values*/tracking_location_export_strings.xml` files. Their bytes are unchanged from the reviewed app-owned copy set; their owner is now the import/export feature. They contain exactly the two intended resource IDs and use existing application locales.

| Selected source | SHA-256 |
|---|---|
| ExportFormatDialog.after.kt | `a9023a5da27706bf0e583d559433f56a70f30297ce8a2bcc7f1ad07dad08d973` |
| ImportExportComposeActivity.after.kt | `e542e496dcf4659a163596dc261bcf21263501b20cb19abb38bc57ea30455a47` |
| LocationFileExporter.kt | `8a60802253e3c1b89c0a1a37d2b433c83c7f998d9f0d625a3ab61c3112c7f095` |
| SourceFileShareIntent.kt | `88e50d1eb289adecccf09e7a785e1eb3fdf7f9f59cc944c05537f9452d42ac4d` |
| LocationExportChoiceTest.kt | `d5a40b4a4965e50e2723bfaa71079eca6b238608bb4bab54bdc98d49c21c928a` |
| SourceFileShareIntentTest.kt | `952aea503d78c54f275c9c6d7ad202d0fb86e19ccffa54648bd134902e35c53f` |

Selective patch pins: `LOCATION_EXPORT_UI_SELECTION.patch` = `582bd6f4dd7a09eae89c18402ba5da0047716f1b2b21850ed9c445fa2a65e4da`; `LOCATION_EXPORT_SHARE_SELECTION.patch` = `a7e8a2fa0e858976c3044b629a2534360c217614f92f760d1dd7c6a3d20cf445`. Before-snapshot pins: dialog `fce27cb54416a507bb94034e2d81725510300ea5a52c7bfbecb635fc8c25f2b0`; activity `f915ef9ec13cc2b42e60540c5d60348714844951879090b190d7e9ccc84bfe16`.

## Findings and static closure

1. **Reflective constructor:** the activity reads a dynamic Class from its Intent and calls `getConstructor().newInstance()`. The exporter now has `@Keep`, retaining its public no-argument overload. The actual cached AndroidX annotation JAR includes `META-INF/proguard/androidx-annotations.pro` with `-keep @androidx.annotation.Keep class * {*;}` and constructor rules. An additional duplicate keep rule is unnecessary. The existing choice case now authors the real no-argument reflection call; no minified artifact was built or inspected as a validation result.
2. **Borrowed trip summary:** the selected activity snapshot at lines 396-414 routes Location through the small `sourceFileShareIntent` helper. It supplies one stream URI, the chosen MIME type and a read grant, with no trip metrics, `EXTRA_SUBJECT` or `EXTRA_TEXT`. Existing retained-generation handoff and failed-handoff directory cleanup remain in the caller. Other formats keep their existing branch; held radio changes are excluded.
3. **Choice localization:** the Location enum reuses existing localized app `setup_source_location`. The two descriptions/messages are owned by import/export and used by both the picker and actual exporter warning. The source adds only `@Keep` and the warning override to the reviewed data core: removing those three lines reproduces prior core hash `30b9159acfbb139c0ec4b636cb8ecf270929802646aa701ce12a85a0ab7f3c16`.
4. **Actual confirmation copy:** the exporter no longer inherits the generic warning about other archive data. Both chooser and Save/Share confirmation use the reviewed concise Location-specific message; the authored choice case asserts that equality.

No remaining concrete defect was found in this exact selection. The dialog exposes only the registered Location format, launches the existing activity, and reaches the source-owned reader with `requiresLocationData=false`. Calendar dates use the existing local-day expansion; the Location codec writes one `.trackerlocation` file. This adds no provider demand, native trust restriction, file format, schema or generic export framework.

### Final narrow Share simplification recheck

The optional hardcoded `Tracker $sourceName data` subject earned no useful role in file sharing. Removing it preserves the approved file-only Share behavior and removes untranslated copy and an unnecessary source-label dependency. Re-read only the helper, its test, the selected activity after-snapshot and selective Share patch; all three source hashes above match the refreshed manifest. The helper has only `(file, mimeType)` arguments, its selected caller passes those exact arguments, and the authored test explicitly withholds both subject and text while preserving the stream, MIME and read-only grant. No remaining defect was found in this change; no execution or broader repeat review occurred.

## Evidence limits and remaining effort

Three cases are **authored, unexecuted**: choose Location, dismiss its confirmation, and inspect the one-file sharing intent. They do not prove Android SAF, another app receiving the URI, accessibility rendering, release shrinking or physical device behavior. The data core and shared transfer draining are separate implementation inputs. Held Wi-Fi/Cell UI/exporters, Location Replace, session bundles, unfinished external-file recovery notices, final gates and integration remain excluded and required. All 25 full-chain prerequisites remain open.

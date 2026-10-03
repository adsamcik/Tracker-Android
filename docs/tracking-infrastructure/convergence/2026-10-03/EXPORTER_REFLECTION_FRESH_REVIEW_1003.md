# Registered exporter reconstruction: independent static review

Date: 2026-10-03. Status: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**.

Original task messages and approvals were inherited. This slice implements the existing exporter reconstruction behavior reliably, using the same AndroidX annotation already selected for Location. Review was read-only except for this receipt: no production edits, Git operations, delegation, compilation, tests, shrinking, lint, schema, device/provider work or integration gates were performed.

## Exact selection

Base recorded by author: `3291c4b15df99194daee7c4135b4f223e1a36a93`.

- `EXPORTER_REFLECTION_FILES.json`: `cf9ce2f02961e1ce024e9b5d872e37dbc55affdbf3986466dcfc05f5c323cf28`.
- `EXPORTER_REFLECTION_SOURCE_BOUNDARY.md`: `cf4cdd882429aa78975d8ce419d7d59eb76f875811e56f84a6a8e5a4906a21e4`.
- `EXPORTER_REFLECTION_SELECTION.patch`: `36579f7d29f8efdb722153279dc4271357e5d6d4e3b1f264481ae045dd9a382e`.

All nine actual-source pins match the manifest; all selected pins also match. For each of the eight production sources, removing exactly the Keep import and class annotation, then normalizing CRLF to LF, reproduces the declared baseline hash. For mixed DatabaseExporter, that comparison uses the explicit selected after-snapshot, whose prior baseline is preserved separately. Its actual working-source backup draft is not selected.

| Selected source | SHA-256 |
|---|---|
| GpxExporter.kt | `f1c24ae40fa2f25bb7ea04ebfbb916da85a4f186f0780b3f4bb7898e6e7ed05c` |
| KmlExporter.kt | `58ae27ecb5ffb69cda4be7b3cf0bc2cb04b11e959e1666d2bb39470825083324` |
| JsonExporter.kt | `24e23991f7902a046bd06d4a61a56785e02615bc29579019972bbb0654243c73` |
| DatabaseExporter.kt selected after-snapshot | `adee8fd7b1db151838bfa8dbd290af835d53684a0a424998499abd618b9284b9` |
| PortableStepsExporter.kt | `3bd9daa4cb3e22a730df78630aed9413c2106756f9b4c8e2b890022b777cd952` |
| PortableAmbientStepsExporter.kt | `6a114e32dace61d51f6998270ecc66bcdc8fde6bd3042ff5ab33692659cff61a` |
| PortableActivityExporter.kt | `700d7e792012784cf0bbd569e68e8c0bee72d635284a81e1841e2a5ed2232454` |
| PortablePressureExporter.kt | `1303dbb4adf45a0e51b1bee5dc6e04c02442af715e841fe7f4b4c6ca5ce8b66c` |
| RegisteredExporterReconstructionTest.kt | `c50a06f5f549f52ada570e4bf426a6f089f15b59b7e2e34b1605b31a4fa20b04` |

## Static findings

No remaining concrete defect was found in this exact selection.

The app format chooser obtains the registered exporter's Java Class and passes it in the existing Intent. `ImportExportComposeActivity.kt:105` invokes its public `getConstructor().newInstance()`. The eight sources now retain the class and constructors through `@Keep`; their existing implicit or all-default no-argument construction is otherwise unchanged. No factory, registry, dependency, file format, output, collection demand or consumer-rule change appears in the selected patch.

The actual cached AndroidX annotation JAR's `META-INF/proguard/androidx-annotations.pro` includes `-keep @androidx.annotation.Keep class * {*;}` and constructor rules. The feature already uses this annotation mechanism and the app enables optimized release shrinking. A redundant keep rule is unnecessary. This establishes the source mechanism, not evidence from a built/minified artifact.

The new test obtains each of the nine committed registry IDs, reconstructs its actual public no-argument Class and compares class, MIME, extension, date-range support, Location requirement and sensitivity metadata. It exercises the same reflection operation as the Activity. It does not mistakenly inspect the binary-retained annotation at runtime or resolve a provider/database through export. Existing test conventions already supply JUnit Jupiter parameterized-test dependencies; no test dependency addition is required.

## Evidence limits and exclusions

One parameterized test with nine cases is **authored, unexecuted**. It is not compilation, Hilt, R8, real file export or device evidence. Release shrinking and the complete convergence gates remain deferred.

Held Database backup discovery/locking/scope/cancellation behavior, radio exporter definitions, radio registry/test/consumer-rule changes, Location Replace, transfer/scope drafts and full session selection remain excluded and required. The earlier Location source is only an unchanged test dependency. This receipt does not reopen or certify it, close any full-chain prerequisite, declare the assembly complete or establish `dev/v10` readiness. All 25 full-chain prerequisites remain open.

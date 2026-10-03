# Native Wi-Fi marker selection

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Independent static review:
`../2026-09-30/NATIVE_WIFI_CARRIER_FRESH_REVIEW_1003.md`.

Select the twelve entirely owned tracked paths described in
`../2026-09-30/NATIVE_WIFI_CARRIER_FILES.json`: native fact/entity integrity,
shared WAL linker, existing unshipped migration/preflight, writer/classifier/
maintenance, and their core/writer/history test sources. Their dependencies
already exist in the candidate. This closes the durable native marker and
shared writer/history/retention/source-export audit slice at source level.

The three mixed owner-query/file-export/test deltas remain held. Their exact
patches are preserved with the authored receipt; the complete native owner query
and file exporter definitions require their own final selection. In particular,
do not stage either full radio exporter or NativeRadioExportChildrenRoomTest from
this marker acceptance. No whole-file exporter readiness follows from this step.

No schema generation, compile, tests, lint, device/provider work, convergence or
integration ran. The migration is authored source; released-data reopen and
unknown-database preservation remain final proof requirements. All 25 full-chain
prerequisites remain open.

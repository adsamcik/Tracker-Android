# Independent source review — Cell R8 policy/consent erase scope

Status: narrow static acceptance, IMPLEMENTED_UNVALIDATED. No compilation, tests, gates, Git mutation, providers or device work was executed.

Exact reviewed pins:

- FILES.json: `9ca95a40fcd16d3ff46530c9dd4b0f65477ab2a389a11642969858eb7acbcb46`.
- SELECTION.patch: `1d5983115b69a58d9abe4603e4e70aac62e9fe15fc42e603eb43f0cf1b8e9243`.
- SOURCE_BOUNDARY.md: `83a7d9506b4604ae8be9a9e804ded7a9ed700dd632b4c6c99b6d3b110e0c6915`.
- Selected maintenance: `047d3ffac17cdaed23fe2b6464222ddc08c1ef8da1b5e4a284f4614bd8fe66ba`.
- Selected test: `a43972d50b3bbb3a374029bac4e476c5656b0186471c8073b8346c1a62f5d4d6`.

Both selected and BASE snapshot hashes match their manifest. Reviewed SELECTED snapshots and the exact delta, rather than the moving actual files now receiving R7/R10 work.

The original Cell R8 finding is repaired. The local event-authority key includes existing, independently authenticated policy and consent alongside original event and logical/run scope. The separate original-event ordinal/WAL-integrity check remains unchanged; incompatible raw identity still blocks deletion. Canonical run scope/deletion generation remains deduplicated by the existing run key. No database schema, durable identity or public API is added.

Two authored cases exercise actual Room/ingress/projection writer and real source erase, not merely map equality: one same-run callback spanning two policy/manifest/plan revisions under the same physical configuration produces two facts and children, erases them under one fence, preserves original WAL, rejects replay and repeats idempotently. The second additionally rotates consent, performs actual retention to two receipts, removes WAL, and erases the independently validated proof-only siblings under one fence. The fixture uses the actual accepted timeline contract: first manifest retains the original MANUAL origin and later manifest is POLICY_RECONCILIATION. Cell has no contradictory action-origin predicate, so no Cell provenance relaxation was introduced. Existing fixture defaults retain their prior two-session behavior. Both cases are unexecuted.

No additional blocker was found in this narrow Cell R8 repair. Wi-Fi R8, R7 child authentication, R10 no-observation settlement, shared Steps/Pressure acknowledgement origin handling and all 25 full-chain prerequisites remain separate. This receipt closes only the Cell R8 static finding; it does not accept the original entire Cell packet or declare integration ready. Earlier rejected evidence remains intact.

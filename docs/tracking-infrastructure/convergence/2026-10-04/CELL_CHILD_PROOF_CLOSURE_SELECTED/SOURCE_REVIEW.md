# Independent source review: Cell saved-child qualification (R7)

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

## Scope and exact input

Review-only inspection of the frozen four-path selection, original rejected Cell evidence, existing child authenticator/DAO, approved source detail and independent-purpose rules, and all four newly authored actual Room cases. No source edits, Git mutations, executable gates, providers or recursive delegation.

- FILES.json SHA256: `73e1f0d4af99fd23313eb1fe3c8e3311199164be7667768caf8a8b3aa4d9f17c`.
- SELECTION.patch SHA256: `57ac83c34c57325cae496bfe7382d458133f6e4a67e51bb441cfdd99fd906f62`.
- All four BASE and SELECTED files independently matched their manifest hashes.

## Result: narrow static acceptance

The original R7 mismatch is repaired in both existing Cell-private readers. Lifecycle materialization and exact fact lookup now load saved children for the exact four-part fact key, authenticate their original payload values and checksum through the existing overload, and require their resulting qualification to equal the independently qualified purpose indexes. Removing or changing mandatory v6 children therefore cannot leave these readers trusting a fact that History rejects. The legacy summary branch remains unchanged.

The already authenticated raw payload is limited to 256 children. Each query uses the actual payload size plus one, at most 257. This admits a valid full 256-tower delivery and exposes an extra saved child rather than silently hiding it at the limit. The DAO orders by original item index; the existing authenticator checks exact accepted count/indexes, fact ownership, provider times, wall-time derivation, available tower identity, radio/signal values and checksum. No new storage, API, abstraction, consent or capture flow is added.

Four authored cases are meaningful and unexecuted:

1. Actual writer/lane saves 256 towers, verifies lifecycle proof, then inserts index 256 and requires integrity failure.
2. Actual ingress and writer save two independently authorized historical-session groups; removing one fact's child retracts only that materialization, preserving the sibling.
3. A v6 exact lookup succeeds before deletion; deleting its saved child revision yields integrity failure in exact lookup and failed/empty History.
4. Changing a saved tower identity similarly retracts both exact lookup and History.

The last two use actual Room persistence and the existing per-reading authority fixture; the first two use real ingress/writer/lifecycle source paths. Assertions are not mocked success results. The adapter-test BASE exactly preserves the separately reviewed R8 fixture.

## Remaining boundary

This accepts only the R7 source repair and test authorship. It does not establish passing tests or full implementation readiness. R10 empty Cell purpose settlement, R11 later-manifest Steps/Pressure acknowledgement, Wi-Fi historical capture, future-dated sibling handling, selected-date transfers and all 25 full chains remain separate work. Original rejected packets and findings remain preserved.

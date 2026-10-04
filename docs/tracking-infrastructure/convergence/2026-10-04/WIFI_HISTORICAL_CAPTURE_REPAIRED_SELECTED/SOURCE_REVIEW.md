# Independent source review: Wi-Fi historical capture, first W1 repair

## Disposition

**REPAIR_REQUIRED for the two narrow relationships below.** The original W1 missing run-retirement acknowledgment is repaired in this snapshot. Its current ACTIVE-registration path still lacks the accepted-before-cutoff relationship already present in the accepted Cell counterpart, and newly exhausted authority-query reads use the wrong existing result reason.

This is static source review only. No compilation, tests, lint, schema/diff check, CI, Git mutation, provider activation, device/UI/battery work or recursive delegation occurred. All 28 listed authored scenarios remain NOT_RUN. This receipt does not declare assembly completion, integration readiness or any full-chain gate.

## Exact reviewed input

Parent: c315f7dc1c649a54c575485bd8de18e16a5fa162.

- PACKET_PINS.json: 27683f864bac2999beebc35d8c7c313e4b534bed3f0016b793fdc605add20c3b
- FILES.json: 5ba55b3baada3c2f711a1ab7afc6162b04f6032a9de6403068810d2fc31ce7cb
- SELECTION.patch: 7d7aa4bb2b6c27442705605a35aff1ef5c62f00b47a4180fd0222da1a42f78fa
- W1_AUTHORING.patch: 30f5f4b0ec84c1b2b0d7ae2a1b37dd634784a340094cc37a4de62dfe55b0a487
- SOURCE_BOUNDARY.md: 8233699e0b3f4e6fee0b0ae57e95c0654256bd0eb4ff5119b2ec17f3c2a27401
- Selected WifiCapturedPortableTransfer.kt: 61f25d7c5a2f701a042785ac543c672acfb3a5665f9fd76f557848f64189ed32
- Selected WifiWalQualificationAdapterTest.kt: 326c8291fd6a031263b23c27a03610ae4e8a323c719ca039213b35135271bfbb

All 14 BASE and SELECTED file pins, all packet artifact pins, all 64 original and five current dependency snapshots, and all 14 normalized AFTER snapshots matched their manifests. The rejected original packet's pinned artifacts and SOURCE_REVIEW.md remained unchanged.

## Concrete findings

### W3 — ACTIVE provider acceptance must precede the selected acknowledged cutoff

SELECTED WifiCapturedPortableTransfer.kt:747–778 checks reserved <= accepted and accepted <= the matching start acknowledgment. Its ACTIVE shape has no accepted <= shared-retirement cutoff comparison. The new matchesPortableWifiSharedSettlement at945–961 checks the cutoff against run start, completion and completeness, but cannot check the provider's acceptance time.

An empty selected session exercises no WAL observation qualification. In its existing real Room fixture, move provider acceptance elapsed/wall times after the selected cutoff and move the original start acknowledgment after those acceptance times. The existing shape, action, demand-closing, lease and empty-tail comparisons can still agree, so the exporter accepts an impossible lifecycle ordering.

The accepted Cell source already enforces both accepted clocks <= shared cutoff in W1_DEPENDENCY_INPUTS/.../CellCapturedPortableTransfer.kt:1830–1831. Apply the same relationship to the Wi-Fi ACTIVE shared path. Author an actual empty-session negative with acceptance and start ACK moved together, so failure cannot be explained by the older accepted <= ACK check. No new authority, identity, table or API is needed.

### W2 — Added authority-query exhaustion must keep the existing typed overflow reason

SELECTED WifiCapturedPortableTransfer.kt:444 returns true from the any predicate when incrementing its shared-retirement read beyond the existing 96-query budget. The outer expression then returns WRITER_AUTHORITY_UNVERIFIABLE. The initial budget check at434 is also folded into that authority mismatch.

The existing exporter has DEPENDENCY_OVERFLOW and overflow() (99–102,1192), and the existing audit counter throws WifiCapturedMaintenanceLimitExceeded for the same budget. Keep that existing reason when the added query cannot be performed. This is fail-closed today; the defect is the misleading result classification, not emitted untrusted data. A direct outer return of outcome(overflow()) is sufficient.

Existing real Room source cases include “portable authority reads stay bounded as selected WAL rows grow” (3668) and “portable export fails closed at the bounded Wi-Fi completeness limit” (3873). They establish existing bounded-query/overflow semantics but are not an exact 97th added-retirement-read scenario. Do not claim that coverage or create a new testing abstraction merely to mirror this guard.

## Original W1 closure confirmed

The selected path now reads rawRunRetirement with the exact logical session/run/source/instance/registration tuple, within the existing authority budget. The existing DAO uses a raw nullable projection with LIMIT 2; singleOrNull and validatedOrNull reject ambiguous or malformed storage.

The matching receipt requires acknowledged COMPLETE, exact run lease, completeness tail, source sequence and coverage, completed app drain, no failed admissions or unresolved callbacks, supported shared-owner removal/flush outcomes and callback-entry barrier at least the saved tail. Final selected demand closing matches the receipt's elapsed and wall cutoff; earlier policy demands may close earlier. Other capture/background demands remain independently active and cannot substitute for this final closing.

Existing retired-provider and legacy payload behavior are preserved. The first empty positive now uses an acknowledged retirement and a genuinely active sibling; the background positive verifies its active demand and physical registration are unchanged. The added nonempty cases use real durable ingress/writer/lane paths before export.

## Scope and preservation

The remaining original historical capture/lifecycle/child-owner/retention/erase/history selection is unchanged except the current-parent R11 origin condition is preserved. The existing Cell child-authentication helper remains byte-equivalent to its parent body, including raw payload size + 1 and exact stored-versus-qualified child comparison. No Cell/schema/ambient provider implementation is selected by this repair.

No additional material blocker was found in the two-path W1 delta. Repair and independently recheck exact revised snapshots; retain this first repaired receipt and the original rejected packet. Full source subsets, shared all-day status, history/product assembly and all 25 convergence chains remain open.


# Independent source review — Wi-Fi historical capture, W1 V2

## Disposition

**ACCEPTED_FOR_STATIC_SOURCE_SELECTION — IMPLEMENTED_UNVALIDATED.** This recheck closes W2 and W3 from the first W1 repair. The original W1 shared-settlement repair remains accepted within the reviewed source scope. No new concrete blocker was found in the two-path V2 delta or its preserved fourteen-path composition.

This is an independent, review-only source inspection under IMPLEMENTATION_ONLY. No compilation, tests, lint, schema checks, diff checks, CI, provider activation, device work or Git mutations were performed. All 25 full-chain prerequisites remain open; this receipt does not declare assembly complete or integration ready.

## Exact input

- Parent: `c315f7dc1c649a54c575485bd8de18e16a5fa162`.
- `PACKET_PINS.json`: `5c3cab18d71cb5e298f749348def3b0d84b7abb4b84ff2cd3c424a7b1f992af9`.
- `FILES.json`: `bc7cb62f3d8142df4e1d8e11c5fd0e7c632314725cd4b246b1ebe912334376db`.
- `SELECTION.patch`: `eabdbb4889c7ea46f7f2a2a4d69c9545329b9e97684a6d10bc6e7d0bf52d599f`.
- `W1_V2_AUTHORING.patch`: `a0db833f5bad9e63cf01e44561f8d41acd2ad88add03f482e20835669b939c91`.
- `SOURCE_BOUNDARY.md`: `2adb47b48e3291eabf574973dacfd23159ff7962f629e8c669af8578df97fc2a`.
- Selected `WifiCapturedPortableTransfer.kt`: `7c2181c329e822f91bd0e67beb8a66e06dcbcc49b0ef52f7811735fe4efb6d01`.
- Selected `WifiWalQualificationAdapterTest.kt`: `4ee687d0556618d6452c0ef61a0e8e52931b3f12a2091b8567065678d9b55900`.

All fourteen BASE, SELECTED and raw AFTER hashes, the packet's eleven pinned artifacts, sixty-four original dependency files, five W1 dependency files and two V2 BEFORE files were checked against the manifests. No mismatch was found. V2 BEFORE normalizes exactly to the first repair's two selected files. The other twelve SELECTED files remain byte-identical to the first repair, preserving the reviewed R11 origin predicate and Cell child-authentication repairs. Frozen snapshots, rather than changing working sources, were reviewed.

## Rechecked repairs

### W3 — provider acceptance precedes the selected session's End

`SELECTED/.../WifiCapturedPortableTransfer.kt:443–453` now requires both stored provider acceptance clocks to be at or before the exact selected run retirement's acknowledged cutoff. The exact raw retirement lookup still uses `(logicalTrackingId, serviceRunId, sourceKind, sourceInstanceId, registrationGeneration)`, bounded ambiguity rejection and existing nullable-row validation. The existing lease, drain, tail, failed-admission, gap, callback barrier and final selected closing-authorization checks remain intact.

The added Room cases at `SELECTED/.../WifiWalQualificationAdapterTest.kt:799` and `:815` use the existing real shared-owner fixture, including its exact retirement row. Each independently moves only the tested acceptance clock beyond End and moves the corresponding START acknowledgment beyond acceptance. Thus the old `accepted <= START acknowledgment` relationship remains valid, while the new cutoff relationship is specifically false. The sibling session and physical registration remain genuinely ACTIVE. These are meaningful authored negatives for the previously empty-session hole, not a replacement helper that mirrors the predicate.

### W2 — exhausted authority budget keeps its typed limit result

`SELECTED/.../WifiCapturedPortableTransfer.kt:432–434` checks the initial count before nullable authority extraction. The added exact-retirement read at `:444` now exits the enclosing operation with `outcome(overflow())` when the existing 96-query budget is exhausted. Because Kotlin `any` is inline, this is a nonlocal return rather than an authority-mismatch element result. Both exits reuse the existing `DEPENDENCY_OVERFLOW` result and existing budget; no extra type, storage mechanism or test-only budget injection was introduced.

## Preserved behavior and test boundary

The original W1 repair still permits an ended selected session to export while a separately authorized capture or background purpose keeps one physical owner ACTIVE. It authenticates only the selected run's acknowledged drain and immutable final closing cutoff; earlier policy windows may close earlier. Unrelated purposes need not end. Legacy/physically retired paths and recorded completeness requirements are preserved.

The inventory contains **30 authored cases, all NOT_RUN**: the original fifteen, thirteen first-repair cases and two V2 cutoff cases. Existing real source scenarios cover bounded authority reads as WAL rows grow and completeness-limit `DEPENDENCY_OVERFLOW`. They do not specifically force the added raw-retirement lookup to become the 97th query; no claim of such executed or authored coverage is made. The two budget exits were reviewed directly in source. No new architecture, database schema, user format or unrelated behavior was added.

Preserved prior evidence: original `WIFI_HISTORICAL_CAPTURE_SELECTED/SOURCE_REVIEW.md` remains rejected for W1, and first `WIFI_HISTORICAL_CAPTURE_REPAIRED_SELECTED/SOURCE_REVIEW.md` remains rejected for W2/W3. This receipt supersedes those findings only for these exact V2 selected bytes.

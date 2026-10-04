# Cell source erase policy scope — source boundary

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Two production/test source paths; two actual Room/ingress cases authored and unexecuted. No compiler, test, lint, schema, device, provider, CI, integration or Git actions.

## Exact selection

BASE is the two selected paths from `CELL_HISTORICAL_CAPTURE_SELECTED`, based on f3b026e4d1fa96e38c0bfd912abdc7c956feb0bf. BEFORE preserves actual working bytes, which match that predecessor text after line-ending normalization. SELECTED applies only R8; SELECTION.patch contains that narrow delta. The predecessor runtime and revised radio event-index packets remain required separately. Do not replace/stage the complete mixed working files wholesale.

## Production trace

`deleteCapturedCellFactsAfterConsentReset` still authenticates current revoked capture consent, quiescence, every retained fact/WAL/receipt, and canonical scope before deletion. Its local event-authority key now distinguishes the already authenticated policy revision and capture consent epoch alongside original event and logical/run digest. The independent event-ID-to-ordinal/WAL-integrity guard is unchanged, as is the one canonical deletion generation/fence per logical run. No storage, identity, public API, format, or user-visible policy changed.

## Authored scenarios

1. Real ingress admits one original v6 callback spanning two policy/manifest/plan revisions in the same run with unchanged physical configuration and consent. The actual projection lane/writer produces both facts and original-index tower children. Revoking capture and erasing removes both facts/cursors/children, creates one run fence/generation, preserves original WAL integrity, rejects both replay groups, and repeated erase is AlreadyDeleted.
2. The same actual chain rotates consent too, then actual retention removes both fact payloads and keeps two receipts. After WAL removal, capture erase authenticates those independent policy/consent receipts, removes both under one run fence, and remains idempotent.

The existing historical fixture defaults remain the original two-session behavior. Its optional same-run branch adds real policy/consent rows, a physically equivalent plan revision, POLICY_RECONCILIATION manifest, exact retired demands/authorization boundaries, and final original session/run metadata. No actual Android provider is activated.

## Explicit remaining work

R7 mandatory Cell children in lifecycle/exact lookup remains separately root-owned. R10 empty Cell session settlement while a sibling owns the physical provider is still open and will be investigated separately. Wi-Fi R8 is separately owned. This packet proves neither test passing nor complete source/assembly readiness; all full-chain prerequisites and deferred convergence/device evidence remain open.

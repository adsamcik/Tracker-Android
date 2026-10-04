# Independent prepared Start repair review — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Receipt transcribed by root
from `/root/assembly_coverage_review` messages and final acceptance. The reviewer
had the original user requests, approvals, exact source diff and authored test
source. No compilation, tests, gates, activation or integration was performed.

Accepted exact actual source pins:

- Coordinator: `8af9c46a76c22bd8b7f69893218714890ae2109036b2745fb946fa10e0525243`.
- Test: `a6e8ca4f4e3bb7969ee996c10dcef907b6265c9006f72b187631f0758519c7f1`.

The existing current-settings owner boundary now runs after exact manual Prepare,
including permission repair when cached reconciliation is complete. Current
choices/permission/Off remain owned by the existing reconciler. A controlled
Prepare fixture does not establish real owner retirement or permission delivery.

Two concrete cancellation findings were repaired before acceptance:

1. Cancellation after a resumed Start compensated PREPARED but left the prior Off
   resumed. The exact-generation existing restoration now also runs.
2. An initial 250 ms wrapper around Off could expire before the command gate
   durably reserved it. That wrapper was removed; reuse the existing awaited
   NonCancellable restoration used before successful Prepare. Its generation
   comparison occurs under the lifecycle gate before exit/STOP commit and cannot
   overwrite a newer Start/End/Off. No global reservation-wait bound is claimed.

Eleven newly authored methods cover thirteen scenarios; none ran. Cancellation
fixtures invoke actual coordinator compensation with mocked storage. Resumed
fixtures mock the existing Off endpoint, check exact generation and include a
300 ms delayed settlement. Existing real lifecycle-authority test source covers
newer-command refusal separately. The generic MockK suspend-lambda stub appeared
plausible by static inspection, with compilation deferred. The nonmanual guard
case was renamed rather than claiming real automatic request evidence.

Inherited unresolved limitation: the existing restoration ignores UNAVAILABLE
and storage exceptions; failed durable Off storage can leave resume intent uncleared.
That remains a full collection-control/recovery prerequisite. This narrow repair
does not close all-day Steps, original Start, Off, Hilt, provider or device proof.

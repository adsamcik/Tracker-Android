# Service ownership review repair

Phase: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. Tests: **NOT_RUN**.
Independent final revision review: **ACCEPTED_STATIC_ONLY**; see SOURCE_REVIEW.md.

The reviewed predecessor `FILES.json` is preserved at
`REVISION_BEFORE_OWNERSHIP_MONITOR/FILES.json`, SHA-256
`b689663b30e23540cb905b02f43bc662c7599c918b8101144c81aa22de2db5d0`.
Its review patch SHA-256 was
`07ef76efa0dd09bc92364075138cb04688b42e1bdb5ac4782b00cf7cc409f96e`.
Final current pins are in the packet's `FILES.json`. Only `TrackerService.kt` and
`PreparedStartCancellationReleaseTest.kt` changed selected source bytes in this revision.

## Concrete finding

`CoreService` jobs run on the default dispatcher while Android can deliver a replacement on its
main thread. Volatile runtime state could not make the old final match and subsequent unconditional
descriptor/foreground clearing atomic. A successor could arrive between those operations.

## Smallest shared boundary

One private service instance monitor protects synchronous ownership mutation. It is not persisted
and is not a startup engine. Exact token/command helpers guard both sides of suspended work.

| Mutation | Current boundary |
| --- | --- |
| Initial Android delivery/flight adoption and providerless foreground | Same monitor; private adoption helper |
| Redelivery predecessor consumption and replacement adoption | Same monitor; exact predecessor tuple |
| Rebase enqueue ownership/flight flags | Same monitor; exact replacement tuple |
| Accepted session/descriptor/origin and foreground type | Exact helper under same monitor |
| Initial initialization metadata/child installation | Exact helper; command and Stop checked |
| APPLY started/applied and returned descriptor | Exact helper; suspended APPLY outside monitor |
| Initialization failure metadata and Stop request | Exact helper; compensation/storage outside monitor |
| Descriptor observer/save/reconfiguration results | Same monitor; exact request/run or expected descriptor comparison |
| Rejected shell/runtime/foreground/flight release | One atomic check and release under same monitor |
| End/foreground type fields and completed teardown reset | Same monitor; physical join remains separate |

Cancellation will not discard `applyStarted`, `applied` or platform-owned runtime state. The existing
durable Stop/init join owns partial physical cleanup. A valid sibling host retains foreground hosting.
All compensation, Room, provider, disk and physical-join suspended operations remain outside the
source-private monitor. Existing short platform foreground calls remain synchronous and reentrant.

## Authored cases

The actual service retirement method still covers matching cancellation, 250 ms compensation timeout,
unmatched/platform-owned/partial/applied receipts and successor arrival during compensation.
The new final-check collision case uses two bounded threads and verifies the exact monitor identity
through the JVM thread information API, then checks successor runtime, descriptor, session, origin and
foreground fields. The independent-host case verifies hosting is retained, and the old-publication
case calls the actual exact ownership helper to prove it cannot copy flags onto a successor.

Room, foreground promotion and Android stop are controlled. Nothing here establishes executed tests,
Hilt composition, real Android delivery, provider retirement, device/battery behavior or convergence.

# Independent source review: original radio cooldown V2

**Verdict: ACCEPTED_SOURCE_ONLY / IMPLEMENTED_UNVALIDATED.** No material blocker
found in this bounded two-path selection. This is not convergence, integration,
provider, device, battery or release readiness. All eleven selected test methods
are **NOT_RUN**.

Reviewer: `/root/adversary_runtime_0930`, independently reviewing root-authored
source. Reviewed the inherited original user requests and approvals, current
`AGENTS.md`, task context, exact `BASE`/`SELECTED`, selection patch and source
boundary. The settled requirement is one saved active request per actual session
and its next allowed time, surviving restart and quality changes; passive delivery
continues independently. The latest user priority is completing tracking
architecture and the system. No new storage move, observation proof, API, table,
provider mode or backup behavior is selected.

## Exact input

Base: `1056464a1376e3574f3a744a6eaf5e3402accaf7`.

| Artifact | SHA-256 |
| --- | --- |
| `HEAD_BASE.json` | `11b16d0e48905be36c9533562d91931a4bd34d543c5c353452ee5de439858671` |
| `FILES.json` | `8a5bab344289dff19d1caae764a0822438e7b3b6007cd419f3486e3a76348eee` |
| `SELECTION.patch` | `7964aea8e07a76c538872115bfcf9a45aa928e6e01e7d640978a7fbafaac29de` |
| `SOURCE_BOUNDARY.md` | `f50b95709b220e2923b0c928ca647c9d45ef18710be110d14773bca6d6db15e6` |
| Selected `RadioActiveRequestAllowance.kt` | `5512a905a1290a55b5c7ef37170367749885f97fccd2b58757109824cf3e3019` |
| Selected `RadioActiveRequestAllowanceTest.kt` | `1b53c301ddba30602d43729c4a393b37252cc69b8684ca35e67bc4ce2834370f` |

The two selected files are under
`tracker/engine/src/{main,test}/java/com/adsamcik/tracker/tracker/source/runtime/`.
Their actual hashes match the manifest; the first packet remains preserved.

## Traced correctness

- Production lines 67–78 refuse a future deadline and commit the original
  interval together with session, boot and both clock readings before returning
  a grant. Later decisions use `maxOf(priorInterval, minimumIntervalMs)` at lines
  119–120. A later shorter profile cannot refund the spent cooldown.
- Lines 95–118 reject invalid arguments and partial, blank or incorrectly typed
  state. Lines 99–100 preserve the exact spent-session refusal before checking
  the other stored fields. A completely absent source reservation is fresh;
  state from an older unshipped candidate with no interval is unavailable for a
  new session, without inventing its missing interval. The user confirmed main is the released reference; this selection
  creates no migration of released data.
- Same-boot elapsed rollback refuses admission. Existing reboot wall rollback
  handling remains conservative. All clocks and intervals are nonnegative before
  arithmetic; subtraction is bounded and deadline addition saturates (121–128).
- The existing singleton mutex serializes inspect/reserve. IO stays on the
  injected dispatcher. Cancellation is rethrown (50–51, 80–81); a commit that
  completes before cancellation may conservatively spend the request, while
  cancelled callers do not receive a grant. False commit and storage exceptions
  return Unavailable, without clearing a cached spend or issuing a provider call.
- Actual unchanged Wi-Fi callers inspect at 1006–1009 and reserve under
  `providerOperationMutex` at 1038–1069. Registration, plan and current request
  authority are rechecked after reservation. Cell mirrors this at 1034–1037 and
  1131–1174. Both require an actual single capture-session ID. Shutdown fences
  share the same provider mutex (Wi-Fi 780–787; Cell 771–778). Passive callbacks
  have no ledger reservation. Source-specific preference keys remain independent.
- Runtime DI still uses existing shared controllers and singleton runtime/ledger
  classes; neither selected file adds a second physical owner or execution flow.

## Authored test source and limits

All three earlier tests (30, 42, 58) and eight added tests (69, 84, 92, 101, 114,
127, 140, 149) were inspected. Their expected deadlines follow the selected
arithmetic: shorter later quality, reboot, stricter current quality/wall rollback,
partial state/source isolation, missing interval, wrong type/blank identity,
saturation/elapsed rollback and reported commit failure. The latter delegates to
a real preference commit and then reports false; it checks Tracker's response
and persisted/cache spend, **not physical disk failure**. No declaration issue
was found by source inspection; compilation and execution remain deferred.

Existing runtime test fixtures usually inject `testRadioActiveRequestAllowance`
(a mock), so their presence does not prove a real ledger/provider chain. No
additional executed cancellation, physical preference failure, post-reservation
race, process-kill or Android provider evidence is claimed. Complete source power,
all 25 assembly prerequisites and the final convergence gates remain open.

## Unchanged call-site inputs inspected

| Path under `tracker/engine/src/main/` | Working-byte SHA-256 |
| --- | --- |
| `java/com/adsamcik/tracker/tracker/source/runtime/WifiSourceRuntime.kt` | `b74b58e8fdded10365180f3a57ebf12a847bea4d3aa806f8b0c67871580725aa` |
| `java/com/adsamcik/tracker/tracker/source/runtime/CellSourceRuntime.kt` | `4f776ff138f228485132b0fa0bef4161314d26f6c9bed5b46af18b5f24df2b4f` |
| `java/com/adsamcik/tracker/tracker/di/SourcePipelineModule.kt` | `5962fde0080c44fde2e1556e5abc8646cbffd0e66ed230ecf9bf4ce435f00554` |

These are static call-site observations, outside the two-path selection. No
production/test source was edited and no Git, build, test, lint, schema, device,
provider or other convergence gate was executed during this review.

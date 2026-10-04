# Cold automatic callback: selected source boundary

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

The two selected paths implement only A3 from
`AUTOMATIC_ACCEPTED_STEPS_TRACE_INPUT/SOURCE_TRACE.md`. The committed production base is
`39718842674c35a5052c696c1a28d2c1f73c9b05`; its Background API bytes match the earlier reviewed
automatic-owner selection. `BEFORE_INPUT.json`, `BASE`, and `WORKING_BEFORE` preserve those inputs.
`FILES.json` pins the actual and selected files, normalized selection patch, unchanged dependencies,
and eleven authored test methods. All tests are **NOT_RUN**.

## Production change

`BackgroundTrackingApi.handleDurableActivityEvidence` first checks whether cached settings and policy
describe one coherent revision. It preserves the existing bounded retry when they are still loading.
It then reads the existing source-owned current availability directly, checks its available matching
policy revision, and uses the existing publication recovery classification. A lagging Background API
collector no longer suppresses a callback for which the source reader already has current Ready.

Ready still requires the existing exact `isCurrent` check, current policy/consent eligibility, matching
effect consent and automation epoch, supported capture/foreground plan, and the original fresh
transition callback context. Missing Ready never grants automatic control. Current-reader construction
or exact-read failures retry; cancellation propagates. Known unavailable classifications retain their
existing terminal or retry meaning. A current Ready value cannot borrow consent from the effect or
from the API's stale collector cache. The captured settings snapshot is reused for the start plan.

## Test-source composition and limits

The new Robolectric sibling composes the actual outbox dispatcher, actual
`BackgroundTrackingActivityAutomationEffectConsumer`, and actual Background API. The existing
singleton shutdown resets each test. Private cache fields simulate the independent collectors'
arrival order; there is no new production test-access interface. The source availability reader,
outbox DAO, policy validator, action store, hardware, and Android enqueue are fixtures.

Eleven methods cover initial cold retry; direct current publication despite collector lag; unavailable
or newer current revision; pending registration/provider/projection; known permission/transition/
authority loss; reader construction failure; exact-read failure and cancellation; failed exact current
check; changed consent/automation epochs; expiry of the original callback budget; and mismatched
settings/policy arrival. The positive case requests only Steps with Activity and Location capture off.
It deliberately leaves the outbox pending after fixture enqueue: START_REQUESTED does not prove
foreground, Room lifecycle acceptance, provider activation, storage, or product completion.

## Explicitly excluded

- A1: the existing fresh callback startup operation holds the lifecycle command mutex across a start
  reservation that tries to acquire that mutex. Its proposed cancellation-boundary repair is pending.
- A2: foreground acceptance currently enters the same lifecycle/startup lock twice. The shared
  acceptance coordination decision remains pending.
- Held manual Off/pending-start acceptance drafts, automatic full-chain activation, all source
  subsets, main integration, device evidence, and all 25 convergence chains.

No compilation, tests, quality/schema gates, provider/device work, or Git operations were executed.

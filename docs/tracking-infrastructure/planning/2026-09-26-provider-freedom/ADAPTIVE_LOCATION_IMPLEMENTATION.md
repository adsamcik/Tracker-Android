# All-day adaptive Location — approved implementation

2026-09-26. User approved with "Implement the proposed plan." Phase remains IMPLEMENTATION_ONLY. Candidate checkout: source-native-map-context-root; starting HEAD f1f1b97f9a. Author production and focused tests only. No builds/compilation/tests/lint/Detekt/schema generation/diff-check/device/UI/battery/CI/release/activation/integration/push until global freeze. Do not represent source or authored tests as passing evidence.

## Approved plan (verbatim)

# Balanced Location that works throughout the day

## Agreed behavior

With **background Location enabled**, Balanced aims to preserve recognizable daily routes. Location remains enabled before, during and after sessions.

The earlier illustration showed a session-only configuration. The normal background configuration will instead use adaptive collection with an ongoing notification. Motion assistance is optional; declining it never prevents Location tracking.

## How Balanced adapts

| Situation | Proposed behavior |
|---|---|
| Moving | Request precise Location where permitted, initially every **10 seconds**, with batching up to **50 seconds**. |
| Positively confirmed stationary | Reduce to a **30-second** request, with batching up to **60 seconds**, while keeping the shared service available. |
| Movement resumes | Restore the moving request promptly. |
| Motion unknown or conflicting | Keep useful active collection; missing observations must not strand Location in passive mode. |
| Live session | Participating sources prefer level **4 or higher**, within permissions, explicit limits and device constraints. |
| Session ends | Return to the selected background balance. |
| Motion assistance declined | Use Location's own qualified movement evidence and independently authorized sources. |

These are initial request targets, subject to device validation.

**Balanced describes the user's preference, not a fixed Android provider mode.** The engine can request high accuracy while moving and reduce frequency, accuracy or delivery urgency when appropriate. Android exposes these as separate power trade-offs. [Android location guidance](https://developer.android.com/develop/sensors-and-location/location/battery)

## Implementation changes

- Extend the existing motion controller, plan factory and resolver. Keep one acquisition flow and one Location owner.
- For newly adopted adaptive profiles, use **zero minimum-displacement filtering**. Time-based requests must still deliver evidence after stopping; the previous distance filter could prevent stationary detection.
- Retain the existing two-confirmation stationary rule, spacing and dwell, with observation freshness and speed uncertainty checked. Missing fixes or missing speed never prove stillness.
- Preserve the five moving-cadence targets: **60 / 30 / 10 / 5 / 1 seconds**. Levels 3–5 permit high-accuracy requests; stationary reduction uses the existing reduced-request mechanism.
- Make adaptive collection the normal mode when background Location is explicitly enabled. Preserve existing passive-only configurations until users adopt the change.
- Add optional **Motion assistance** in Location details, initially disabled. Reuse the Activity registration arbiter with a separately authorized consumer. Assistance-only events remain transient and cannot create Activity history or start sessions.
- Keep the lawful shared host while adaptive background intent remains. Report actual provider fallbacks and restrictions; a GPS-backed fallback cannot be described as proven low-power operation.
- Preserve original observations, gaps, source independence and optional stored-data enrichment. Changes in balance never enable another tracked source.

## Acceptance and compatibility

Author checks for Location-only moving → stationary → moving, assistance refusal/revocation, uncertain speed, stale observations, GPS-only devices, approximate permission, battery constraints, session start/end and system-requested stop.

Verify that stationary reduction actually changes the request, Location survives without Activity assistance, and stopping a session preserves enabled background collection.

Existing custom limits, consent and historical records retain their meaning. Build, migration, physical-device fidelity and battery verification remain deferred to the established final convergence gate.

## Implementation ledger

- Preferences/schema/UI owner: astra_plan_data.
- Location profile/motion/provider owner: astra_plan_runtime.
- Optional assistance registration/callback owner: astra_adversary_runtime.
- Shared host, persistence, integration and review packet owner: root.
- All agents work in this existing candidate with disjoint files; no recursive delegation.
- Fresh independent review over original user packet, approved plan, exact source/diff and authored-test inventory is required before this checkpoint is represented as reviewed.
- Main dev/v10 and other dirty candidate drafts remain preserved; no activation or unvalidated local integration.

## Source trace for this implementation

Status: **IMPLEMENTED_UNVALIDATED candidate; bounded source review complete.** This section records source evidence, not executed acceptance results. The approval is the All-day adaptive Location refinement above. The broader provider-freedom assembly is not declared complete.

| Behavior | Production trace | Authored checks (unexecuted) |
|---|---|---|
| Explicit adoption, five levels, legacy preservation | `LocationAdaptiveSettings` → proto settings → `RoomSourcePolicyRepository` → `SemanticAcquisitionPlanFactory` | Preference round trips, unchanged legacy policy, adoption and consent rotation, codec legacy/v2 compatibility |
| Useful Balanced collection without assistance | `CollectionMotionController` → `SourcePlanResolver` → `LocationSourceRuntime` | Unknown/moving/stationary/uncertain speed, confirmation spacing and dwell, active stationary requests, battery hysteresis, severe thermal reduction and recovery |
| One physical Location owner | `AmbientLocationPurposeOwner` → guarded broker demand → `BackgroundLocationHost` → `SharedLocationSourceController` | Exact demand/lease retirement, host rejection, source independence, shared session start/end, stale cleanup, late callback context history |
| Truthful physical acquisition | Backend `resolvePlan` → `SourceRegistrationRepository.recordLocationPlan` → immutable `location_registration_plan` | GPS/network fallback, approximate permission, actual-plan version/recovery, reservation before pointer publication, stale generation/pointer rejection |
| Optional transient motion hints | `LocationMotionAssistanceOwner` → existing Activity arbiter → receiver narrows durable routing → `DefaultActivityMotionAssistance` | Helper-only deny-all Activity history authority, refusal/revocation, exact callback identity and time, legacy Location scope |
| Sessionless observations and independent purposes | Unbound ingress → WAL qualifier → `ProtectedLocationCanonicalHandoff` → existing curation/destination owner → common receipt + capture/ambient associations | Ambient-only WAL attribution, mixed-age units, exact replay, revocation, prior-boot intent with current physical authority, no duplicate canonical sample |
| Durable session-end receipts | WAL admission returns its allocated sequence → delivery sink → Location runtime metrics → session completeness → canonical drain | Zero-based first sequence, batched fixes vs callback count, sparse unit indexes, exact duplicate replay and receipt high-water preservation |
| Read, retain, delete and export consistently | `LocationReadScope` + `LocationPurposeDao` → map and export readers; existing consent/retention/deletion transactions remove invalid ambient associations | Ambient visibility, capture sibling preservation, purpose validation, route gaps, GPX segmentation, settings status |
| Explicit system-stop recovery | Durable user-exit receipt → startup collection gate → exact user resume boundary → provider/ingress/importer fences | Receipt recreation, stale resume CAS, unchanged historical exit, delayed pre-resume observations and Steps windows |

Background and capture share canonical samples when both qualify. Ordinary session metrics continue to use capture associations. Ambient-only map/export reads require current retained authority before query limits. JSON3, GPX and KML remain the existing lossy interchange formats; export does not manufacture native authority or session identity.

## Review corrections

The source reviews found and corrected these concrete issues during implementation:

1. Repeated policy refreshes raised the observation floor and dropped otherwise valid delayed batches.
2. Session joins incorrectly stamped delayed observations with the newest authorization. Bounded historical contexts now preserve observation-time authority.
3. Legacy Location-only sessions lost motion input after changing to unbound ingress.
4. Requested plans were incorrectly used as actual-provider fingerprints; persisted actual registration plans now accompany immutable requested plans.
5. Adaptive encoded-plan metadata still advertised legacy payload version 1 in storage/recovery paths.
6. Prior-boot policy and consent were rejected using incomparable elapsed timestamps; current physical registration and authorization remain boot-specific.
7. Ambient's longer age allowance could widen legacy capture freshness. Each purpose now rederives its own exact allowance.
8. Ambient-only receive-time WAL attribution and per-unit mixed-age membership were rejected by capture-only assumptions.
9. Session End stopped callback entry for the continuing background owner.
10. Framework network fallback was labeled GPS rather than unknown acquisition with its actual provider retained.
11. Failed background refresh could retain stale ready status; it now invalidates availability and leaves reconciliation debt.
12. Actual-plan persistence required an accepted pointer before the reserved provider had started.
13. Isolated native map fixes disappeared during route segmentation; they require the existing point-rendering path.
14. Motion assistance controls were hidden for adopted capture-only Location configurations.
15. Session-end drain rejected an ambient-only endpoint and interleaved background rows. It now authenticates the shared physical prefix while keeping capture membership exact.
16. Location stop acknowledgements confused callback counts with WAL source sequences. Ingress now returns the committed sequence on fresh admission and replay; runtime receipts preserve that exact sequence/ordinal pair.
17. A buffered background export could finish after consent or retention changed. Bounded page checks and final completion checks now cancel the export; capture-associated samples remain exportable under capture authority.
18. Physical reconfiguration preserves the source instance's WAL allocator. Continuity now follows that instance across registration generations, with bounded fallback for older compact anchors and pruned WAL; each observation still requires its exact registration and authorization.

The existing export destination is written through Android's document provider. Failure cleanup attempts to remove the partial document; these checks do not claim that bytes already handed to an external document provider can be erased atomically.

Independent reviewers received `TASK_CONTEXT.md` (original requests and corrections), this approved plan, current source/diff, and the authored-test inventory. `astra_flow_audit` supplied a fresh provisional review during implementation. `astra_adversary_runtime` independently reread the root/data runtime and qualification fixes; it did not independently certify its own helper/host/user-stop code. An additional fresh-agent dispatch was unavailable because the host reached its agent-thread limit.

Root and `astra_plan_data` separately traced the helper's authority scope, callback narrowing, shared host tickets, service/session lifetime, live-writer attach/detach, and durable user-exit/resume handling. No concrete blocker remained in those inspected seams. Root also reread the final data agent changes for immutable actual-plan registration, sessionless canonical materialization, capture/ambient read separation, instance continuity with old-anchor compatibility, and buffered export cancellation. The last reread includes the newly authored Room JSON cases for sessionless geography, consent revocation and retention preparation. These are bounded static review results, not a claim of exhaustive execution or hardware proof.

`astra_plan_data` then independently reread root's final WAL sequence propagation and failed-refresh readiness invalidation. It confirmed that fresh admission and both replay paths return the committed sequence, the sink pairs it with the greatest admission ordinal, and Location keeps callback barriers separate from durable sequence receipts. It also traced readiness withdrawal/reconciliation on failed or changed refresh and host mismatch. No concrete finding remained in that bounded reread. All authors stopped editing before the local checkpoint. The checkpoint includes new or updated test source in 54 files; none has been executed.

## Assembly and verification boundary

- No build, compilation, test, lint, Detekt, schema generation/drift, `git diff --check`, emulator/device, UI, battery, CI or release gate has been executed for this candidate. Authored checks are not passing checks.
- No global convergence input set has been frozen. No provider/writer rollout has been activated, and no integration or push is authorized by this checkpoint.
- Deferred acceptance includes real Android foreground-service behavior, screen-off/reboot/system-stop recovery, device fallback fidelity, actual battery cost, migrations, and the repository-owned full convergence gates.
- The parent plan still includes work outside this refinement, including the system-rearmable passive Location owner, exact `.trackerlocation` v1 export/import with provenance receipts, broader session organization/retroactive sessions, and remaining six-source assembly slices. Their existence in planning documents is not implementation evidence.
- The ongoing notification is a foreground-host requirement; it is never used as proof that observations are arriving. Provider acknowledgement and restrictions govern operational availability. Actual delivery continuity still requires on-device evidence.

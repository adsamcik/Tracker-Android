# R25 focused source lifetime coverage

**IMPLEMENTED_UNVALIDATED.** Test-source authorship and static inspection only. No production
behavior, dependency, schema, or product decision changes. No compilation, test execution, lint,
schema validation, device activation, Git mutation, or integration occurred in this slice.

## Approved scope

The user requires six independently selectable sources, independent sessionless collection where
supported, sessions that organize data and increase quality only for participating sources,
optional Location enrichment without Location demand, a single physical owner per source, separate
automatic trigger authority, and different End versus Turn off behavior. Temporary map/game
Location requests share that owner and may request higher quality independently of tracking.

The historical acceptance inventory remains
`assembly-coverage/2026-09-28/SCENARIOS.md`. Its full provider-to-product cases require more than a
policy-only assertion. This bounded slice fills specific missing assertions at existing production
seams; it does not declare S01-S25 or the assembly gate complete.

## Authored scenarios and exact boundaries

| Existing test file and anchor | Added scenario | Production seam traced | Evidence limit |
| --- | --- | --- | --- |
| `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SourceBrokerTest.kt:1198` | New method iterates all 64 capture vectors against real Room demand replacement. Independently persisted Wi-Fi/Cell background and Activity automatic demands retain their exact rows and authorization membership; excluded Location receives no demand. Retiring capture closes its authorization before final retirement while siblings survive, and all 192 capture demands remain retired history. | `SourceBroker.kt:419` builds immutable policy-derived demands; `:504` replaces only the session consumer and rotates affected authorizations; `:914` fences retiring capture; `:947` retires the same consumer. `SourceBrokerAuthorizationTransactions.kt:12` and `SourceBrokerDao.kt:149` use the actual current active demand vector. | The sibling inputs and accepted provider registrations are explicit ledger fixtures, not newly admitted native observations or retention/permission proofs. All six policies are eligible while the test varies manifest selection. No provider is activated or product writer exercised. Registration generation assertions establish that broker replacement does not allocate a successor; they do not independently prove physical provider exclusivity. |
| `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/catalog/DefaultSourceImplementationCatalogTest.kt:101` | New method iterates all 64 lazy runtime selections. Only selected providers resolve; repeated lookup returns their same fixture owner. Empty and every solo selection are included. | `SourceRuntimeRegistry.kt:99` stores the keyed provider directory and `:115` resolves exactly the requested source. `di/SourcePipelineModule.kt:76` binds the six actual shared/runtime owners; concrete runtime/controller classes use `@Singleton`. | The existing counting provider caches its fake owner. This checks lookup isolation against that seam, not generated Hilt composition or native provider ownership. Its fake runtime rejects lifecycle operations, so no native activation is hidden in lookup. |
| `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedLocationSourceControllerTest.kt:87` | New map plus game case acquires simultaneous high quality leases, releases map while game keeps its requested interval, ignores a repeated map release, and closes the physical runtime only when game releases. The broker remains empty and the durable refresh path is never called. | `SharedLocationSourceController.kt:50` and `:74` mutate exact keyed leases; `:89` distinguishes temporary work from authorized durable demand; `:113` combines active UI requests. | Uses the existing mocked physical runtime. The requests carry the approved 10 s/5 s freshness limits, but this test does not assert actual callback freshness filtering or native SDK accuracy. |
| `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/TrackingPurposePublicationRuntimeTest.kt:67` | Existing Turn off assertion expanded from two purposes to all 11 canonically supported source/purpose pairs, including each of the six capture choices. Every read returns no new collection authority before a policy read. | `CurrentTrackingPurposeAuthorityReader` in `TrackingPurposePublicationRuntime.kt:174` fences before policy/lifecycle/retention reads, and rechecks the startup gate before returning authority. Canonical support comes from `TrackingSourcePurpose.kt`. | This is the closed authority gate, not every UI/notification/widget/shortcut command or physical teardown. Strict dependency mocks keep an unexpected policy read observable. |
| `sensor/activity/src/test/java/com/adsamcik/tracker/activity/api/registration/DefaultActivityRegistrationArbiterTest.kt:1187` (new tail at `:1263`) | Existing real Room in-flight capture drain case now continues through End to trigger-only on the same generation, then removes the final automatic owner. Off leaves deny-all authorization, a retired registration and closed admission, removes the provider exactly once, and repeated Off remains idempotent. | `DefaultActivityRegistrationArbiter.kt:110` removes one owner; `:628` fences/removes the final registration; `:815` waits for its terminal callback barrier and atomically appends deny-all plus retirement. | The production arbiter and Room are real; the GMS backend is mocked and durable consumer retirement is arranged in the existing fixture. This is the leaf owner lifecycle, not the app command route or a full automatic transition-to-session-to-product case. |

Three new test methods and two extensions to existing methods are authored. They reuse existing
fixtures and assertions. No test framework or duplicate production predicate was introduced.

## Existing coverage inspected instead of duplicated

- `FiveLevelSourceConfigurationTest.kt:42`: all 64 source selections across five quality levels,
  with live preference applying only to selected sources.
- `SourceLifecycleProofCoordinatorTest.kt:154`: selected-source proof keys for all 64 selections,
  through fake adapters; this alone is not provider-to-product proof.
- `SharedAmbientRadioSourceControllerTest.kt:30` and `:74`: session Wi-Fi/Cell End preserves the
  independently selected background owner; subsequent tests cover failure and exact claims.
- `SharedStepSourceControllerTest.kt`: exact claim/retirement handling and removal of old unsupported
  Steps automatic control. All-day Steps use the separate supported on-device recording/import
  mechanism; this slice does not claim that the direct capture sensor stays registered after End.
- `AutomaticStartTransitionMonitorTest.kt:66` and `:355`: disabled transition detection cannot
  register continuous recognition as hidden automatic control; supported transitions retain their
  explicit control demand. Production `AutomaticStartTransitionMonitor.kt` was traced through
  guarded demand dispatch, projection registration, arbiter registration and compensating cleanup.
- `AuthoritativeSessionCoordinatorTest.kt` and `TrackingOrchestratorIntegrationTest.kt`: existing
  durable session preparation, immutable attribution, selected-source failure/retirement and
  persistence paths. Their fakes and individual source cases are not a composed real-provider
  implementation of every acceptance scenario.

## Remaining source/evidence boundaries

No new concrete production defect was established at these inspected seams. This statement is not
a completion verdict for their downstream UI, exports, effects, or every lifecycle scenario.

The full S01-S06 solo provider admission-to-retained-read-to-UI/file cases, actual Dashboard/service
routes for representative subsets, mixed automatic Activity+Pressure and Wi-Fi+Cell trigger/capture
product paths, and cross-purpose retained outputs must still be reconciled against their other
source-owned test slices. These new seam cases alone do not author or close those full chains.

Final convergence must compile/Hilt/Room-check and execute the authored suites before any pass is
claimed. Required real-device evidence remains provider capability/permissions, Android foreground
service start/type rules, passive radio callbacks and active throttling, callback retirement races,
system Location rearming, process/reboot/provider handoff, all-day Steps gap/deduplication behavior,
and lifecycle/power behavior. Device evidence cannot be inferred from Room or mocked SDK tests.

The coordinator owns independent review, exact staging and commits. The exact five test paths and
their source hashes are recorded in `R25_SOURCE_LIFETIME_COVERAGE_FILES.json`; companion production
and earlier dirty drafts are excluded from this slice.

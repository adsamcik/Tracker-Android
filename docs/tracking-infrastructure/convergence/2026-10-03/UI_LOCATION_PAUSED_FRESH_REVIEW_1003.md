# Paused background Location: independent source review

2026-10-03 — `/root/fresh_bundle_context_review_0930` — **IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY**.

## Result and exact scope

No concrete remaining defect found in the two selected deltas against the inherited user requests and approvals. This is a bounded static source conclusion, not full-chain acceptance or integration readiness. All 25 assembly prerequisites remain open.

Compared the preserved before/after snapshots at coordinator-supplied base `16458b8a59` with the actual two files. The production change is only the five-line guard replaced by ten lines at `SharedLocationSourceController.kt:96–106`; the test change is one insertion of 129 lines containing six methods. All remaining before/after lines match. No Git operation or validation gate was executed.

## Concrete trace

- `SharedLocationSourceController.kt:89–115` exempts only a reconciled disabled `ambient:location` baseline at the same policy revision with no session/run identity. Cold/missing baseline, a newer background revision, a foreign ambient consumer, and every unjoined session demand still prevent UI replacement. The mutex and existing selected-demand/effective-plan branches remain unchanged.
- `AmbientLocationPurposeOwner.kt:191–239` sends the resolved background plan and the lease's policy revision into the existing controller. `reconcileAmbientJoin` still authenticates the exact current ambient demand before remembering the baseline (`SharedLocationSourceController.kt:345–371`). The new guard creates no broker demand and never changes saved participation.
- The existing physical UI-only path checks retained registrations, permission/provider prerequisites and pending retirement (`LocationSourceRuntime.kt:115–177`). After an initial refusal, the controller requires acknowledged `closeShared` release before retrying. UI-only delivery (`:205–214`) emits transient events without the durable sink or WAL. Separately authorized tracking continues to use its existing shared callback path.
- Existing My location/map and game consumers reuse `UiLocationProvider`; `InfrastructureModule.kt:106–111` supplies the same shared owner. Requested rates remain independent of the disabled background plan. `SharedUiLocationProvider.kt:58–100` retains freshness checks, permission checks and noncancellable lease release; no permission, freshness, UI or provider behavior was changed here.
- The six authored methods (`SharedLocationSourceControllerTest.kt:212–338`) assert paused acquisition/release without durable refresh, map/game independent release across pause and restoration, failed retirement and retry, and the newer/session/foreign guards. These use controlled physical-runtime and broker doubles; they do not prove native callbacks, device timing, battery, Hilt, Room or UI rendering. Existing cold-owner and failure fixtures are unchanged.

## Exact SHA-256 pins

| Input | SHA-256 |
| --- | --- |
| `UI_LOCATION_PAUSED_FILES.json` | `52aa3a72061a7538c7217396219f340005509ca95110266e0fe2b1ae61abea75` |
| `UI_LOCATION_PAUSED_SOURCE_BOUNDARY.md` | `31c11c6bf41afc9879395349e673bf3c489da5e21ab754751413b95cf2ee6ac4` |
| `UI_LOCATION_PAUSED_SELECTION.patch` | `778fe45a642c6f4929274c894fc0cd49d8ec95b6e4fee03cf39ac48ed399e1c5` |
| Controller before snapshot | `5bfff7a5f91e379eaac82dd7b0086b39d583487a2f8f83c7752567b7beb2d612` |
| Controller actual/after snapshot | `18e4d0bc46c6c567a2e84b69e522ea8c94ce24c6589f9d7e454a8f825ec8417b` |
| Test before snapshot | `532ccc558349e52954012d315cf90b17391a55909e3ccf7345ac3bcf249624fa` |
| Test actual/after snapshot | `2b951b797804e7559a4f41b5928bf4afbb6096fa837055994949fc1e489af1c5` |

The two actual source hashes and their after snapshots match the frozen manifest. Runtime dependencies were read, not selected. Held radio/export/import/Replace/transfer and other pending implementation slices remain outside this review. No production edits, tests, compilation, Gradle, lint, schema/diff checks, device/provider activation, CI or integration were performed.

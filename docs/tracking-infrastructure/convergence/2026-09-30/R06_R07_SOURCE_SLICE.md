# R06 / R07 source slice

Status: **IMPLEMENTED_UNVALIDATED**. Production and focused test sources only; no compilation, tests, Hilt, schema checks, Android activation, or Git integration was executed by this slice.

## Implemented behavior and source trace

- Optional default-off Wi-Fi and Cell notification choices use the existing source-policy rows and preference store. They do not enable capture/background consent, change QoS, or grant active scan requests. Both flags off preserve the exact preceding settings fingerprint. `RoomSourcePolicyRepository.kt:179,681,761`.
- One service host now maintains independent Location/Wi-Fi/Cell leases. Radio admission checks the current policy, consent, collected-data epoch/retained floor and authenticated active radio owner. Tickets and retirement remain exact; predecessor cancellation cannot release a successor or sibling. Source-wide fenced deletion also closes pending requests. `BackgroundLocationHost.kt:54,99,202,233,258`.
- A declined or failed optional host preserves the independently acknowledged passive radio provider and reports degraded availability. Host loss downgrades only the matching published identity. `AmbientWifiDemandReconciler.kt:255`, `AmbientCellDemandReconciler.kt:250`, `TrackingPurposeAvailability.kt:1129`.
- Foreground types use the complete capture/host source union. Radio access retains Android's Location type/permission requirements without requesting Location collection. End retires live capture while valid independent hosts survive; Off, permission loss and exact source retirement close their corresponding hosts. `TrackerService.kt:1358,1413,1537,1799,1979`.
- Non-Location collection uses the existing clock-bounded collection scope, accepting admitted sessionless hardware Steps as motion hints. This does not add a Location/Activity request or treat historical imported step windows as current motion. `TrackerService.kt:1327`; existing committed `CollectionMotionController.kt:38,91` and durable admission sink.
- Settings retain edits on failure and use existing ordered Retry; notification choices are independent accessible switches shown only for enabled background sources. Background notification names the actual sources without live counters.

## Independent static review receipt

`/root/completion_wiring_0930` independently reviewed the source/test slice and reported no definite correctness blocker. It accepted keyed lifetimes, pending-ticket/successor fencing, default-off/fingerprint compatibility, unchanged consent epochs, passive fallback, FGS union, End/Off fences, settings Retry/accessibility and unbound Steps hints. This is bounded static acceptance, not executed validation or full assembly readiness.

The root-owned dependency is now authored and independently statically reviewed by
`/root/completion_wiring_0930`: 28-to-29 adds
`source_policy.background_notification_host_enabled INTEGER NOT NULL DEFAULT 0`;
v29 preflight checks its exact type/nullability/default; the old-policy migration
fixture preserves both source rows as default off; a corrupt default-one v29 file
is blocked before Room opens. Historical v28 fixtures retain their preceding shape.
Checked-in schema regeneration/verification remains deferred. Existing
`CollectionMotionController.kt` and `TrackerServiceApi.kt` have no working-tree diff
and need no additional companion draft.

Root-owned companion paths:

```text
core/base/src/main/java/com/adsamcik/tracker/shared/base/database/RadioObservationChildMigration.kt
core/base/src/main/java/com/adsamcik/tracker/shared/base/database/DevelopmentV28DatabaseContainment.kt
core/base/src/test/java/com/adsamcik/tracker/shared/base/database/RadioObservationChildMigrationTest.kt
core/base/src/test/java/com/adsamcik/tracker/shared/base/database/DevelopmentV28DatabaseContainmentTest.kt
```

## Exact R06/R07 path manifest

### Production / resources

```text
core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/SourcePolicyEntities.kt
data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/SourcePolicy.kt
data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/TrackingParamsState.kt
data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/TrackingParamsRepository.kt
data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/DefaultTrackingParamsRepository.kt
data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/AuthoritativeTrackingParamsRepository.kt
data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/RoomSourcePolicyRepository.kt
data/preferences/src/main/proto/tracking_params.proto
tracker/api-module/src/main/java/com/adsamcik/tracker/tracker/api/TrackingPurposeAvailability.kt
tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/BackgroundLocationHost.kt
tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/TrackerService.kt
tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/wifi/AmbientWifiDemandReconciler.kt
tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/cell/AmbientCellDemandReconciler.kt
tracker/engine/src/main/java/com/adsamcik/tracker/tracker/notification/TrackerNotificationManager.kt
app/src/main/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModel.kt
app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt
app/src/main/java/com/adsamcik/tracker/app/settings/tracking/SourcePurposeSettings.kt
app/src/main/res/values/radio_host_strings.xml
app/src/main/res/values-cs-rCZ/radio_host_strings.xml
tracker/api-module/src/main/res/values/radio_host_strings.xml
tracker/api-module/src/main/res/values-cs-rCZ/radio_host_strings.xml
```

The radio-hosting keys are authored in all 28 declared locale directories in both modules.
`RADIO_HOST_LOCALIZATION_FILES.json` is the exact 56-resource path manifest plus the two
newly introduced Czech fallback files removed after moving to the declared `values-cs-rCZ`
locale. Unrelated `values-cs` drafts are preserved. The existing switch rows now identify
Wi-Fi or Cell in their spoken descriptions without adding duplicate visible text; the
existing Compose choice scenario asserts both descriptions. These resources and assertions
remain unvalidated.

### Authored focused test source

```text
data/preferences/src/test/java/com/adsamcik/tracker/shared/preferences/tracking/DefaultTrackingParamsRepositoryTest.kt
data/preferences/src/test/java/com/adsamcik/tracker/shared/preferences/tracking/RoomSourcePolicyRepositoryTest.kt
tracker/api-module/src/test/java/com/adsamcik/tracker/tracker/api/TrackingPurposeAvailabilityTest.kt
tracker/engine/src/test/java/com/adsamcik/tracker/tracker/service/BackgroundLocationHostTest.kt
tracker/engine/src/test/java/com/adsamcik/tracker/tracker/service/ForegroundServiceTypeCandidatesTest.kt
tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ambient/AmbientRadioDemandGateTest.kt
tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionControllerTest.kt
tracker/engine/src/test/java/com/adsamcik/tracker/tracker/notification/BackgroundCollectionNotificationTest.kt
app/src/test/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModelTest.kt
app/src/test/java/com/adsamcik/tracker/app/settings/compose/TrackingSettingsScreenTest.kt
```

Tests author host coexistence/forged owner/default-off rejection, exact predecessor/sibling/pending retirement, stopped startup, fingerprint/default and consent preservation, passive fallback on refusal/exception, FGS union, actual notification text, settings Retry/independent accessible switches and unbound Steps scope/clock/lifetime. They have not run.

## Separate bounded R05 repair

Valid v6 Cell facts with accepted child count zero are complete when the child-count map has no row. The exporter now treats an absent row as zero and still rejects unexpected children or incomplete positive counts. Source/test paths:

```text
feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/PortableCellSourceExporter.kt
feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/CellSourceDetailCompletenessTest.kt
```

This two-file repair is unvalidated and needs root's independent review before selection; it is not a claim that the full legacy radio read/history/export chain is complete. The legacy shape decision and R08 durable passive Location failure/gap handling remain outside this slice.

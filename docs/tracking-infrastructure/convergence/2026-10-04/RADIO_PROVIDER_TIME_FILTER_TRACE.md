# Radio provider timestamp filtering: independent static trace

Date: 2026-10-04. Reviewer: fresh_radio_schema_review_1004.

## Verdict and boundary

The earlier concern that one future-dated radio item makes normal provider delivery reject all valid siblings is **disproved for the actual Wi-Fi and Cell provider routes inspected below**. Both runtimes remove that item before constructing the canonical payload, delivery identity and observed min/max header. Their shared physical age limit preserves observations still useful to either selected owner, and v6 admission applies each owner's own age and consent at the individual reading.

No production change, new envelope contract, storage design or duplicate filter test is justified by this concern. A directly constructed malformed ingress candidate may continue to fail the existing defensive time guard. This receipt narrows the interpretation of the earlier finding; it does not alter the preserved original Cell packet or its rejected review receipt.

This is IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED static source inspection. No source or test edits, Git mutations, recursive delegation, Gradle, compilation, tests, lint, Detekt, schema generation/drift, diff--check, emulator/device/UI/battery/CI/provider activation, integration or push occurred. Tests listed below were read, never executed. All 25 full-chain prerequisites remain open. This is not whole-provider, whole-assembly or device acceptance.

## Actual provider routes

### Cell

- CellSourceBackend.kt:229-247 sends Android 31 TelephonyCallback, legacy PhoneStateListener and the explicit requestCellInfoUpdate callback (134-145) through toBackendSnapshot. Available provider timestamps are retained by backendObservation (333-343); receipt time is not fabricated as an observation.
- CellSourceRuntime.kt:648 and 1171 send both passive and explicit-refresh callbacks through onBackendSnapshot. The actor's recordSnapshot (1060-1083) first calls qualifyCellSnapshot with the callback's captured receipt and merged plan age. It uses only qualified.observations and qualified.providerTimeNanos afterward.
- qualifyCellSnapshot (1487-1514) rejects cached/operational outcomes and calls freshProviderObservations independently on every item. The list must be nonempty; canonical identity and maximum provider time are built from that filtered list.
- admitSnapshot (1249-1278) builds the v6 payload from those filtered observations, derives the unit's earliest time from them, and calls the captured context sink. No actual backend path constructing a radio delivery bypassing this qualification was found. Persisted payload decoders and portable-import adapters are not an alternative native backend writer.

### Wi-Fi

- WifiSourceBackend.kt:35-38 converts positive, non-overflowing Android scan timestamps to nanoseconds. Broadcast receipt (83-91) reads the provider snapshot only for an eligible update and supplies the original receipt time. readSnapshot (147-150) yields individual access points.
- WifiSourceRuntime.kt:prepareWifiSnapshotDelivery (1238-1302) first calls qualifiedWifiProviderObservations. Both snapshot preparation and sink admission converge through that method.
- qualifiedWifiProviderObservations (1549-1565) calls freshProviderObservations, then applies the actual physical registration start and cutoff to each surviving item.
- Canonical access-point ordering, provider identity, minimum/maximum observed times and v6 payload are all derived from the filtered list (1261-1300). A future access point therefore cannot set a future batch header or enter the original canonical payload.

### Common filter and defensive ingress

ConnectivityRuntimeSupport.kt:227-245 keeps an item only when its timestamp is present, positive, no later than the captured receipt, and within the saturated age bound. A malformed or future item is omitted independently of its siblings. The subtraction cannot overflow because a retained timestamp lies between zero and the nonnegative receipt.

DurableSourceIngress.kt:585-589 and 2181-2187 reject an intrinsically invalid constructed delivery. On the real routes, observed maximum and earliest time already satisfy those inequalities because they come from the filtered list. Removing this defensive check would not repair a demonstrated provider-route defect.

## Independent session/background freshness

- SharedWifiSourceController.kt:330-365 and SharedCellSourceController.kt:330-358 select the existing session and background demands. Physical maximum age is demands.maxOf(maximumAgeMs), not the stricter session value. Active-request cadence remains the existing capture plan; background's larger age does not authorize extra scans.
- DurableSourceIngress.kt:879-900 preserves the exact callback authorization for v6 radio evidence, checks the independently qualified union, and does not narrow that header through the legacy batch freshness helper. Valid background items remain eligible when session capture is closed or has no eligible item.
- RadioPurposeItemQualifier.kt:115-136 selects each reading's authorization revision, immutable demand, start/retirement interval and demand-specific maximumAgeMs. Session and background accepted indexes are independent. A stricter session cannot remove an otherwise valid background item from their union.

These statements apply to the inspected current source bytes and approved v6 item admission. They do not reinterpret older persisted payload versions, prove callback registration/recovery on Android, or close unrelated runtime follow-up findings.

## Existing authored coverage inspected

- ConnectivityObservationAdmissionTest.kt:13-31 supplies fresh, edge, stale, missing and future timestamps together; expected output retains fresh and edge. This directly covers the shared per-item filtering helper without mirroring a new implementation.
- WifiSourceRuntimeTest.kt:365-392 supplies a mixed-age provider event and asserts that only the fresh access point determines the emitted payload/header. The case at 1022-1059 checks future/missing/stale and repeated empty events stay non-durable; it is an all-invalid event, not a mixed-valid/future end-to-end Room case.
- CellSourceRuntimeTest.kt:624-647 covers stale callbacks, non-callback outcomes and repeated empty callbacks. The common mixed-item helper case supplies the specific valid/future sibling coverage used by Cell. No separate mixed-valid/future Cell-through-Room test was inferred.
- SharedCellPhysicalAgeTest.kt:26-55 and SharedWifiPhysicalAgeTest.kt's corresponding case capture the physical plan: a 10-second session plus a 600-second background demand yields a 600-second age without changing the request interval.
- RadioPurposeItemQualifierTest.kt:37-47 covers both radio sources with a two-minute item valid for background and only the fresh item accepted by the one-minute session. Cases at 52-66 cover session join/leave without changing the background subset; 175-180 rejects invalid items independently.

These are authored, unexecuted source cases. They provide no compilation, Room, real-provider, battery or on-device result.

## Inspected SHA-256 pins

Current working source is moving while authors complete separate packets. These pins identify this trace only; they are not a new selected production packet.

| Repository-relative path | SHA-256 |
| --- | --- |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/CellSourceBackend.kt | 4daa5da8a51563880278c3f83664c60840527e32e979d75f41bfe34f7a69b2a6 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/CellSourceRuntime.kt | 4f776ff138f228485132b0fa0bef4161314d26f6c9bed5b46af18b5f24df2b4f |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/WifiSourceBackend.kt | d0ebae096e450c4fa161bcc5babdb1767f0f196f4cf66114bc65ad4c663ec72d |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/WifiSourceRuntime.kt | b74b58e8fdded10365180f3a57ebf12a847bea4d3aa806f8b0c67871580725aa |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/ConnectivityRuntimeSupport.kt | 5c84dfec6dd4f9debd7d42af694941f91b98c4208a455304cc1b59a12100296e |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SharedCellSourceController.kt | 8a780e452145b21f70e0ad4f9488ed5c95505d8f49609960943657e05eb39339 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SharedWifiSourceController.kt | 412cca0182e946ba04849d039f8891dd294597f56363ca191f9f4489207ff751 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ingress/DurableSourceIngress.kt | 88f6fcb9a13d7f2ae052fa268c9eba6bb07abb10f757125fc34bd2022bac3264 |
| core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/RadioPurposeItemQualifier.kt | 50f52cfd58e71d1613302c753a4d131375a984afc80bb7e1f8fc695ed71eecfd |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/ConnectivityObservationAdmissionTest.kt | 44b27b74a07e9aeb725237cfc60ecfb08f00a7f2fb23f68be98e40dc1d0e3392 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/CellSourceRuntimeTest.kt | e6be846bb8468e1e3c359ca2dc942401b5feaaaf93d100306df0ecf87b3edf41 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/WifiSourceRuntimeTest.kt | 5778001c9d16c3e562cec87ba79d677e141f9b9f0cfb56bb560dde0b1ca5ebbf |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedCellPhysicalAgeTest.kt | 8a96b88e8bf3444df0dfaeb333df038594e7a3e34e567c3c2c29fa2896cb506b |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedWifiPhysicalAgeTest.kt | 0b36ada2019981728510791746502f244a00cdb7f25acb59d18465bb132115c3 |
| core/base/src/test/java/com/adsamcik/tracker/shared/base/database/data/RadioPurposeItemQualifierTest.kt | a3c4ed6f0c898c02cd4dd3572657c0a3c2018cca43803975f6c05eee96b6500d |

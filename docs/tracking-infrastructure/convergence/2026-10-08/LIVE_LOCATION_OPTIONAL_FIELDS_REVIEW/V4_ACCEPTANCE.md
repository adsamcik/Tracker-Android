# Optional live Location V4: bounded source acceptance

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Independent source review only. All15 new/renamed test method names NOT_RUN. No compilation, tests, lint, schema, device/provider, Git mutation or integration ran. Acceptance is confined to the exact27-path optional-field slice and its source composition, not full-chain/provider/architecture readiness.

Exact FILESabe85cc6647e2ef07311567213ae63d29ca2b842789ce80121ee1e28a92136bc; patch63a2f0a631429e16acfdbd421da97e3485eb54a20f21cd4e9a7d3c9e37fff07f;30 committed dependenciescdf53b788ce8e0b56bbc6a7779c0d86a7478c313f2d1742bac0112c153188d39. All27 BASE/SELECTED/WORKING_BEFORE hashes and30 exact current committed pins match. Selected source is LF. V2/V3 predecessor packets remain preserved separately. V4_INPUTS.json freezes this recheck.

## Concrete findings closed

- Ambient acceptance now depends on the actual curated point, so null accuracy no longer causes the terminal-outcome assertion to fail. Existing canonical persistence/receipt idempotence remains unchanged.
- Actual capture follows the original OfflineCanonicalWriter DataCollectionStage→SignalDispatchStage→SignalAdapter→pending signal→PersistenceProcessor path. Dispatch now agrees with present-point acceptance, and the adapter constructs a coordinate signal with unknown accuracy.
- The actual LocationSignal contract is now explicitly Float?; this corrects the earlier review's inaccurate claim that it already was nullable. SIGNAL_NULLABILITY_CORRECTION records the withdrawal. The serializer omits null hAcc and decodes absent/explicit JSON-null as unknown; known-value serialization still emits exactly the original field/value ordering. Existing checksummed envelope/current decoding/recovery guards remain.
- Existing PersistenceProcessor copies nullable hAcc and classifies null as COARSE; the selected canonical prepared output/receipt quality helper agrees. Existing exploration engine and segmentation contracts already accept nullable uncertainty. Exact all-real-module source inspection found no remaining required-accuracy consumer of this curated signal. Separate radio joins keep their explicit known-accuracy requirement.
- Existing duplicate curation distinguishes accuracy presence before comparing numeric getters. Known zero→unknown→known zero is accepted by the authored component case; the subsequent same known-zero repeat still exercises the unchanged stationary duplicate policy.
- The two inherited oversized fixtures now exceed the actual65587-byte unified ceiling. Exact original65586-byte v2 maximal-valid assertions remain; source tests still exercise preflight no-blob reads.

## Preserved approved behavior

Provider callbacks normalize copies before existing order/identity/dedup; original coordinate/time/elapsed/provider/mock values and actual authorization/registration/Stop/epoch/retention boundaries remain. Invalid optional sensor values become absent, never estimates or zero. Unknown-accuracy batches use uniform Location payload/delivery version3; valid-only batches retain original version2, and old1/2 codecs/reconstructed bytes stay exact. Current My location emits sanitized fresh data through its original independent temporary lease. Known-accuracy maximum, game-specific accuracy and motion-hint requirements remain; no new provider, store, schema or dependency was introduced. The dormant non-null shadow schema stays separate from the existing protected canonical writer.

## Test-source reach and limits

The codec/pure qualification/component/SignalAdapter cases exercise actual source functions. The added actual SignalDispatch stage reaches its existing processor fanout with explicit raw/curated fixture inputs and asserts accepted decision/unknown signal accuracy. Both new serializer methods reach the actual checksummed envelope/decoder (including absent and JSON-null hAcc), without changing known-value cases. Coordinates use the actual toDegrees API. SDK source inspection supports the new representable invalid optional-value fixtures; no setter/platform execution is claimed.

The two Room cases seed real WAL and original authority, then invoke the actual protected capture/background writer, persistence and duplicate receipt paths. They are source-authored, not executed. Runtime→recording sink and SharedUiLocationProvider cases declare mocked provider/lease boundaries. They do not prove live Android delivery, Room compilation/execution, startup/permission recovery, physical power behavior or the complete provider chain.

No further concrete source blocker found in the changed producer/type/codec/serialization/writer/consumer or test-source seams. Full shared-setup overlay composition, remaining source architecture work, all25 full-chain prerequisites and T25 remain OPEN. This receipt does not authorize provider activation or integration into dev/v10.

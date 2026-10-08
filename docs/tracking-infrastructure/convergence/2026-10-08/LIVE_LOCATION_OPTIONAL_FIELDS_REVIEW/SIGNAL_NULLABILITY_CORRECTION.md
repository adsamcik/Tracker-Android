# Correction: curated signal accuracy also needs nullable propagation

The V2 review's statement that existing `LocationSignal.horizontalAccuracyM` was already nullable was incorrect. Root identified the actual committed field: `stats/api/.../signal/TrackingSignal.kt:55` is `Float`; only `LocationObservationSignal:111` is `Float?`. This correction supersedes that sentence in V2_FINDINGS without altering the frozen earlier evidence. V2 remains held, not accepted. Static review only; all execution NOT_RUN.

The explicit valid-coordinate/unknown-accuracy approval permits the necessary existing type propagation. Smallest coherent repair:

- Change the existing LocationSignal field to `Float?` and correct the raw-observation KDoc that incorrectly distinguishes nullable accuracy after this change. Known-value constructors retain their original arguments and values.
- Existing SignalAdapter constructs a LocationSignal when coordinates are present; existing SignalDispatchStage and Ambient writer decide acceptance by the actual curated point presence. These were the already reported required repairs.
- Existing SignalSerializer must encode nullable accuracy as an omitted optional hAcc field, preserving exact known-value JSON bytes; its toLocationSignal decoder must accept absent/null as unknown while keeping the original numeric path. Otherwise the existing pending_signal recovery cannot recover the new allowed point.
- Existing PersistenceProcessor.bufferLocation already copies nullable hAcc into LocationSample and classifyQuality(Float?) already returns COARSE for null. No further adapter/store is required there.
- Existing ProtectedLocationPreparedCanonicalOutput copies hAcc into a nullable stored sample and the frozen optional-Location overlay already changes expectedStoredLocationQuality to nullable. This remains the same receipt and prepared-output contract.
- ExplorationProcessor forwards accuracy to CellDiscoveryEngine.onLocation(accuracyM:Float?), whose existing contract accepts null; SegmentDetectorProcessor forwards to SegmentSignal.horizontalAccuracyM:Float?, also already supported. No stats/engine behavior/type propagation is required. Reconstruction/research source uses its separate already nullable stored-observation types.

An exact committed-source search across all real app/core/data/domain/feature/sensor/stats/tracker modules found these consumers. Unchanged retained summary/map notification fields are separate DTOs: saved Location displays already accept unknown, while radio joins retain their explicitly approved known-accuracy requirement. No accuracy fallback/sentinel is needed.

Existing SignalSerializerTest uses known hAcc numeric JSON and round-trip comparisons; no missing-hAcc rejection test was found. Preserve those byte/known-value assertions and author a focused unknown-accuracy round trip (including JSON null/absence), plus the actual existing Room capture/background cases. Existing SignalAdapter/dispatch contract cases should retain known accuracy and verify a present accepted coordinate/unknown hAcc. All new and existing tests remain NOT_RUN.

SIGNAL_NULLABILITY_INPUTS.json freezes the exact source/test input hashes. No source implementation or Git mutation occurred in this review. T25/all25 full-chain prerequisites remain OPEN.

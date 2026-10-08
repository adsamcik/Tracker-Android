# Approved live Location optional fields

IMPLEMENTED_UNVALIDATED. Exact private production/test source held for independent review;
no live drafts changed. No test, compile, lint, schema, diff-check, device, activation, CI or Git gate.
Human approved retaining valid coordinates/measurement time when accuracy or optional sensor values
are absent/invalid, including fresh My location. Explicit game and radio matching accuracy remains.

Normalize a copy before existing provider ordering/deduplication, preserving original valid values,
time/elapsed clock, provider and mock bit. Invalid optional fields are absent; no zero/sentinel or
receipt-time replacement is introduced. Existing coordinate/elapsed/freshness/permission/Stop/epoch,
retention, acquisition maximum for known accuracy, mock and current canonical owner checks remain.
AndroidX's already-used LocationCompat handles removal of invalid accuracy metadata on older APIs;
existing dependency is core1.19.0, no dependency addition. Official API sources inspected:
https://developer.android.com/reference/android/location/Location
https://developer.android.com/reference/androidx/core/location/LocationCompat

LocationFixPayload horizontal accuracy becomes nullable. Old versions1/2 preserve exact bytes;
new source-internal version3 and delivery version3 add presence only for a batch with unknown
accuracy. Every unit of that batch shares version3; valid-accuracy-only batches retain version2
and original delivery identity bytes. Decoder/admission/pure qualifier/reconstructed delivery
agree, with one-byte larger existing finite per-unit bound. Old required-accuracy shape cannot
encode null. This is internal WAL, not a user backup format or new schema. The original canonical
Location writers persist null accuracy with existing COARSE quality, including background without
a session. My location returns a sanitized copy. Motion hints still require valid known accuracy.
The dormant legacy alternate projection schema requires accuracy and keeps its own old limitation;
it is not the canonical writer and no new shadow/schema/store is added or activated.

Test source: actual runtime callback→actor→recording sink, normalization/old-API compat, codec,
pure qualifier, actual shared UI lease consumer, Location component, and real Room WAL adapter→
protected capture/background canonical writer→receipt/duplicate read. Android/provider authority
and admitted WAL are seeded fixture inputs; runtime sink does not prove durable Room ingress.
Existing Room delivery fixture wrongly required all optional fields; its byte builder now writes
actual presence while preserving original non-null fixture bytes. Existing missing-accuracy
assertions adapt to the explicit approval. CASE_INVENTORY records exact new/renamed methods; all
NOT_RUN. No sensor/platform availability or full service lifecycle proof is claimed.

Shared setup binding/consumer composition, source-local original clock guards, effective user
corrections, broader old data and all25 full-chain/T25 remain OPEN. ProtectedLocationCanonicalHandoff
and LocationWalQualificationAdapter will need exact overlay composition with held setup changes.
No integration readiness or complete architecture claim. Mixed drafts/source refs preserved.

## V2 fixture correction from SDK source inspection

The Android36 SDK Location.java public setBearing checks Float.isFinite before writing the field.
The V1 test tried setting NaN bearing before reaching normalization; that was not a reliable fixture.
V2 leaves bearing absent and retains invalid accuracy/altitude/speed/accuracy-metadata cases that
the actual setters can represent. Production bytes and all other methods are unchanged. Existing
payload qualifier tests still cover invalid bearing; no Parcel/reflection fixture machinery added.
The inspected local SDK source is not execution evidence or a platform matrix. All methods NOT_RUN.

## V3 independent source review repairs

The protected ambient and actual capture SignalAdapter/SignalDispatch producers accept a present
curated point independently of its optional accuracy. Nullable accuracy and existing COARSE quality
remain unchanged. Curation distinguishes known zero accuracy from absent accuracy before comparing
numeric getters, without changing time/stationary duplicate policy. Forward/reverse component and
actual stage fanout source cases are authored. The inherited adapter over-budget fixtures use the
new unified 65587-byte ceiling plus one; original v2 maximal bytes remain unchanged. V2 findings
are preserved. All source methods remain NOT_RUN; no execution/activation/assembly acceptance.

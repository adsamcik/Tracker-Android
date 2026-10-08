# Existing callers and contracts

Source inspection only, at ab25a6dbfaab82d838a22ed97099cdd92bf2a2e7.

- Manifest registers explicit non-exported PassiveLocationReceiver. RearmablePassiveLocationBackend builds the existing identity URI/request. Receiver decodes only its chosen backend and invokes admission in application IO under the existing five-second budget.
- PassiveLocationDeliveryAdmission copies/normalizes source originals, independently checks original registration/clock/permission/Stop/resume authority, creates unchanged one-fix locationDeliveryCandidate, then calls unbound sink.
- DurableSourceEventSinkFactory.unbound uses the actual DurableSourceDeliveryIngress. Production RoomDurableSourceIngress atomically validates current gates plus original identity/authority, preserves delivery-unit dedup and allocates WAL sequences. Successful or duplicate Location admission requests protected Location recovery.
- normalizeLocationBatch and canonicalLocationDeliveryBytes remain byte-identical. One-fix hashes omit changing receipt time and retain original source fields. Chunking after global normalization preserves every identity/order across work boundaries and replay.
- The only committed outcome.overflow caller is the former cap test assertion; receiver ignores all outcome fields. Removing overflow adds no compatibility burden or user-visible cap.
- AndroidTestConventionPlugin supplies Mockk, coroutine-test and Robolectric; existing engine test source already uses runTest/JUnit4/Robolectric. No dependencies/modules/Hilt contracts changed.

DEPENDENCIES.json pins the exact source/module/manifest/build contract bytes. Historical plan updates are planning context, not frozen source dependencies. No gate was executed.

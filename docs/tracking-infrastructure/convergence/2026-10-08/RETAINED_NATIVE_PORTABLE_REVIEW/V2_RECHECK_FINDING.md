# Native portable V2 exact recheck

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED; all methods NOT_RUN. No gates, compilation, tests, schema, provider/device or Git mutation. All25/T25 open.

FILES `689522e60c59e9b0c0de50e6ed9cab3305da66d3cacfbf8e6e88cfb0f95b66f3`; patch `e41880c02b315aea9c5f4294be59e0d25ffa5a94a1951f1464dfb93aa34a8183`; dependencies unchanged `fc882ce19ad800c8ce6421329226cdeb550de9ae8cc3f06525128e02b2d48911`.

All eight selected LF files, BASE/raw WORKING_BEFORE and35 saved/committed dependencies match. Core production bytes remain exactly V1. The eighth fixture explicitly supplies SavedSessionCutoff(null,null); the mandatory constructor blocker is resolved without a production default.

The Cell source now snapshots real demand/WAL rows rather than nonexistent BuiltRun.actions/admissions. `source_demand.lifecycle_lease_generation` and `demand_id` match the pinned actual entity columns. Both new and inherited negatives change the exact existing capture demand while keeping original authorization/WAL; the existing recomputation rejects that real inconsistency. The inherited case restores the demand, confirms visible readings, then checks its original fence. No action requirement, new query, storage or architecture is introduced. The corrected production and fixture descriptions explicitly separate Cell demand/WAL from Activity/Wi-Fi actions.

Activity and Cell WAL assertions correctly compare all non-payload fields after borrowing the exact expected array reference and compare actual payload contents separately.

## Remaining concrete test-source blocker

The new Wi-Fi positive still ends with `assertEquals(wal, database.sourceEventWalDao().getByEventId(EVENT_ID.value))` at selected WifiWalQualificationAdapterTest.kt:191. Pinned SourceEventWalEntity.kt:52/:125 defines a data class with ByteArray payload and no custom equals. Each Room materialization produces its own array; generated equality compares payload references. This assertion cannot establish preserved data and can reject an unchanged actual WAL. Repair only this new assertion using the same before/after non-payload identity plus contentEquals approach already used for Activity/Cell. V2's addendum currently claims original WAL assertions all avoid reference identity; the source must match that claim.

V2 remains held for this narrow assertion repair. V1's constructor/Cell findings are resolved; no additional production blocker was found in the previously reviewed original-authority predicates. Existing adapter backlog and whole-entry/export/runtime boundaries remain open. Preserve exact V2 input before the focused repaired freeze.

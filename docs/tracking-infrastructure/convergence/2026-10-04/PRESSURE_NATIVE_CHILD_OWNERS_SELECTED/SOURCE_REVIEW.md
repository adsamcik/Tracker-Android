# Independent static source review — Pressure native child ownership

Date: 2026-10-04. Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Reviewed the exact two before/after snapshots, their source differences, existing native-owner DAO/helper, complete native Pressure selector, pure portable comparator, imported evaluator/retained keys and four new Room scenario sources against the inherited user requirements and approved exact owner lookup. No concrete defect found in this narrow shared-reader repair. It is ready for a coherent unvalidated source commit after its lookup dependency is selected.

`PressureHistoryPageReader.kt.after:197–244` resolves each evaluated import's own entry/run/window keys (or authenticated retained keys) through the bounded exact lookup and complete original source selector. It prevents one neighboring import from lending another native ownership. An owner under a different logical entry produces the existing integrity result; same-entry exact-content comparison and retained same-entry behavior are unchanged. Run ownership continues to block borrowing after deletion or retention loss. No date census, storage, public API, permission, format, UX or dependency was added.

The existing per-candidate scan bound, native owner/member lookahead and typed budget classification remain; missing exact native membership is an integrity failure and cancellation propagates. The comparison operates on the already selected complete Pressure entry and starts no physical source or projection. Four new actual Room source cases cover an equal run under a foreign entry, an equal window under a foreign run, deleted native ownership and native retention-loss ownership. The two prior lookup cases and all original tests are preserved. Authored cases were not compiled or executed.

## Exact pins checked

- Manifest: `c820be77acea0917cb8860d62695a4bdc486f1c1b3f0754c8d6010d6285b67a9`.
- Boundary: `1e3c7296983aea3ca672ff17f4b2e8d88a525493ab0be4fa32985872e3955330`.
- Selected production before: `c2ac7ddc1204b693a71894bb118e2c8ed43957d97a94233f64afcbff0ebdec26`.
- Selected production after: `889522469eb08af64865de3d3356b7307b8ef41109cd3291ef930315adac7e01`.
- Selected test before: `7168be4a2652f59c77f453cf54ca7047af75dab7af3833770598b98e467054a2`.
- Selected test after/actual: `69596463d1fedad64aa61dda9b36d4b74f56cb52af5b661ad7d578bb9eddd897`.
- Preserved working production before: `f053559c1e2ee5b94dd6c7848afa8e9dad1e3242d7f7c20b7c9b556b08c6a427`; working after: `df969e83678410dabfb59f5010104e1ffb1e116f607195d52144dffb871e30db`.

The held R11 selected-import method is excluded: this receipt accepts only the selected production snapshot, not the whole working class. Source-specific legacy composer and export collision parity, full History/UI assembly, schema/gate/device evidence and all 25 full chains remain open. No Git or validation/provider gate was used.

## Test-only fifth-case recheck

The subsequent revised selected test adds `retainedImportedPressureShellCannotClaimAnotherNativeRun`. Independently compared it with the preserved four-case source: it uses the actual existing import and retention operations, advances the real retention floor, explicitly requires the evaluator's `Retained` branch, then checks the shared reader rejects its protected run key owned by a different native logical entry. The existing production lookup still resolves retained keys and retains native run ownership; no production byte changed. This is a meaningful authored check of the retained path, not an executed result. No further concrete defect found.

- Revised manifest: `939d79c5bbd792909aab21c35dc5d7972782e5dc4da67ccc5343f457e8fe0693`.
- Revised selected/actual test: `9228380a55aae3d6958c0999f772a779f5fab9014bac45d207b954f553000508`.
- Selected production remains `889522469eb08af64865de3d3356b7307b8ef41109cd3291ef930315adac7e01`.

This acceptance now covers five newly authored cases. The original four-case pins and review above remain as review history. Static readiness and all phase/full-chain limits are unchanged.

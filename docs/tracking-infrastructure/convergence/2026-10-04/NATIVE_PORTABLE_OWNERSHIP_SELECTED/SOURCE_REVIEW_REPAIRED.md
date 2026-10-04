# Independent static re-review — ownership lookup companions

Date: 2026-10-04. Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

The initial `SOURCE_REVIEW.md` and rejected freeze remain intact. Reviewed the exact revised selection and N1/N2 companion patches, BASE/SELECTED snapshots, existing raw evidence model, native exact Activity backend and both new actual Room scenario sources against the inherited requests and approved small owner lookup. No production edits, Git, compilation, tests, schema or provider/device gates were performed.

## Recheck findings

**N1 closed for the combined selected source.** The revised lookup patch differs only by removing the unrelated caller-projection test, leaving its 15 lookup cases unchanged. The explicit two-path raw-lifecycle companion projects the existing nullable caller reference and its 21st SQLite storage class, matching `RawSessionLifecycleIntentVersion`. Its separate Room case checks exact caller-reference preservation and rejects a BLOB value. It adds no schema or new authority contract. The revised lookup alone deliberately does not include that correction.

**N2 date widening repaired, with N3 still blocking the Activity companion.** The existing source-owned local comparison class now overloads its read with `ExportSelectedPortableCapturedActivityRequest`, rejects imported IDs and maps the existing exact backend results with unchanged cancellation propagation. The selected repository uses exact original segment seeds and the current snapshot epoch rather than dates, rejects unexpected returned identities and preserves typed dependency overflow. The two new Room sources exercise an overlapping unrelated invalid native fact and a different epoch. Ordinary source date-read behavior is untouched.

**N3 — mixed value-bearing and value-free local history becomes unavailable.** Selected `ACTIVITY_EXACT_OWNERSHIP_COMPARISON_SELECTED/SELECTED/.../DefaultActivityHistoryRepository.kt:217–222,839–855` sends all local recent entries through the strict exact export reader. The old date reader omitted factless native groups and the existing `loadValueFreeLocalProtectedOwnership` preserved their identities. With one valid fact-bearing local group, another already-qualified factless Activity group and a readable import, exact `readLocalInTransaction` now includes both groups; the factless chosen source cannot satisfy portable terminal settlement or `buildRun`'s captured-manifest/fact-revision equality (`ActivityCapturedPortableTransfer.kt:2508–2515`). The whole ordinary mixed recent page becomes unavailable. The existing factless-local-plus-import case has no fact-bearing local sibling and reaches the early `NoEntries` branch instead, so it does not cover this regression. Preserve the existing value-free ownership contract in comparison without weakening exact export completeness; add a real mixed local value/factless/import source case. The author owns the smallest partition/reader repair.

The combined lookup/N2 companion is not ready for a coherent unvalidated commit until N3 is repaired. N1 and the separate migration fixture are coherent narrow source selections; no full-chain or runtime readiness is asserted.

## Checked exact pins

Revised lookup:

- Manifest: `44da9787c2eeeddf0e6c4cc683a4a11a43f5531ef17f1b1dab07227a5f38920c`.
- Patch: `09a05baaa7a5ddec6a917e1df12f02147c0b8b56966e7c6b9d4edb61e70978e5`.
- Boundary: `b4065ad86fce9daca9924f165edd2c0caf1fdbe6ac8a428abd38c3dee80924a1`.
- Revised selected SourceSessionDaoTest: `7bf186630c5a3ed9d310c02dd22499527ada0269088b542a8c3e0e8f667cce6d`; all remaining source pins are the unchanged original reviewed selection.

N1 companion:

- Manifest: `b47f06fa901e783e1cd74f62a0f4fe093038a80acc319de22dc732699c695074`.
- Patch: `cb672db33e6bb37561b2baf2f9eece27f6eb0f02bedb5c981efd112d5c23ff8b`.
- Selected SourceSessionDao: `051198f0d90c6d94db3f348f9d983305c772a5dc9eb6591fd257c656e2ee270b`.
- Selected SourceSessionDaoTest: `f48a123a92846277f5c5007a7204e1227bdc53708cf5c9dae9651bf8d387bb08`.

N2 initial repair companion, held for N3:

- Manifest: `8663916baa9fc320cb04ab66d952ad6e302a492f33afd6f6af7de2a4eddcdf37`.
- Patch: `06d5691dd27d950ee28f2024329c88d18dc16ae3f5ab91e67877e36d33d8725d`.
- Boundary: `8821c57b9d05d07571bcd45bb53e379961d26359059cea9a898e39546dfd130a`.
- Selected ActivityCapturedPortableTransfer: `786e3ed52c469e993c12bb91ec9b4e21a011d9742595c58cc0e005b91f8aa433`.
- Selected DefaultActivityHistoryRepository: `e3ab903bfa70ba57364b28e23d4902bdbffaa3ba15b80b7fd09739a82716f123`.
- Selected ActivityHistoryComposerTest: `50bf6f54d0301507c313f46a3e4487835c23052b21e280349d6a4fdc05ab1703`.
- Selected NativePortableOwnershipHistoryFixtures: `ceb869527846d357916f40ac77f17fbfe6ebaf76b7ed26d0ed3cf76a01b44b9c`.

The separately reviewed migration fixture pins remain in the initial receipt. Held R11 files and bridges, unrelated Pressure runtime changes, source-specific parity beyond these packets and all 25 assembly chains remain outside this acceptance.

## Subsequent N3 recheck — exact partition repair

Rechecked `ACTIVITY_EXACT_OWNERSHIP_COMPARISON_REVISED_SELECTED` independently. **N3 is closed** for the mixed valued/factless case: the common comparison helper partitions already-qualified entries by retained activeTime/fragments, exact-reads only measured owners and leaves value-free owners to existing protection. The strict export backend remains untouched. Two further Room source cases contain actual different native logical IDs and check independent import visibility and exact duplicate suppression beside a factless owner; their optional logical-ID fixture propagates through original sessions/runs/manifests, revisions/cursors, authorization and completeness while preserving existing defaults. All four new companion cases remain unexecuted.

**N4 — ordinary recent history drops surviving window ownership for value-free native entries.** This broader partition also skips erased/retained native entries that still have saved window revisions. Selected `DefaultActivityHistoryRepository.kt:943–992`'s `loadValueFreeLocalProtectedOwnership` protects only entry, run and deletion-scope keys. Unlike `loadLocalProtectedOwnership`, it omits surviving window IDs. `ActivityHistoryComposer.kt:190–193` makes a fenced native group value-free; a different imported entry using its native window under another run can therefore avoid ordinary recent-history collision detection. Shared imported eligibility still includes those exact window keys and rejects the same claim. The old portable date comparison processed retained lineages or refused deletion, so the new skip introduces this qualification discrepancy. Reuse the existing bounded full local protected-owner helper for value-free owners, filter out already-portable entries and preserve its budget classification; do not weaken export completeness or add storage. Add a real erased/retained native-window foreign-run collision source case. Author/root notified before acceptance.

Latest N3 packet remains held for N4; the separate N1 and Pressure packets' static conclusions are unaffected.

- Revised N3 manifest: `a1bf2cd100b5ff52bb5355474d9e430ecdc2f0af65a441100a4c18ca23186d90`.
- Revised N3 patch: `97902d0158b7b25e0bb74f3378ee2ef7b8636bc0daf86bcbdd69b80385d63ab2`.
- Revised boundary: `60869729ce598eac4f8e7db295b312a6d63c6afd5cb8499cb074e429c3a182f5`.
- Selected local reader: `786e3ed52c469e993c12bb91ec9b4e21a011d9742595c58cc0e005b91f8aa433` (unchanged from first companion).
- Selected repository: `5c73b365e82c6479cf9bc084b17a95432acc9f02e51e146a706c25f8eb026679`.
- Selected composer test: `8adaeee7d5d12a780ad4f2333d2fa0d6da80c55926c7a30a4aa7e78671c69c6e`.
- Selected unrelated-owner fixture: `ceb869527846d357916f40ac77f17fbfe6ebaf76b7ed26d0ed3cf76a01b44b9c` (unchanged from first companion).

Exact selected hashes matched their manifest; no source execution or whole mixed-working-file acceptance occurred.

# Accepted CONTROL expiry — independent source review

2026-10-03. **IMPLEMENTED_UNVALIDATED; IMPLEMENTATION_ONLY.** Reviewed the original user requirements and approved 48-hour CONTROL lifetime against the three-path selection based on `16458b8a591dbbb8859eb0add85940cc92034540`. No checks or providers were executed, and no production or Git changes were made.

## Result

The earlier accepted-action expiry finding is closed at the static source level. No remaining concrete defect was found in this narrow selection. `ActivityControlRetentionMaintenance.kt:95–110` now removes an expired accepted singleton only when its exact `acceptedLogicalTrackingId` resolves to an existing session in the shared terminal-state predicate. The existing predicate (`ActivityAutomaticStartActionRepository.kt:678–682`) retains its three states, FINALIZED/CLOSED/FAILED; its sole change is private-to-internal visibility. Active and missing sessions do not gain fabricated terminal authority.

The read and exact trigger/collected-data-epoch deletion occur in the existing Room transaction (`ActivityControlRetentionMaintenance.kt:50–114`, `ActivityAutomaticStartActionDao.kt:133–138`). The startup generation is checked before work and again before commit; failure rolls back that transaction. Concurrent reservation/terminal changes cannot replace the singleton between this transaction's read and delete. The added branch touches no session, manifest, lifecycle intent, captured Activity fact, ambient fact, consent, or provider. Existing bounded outbox/raw cleanup and cancellation propagation remain unchanged. The subsequent raw cleanup still independently fences its own transaction.

## Authored evidence and limits

Exactly **three new, unexecuted** real-repository/Room cases were added at `ActivityAutomaticStartActionRepositoryTest.kt:246–300`, sharing the existing qualified acceptance fixture: retention immediately before and exactly at 48 hours with session/manifest/intent survival; an expired ACTIVE action retained until the exact session becomes CLOSED; and FAILED cleanup followed by singleton reuse and rejection of a delayed old caller. Existing cases are unchanged. The end-state fixture uses `SourceSessionDao.updateSession`; these are not real End/Turn off, service, device, generation-race, or full CONTROL lifecycle tests.

An absent accepted session remains a separate recovery/retention risk under the existing contract: both reservation and this maintenance require positive terminal session evidence. This slice intentionally does not infer completion from absence or broaden cleanup authority. Complete lifecycle proof, runtime validation and convergence remain open.

## Exact inputs

| Input | SHA-256 |
| --- | --- |
| `CONTROL_ACCEPTED_EXPIRY_FILES.json` | `4eaad2e716b1bfa96ab2d2445a4ddf4ff9dec41c4cef4e2acc61c1b708f74629` |
| `CONTROL_ACCEPTED_EXPIRY_SELECTION.patch` | `30102bcbbdde7b88aa8ced3ce33362be4e4f7a546cbb60a7dabb25b64c361112` |
| `ActivityControlRetentionMaintenance.kt` | `9c9416091c9bf24c818be35b51f79853aedf08f049b17827615a794b17847a5a` |
| `ActivityAutomaticStartActionRepository.kt` | `7c90ab5b1a99351f80f727bd39e277f1d9e09bd1a724da7ee207cd80994325ef` |
| `ActivityAutomaticStartActionRepositoryTest.kt` | `e55fb5103937e83ac43e0594c227b3bd2226d4e28ff190a3d12a092f75bdd2d9` |

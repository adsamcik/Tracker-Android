# Independent review: R11 reconfiguration acknowledgment origin

Date: 2026-10-04. Reviewer: fresh_radio_schema_review_1004.

## Verdict

**Accepted for this narrow implementation-only source repair.** No concrete blocker was found in the exact selected snapshots. The two prepared-manifest conditions repair the diagnosed Steps and central Pressure acknowledgment mismatch while preserving the existing immutable timeline and exact selected-action checks. This does not accept the whole source lifecycle or any assembly prerequisite.

Phase: IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. No production/test-source edits, Git mutations, recursive delegation, Gradle/compilation/tests/lint/Detekt/schema generation/drift/diff--check, provider/device/UI/battery/CI activation, rebase/merge/push or integration occurred. Sixteen authored cases are unexecuted, not passing evidence. All 25 full-chain prerequisites remain open.

## Exact selection inspected

- FILES.json SHA-256: `6daff35a4f7a62239f939c4c367af585fc2debfa053c7a8274ab7dfb48bbff24`.
- SELECTION.patch SHA-256: `ce475495ec4540c0c65f2370ca0389a4ccbed30f6aa0c2358a9bf3228b7c8e45`.
- SOURCE_BOUNDARY.md SHA-256: `ebba0a990aa1e38c8bd6ce796cc9772de2acb01d5a772b0c53776c09f8756dde`.
- Root-exported committed BASE receipt: `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`. This is a source-selection receipt, not a new integrated validation result.

All two BASE and three SELECTED file hashes match FILES.json. A direct source-text comparison confirmed that each production SELECTED is exactly its BASE with its single `startOrigin == run.startOrigin` conjunct replaced by the prepared-manifest condition. There are no other production deltas in this packet.

| Selected path | SHA-256 |
| --- | --- |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/lifecycle/DefaultSourceLifecycleProofAdapter.kt | 8d4c5ccbc00ee18b7723f933368ff2812c18da570235f779eac3569d59716228 |
| core/base/src/main/java/com/adsamcik/tracker/shared/base/database/PressureFactProofAuthenticator.kt | 0d88be79bddca6b57856645de92c73170865ad83f16ca015d6c8010419b7d74e |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/lifecycle/ReconfiguredSessionSourceProofRoomTest.kt | b84ac2a7c777b763222c2e0057dcc5dc714898863aef97eb8e432a21ecfbe154 |

The lifecycle working file contains concurrent Wi-Fi changes. It is not this selection. Using BASE plus the supplied narrow patch preserves the prior committed Cell child proof closure and excludes those unrelated Wi-Fi hunks. No Cell authority comparison is relaxed by R11. Pressure History/export parity is outside these two production paths.

## Production authority trace

### Steps

The selected lifecycle adapter's public recording path (335-391) reads the real Steps count-domain owner, authenticates the canonical WAL carrier, and invokes historicalSessionAuthority. That helper loads exact historical provider registration, plan, policy, consent and reconstructed authorization. It validates the complete bounded manifest timeline through SessionManifestIntegrity at 2171-2177, verifies each manifest checksum at 2185-2194, and identifies the exact selected manifest before reading its source start action at 2220-2244.

The private action predicate (2295-2332) retains all existing scope, source, manifest, plan, policy, consent, provider, lease, status, attempt and acknowledgment-time checks. Its action origin must equal the selected manifest origin. Equality with the original run origin is required only for the prepared manifest (2320-2321). The predicate has one call site, behind the complete timeline validation; it does not create a separate unauthenticated path.

### Pressure

The selected central Pressure authenticator loads the run and session, validates the complete immutable manifest timeline and checksums at 303-322, then identifies the selected manifest. Its existing provider/authorization/policy/consent/floor and measured-window checks remain. The exact selected action is selected at 516-520 and authenticated at 521-533.

The private predicate (1049-1095) applies the same prepared-only original-run equality at 1076-1077 while retaining action-to-selected-manifest equality and the existing remaining checks, including acknowledgment chronology.

### Timeline prevents a blanket relaxation

The unchanged SessionManifestIntegrity helper, SHA-256 `b8f904e77abf0906a1679eb9fdc55add67487d0289bfa1f57f97584352f21833`, requires the first manifest to be the run's prepared revision and original Manual/Automatic origin, validates the initial session mode, and requires subsequent consecutive revisions to use POLICY_RECONCILIATION origin and reason with compatible run identity and chronology (71-126).

Consequently a later manifest cannot reuse MANUAL/AUTOMATIC simply because the predicate no longer compares that later origin to the original run. Missing prepared revisions and invalid later origins fail before that predicate. A mismatched exact action still fails even when the timeline is valid. The immutable run origin is not rewritten.

## Authored fixture meaning and limits

The selected 489-line Room fixture contains 16 public recording scenarios: initial Manual/Automatic and later same-run reconciliation positives for both Steps and Pressure, mismatched initial/later action negatives, missing-prepared-timeline negatives, and invalid later MANUAL manifest negatives. Later-invalid manifests have recomputed checksums and matching actions, isolating the timeline guard.

The fixture seeds AppDatabase with real session/run/manifest/source-binding/plan/policy/consent/provider/authorization and WAL rows. It uses the production plan and payload codecs, actual WAL insertion with calculated integrity identity, actual StepsCountDomainStore.recordSessionWal, and DefaultSourceLifecycleProofAdapterFactory. Only unrelated Location/Activity/Wi-Fi/Cell qualifier dependencies are mocks; the exercised recording ports, private predicates and proof results are not mocked.

Static prerequisite inspection found compatible physical fingerprints, acquisition floors, report latency and window bounds. The Steps plan's third argument is zero maximum report latency; its 1000 ms fourth argument is a projection checkpoint interval, so the zero-latency Steps demand is satisfied. Pressure's 200 ms window and 50,000 microsecond cadence agree with the stored floor and canonical four-sample carrier. Both revisions reuse one physical registration; observations lie wholly within the selected authorization and manifest interval. Negative expectations match the existing typed Steps/Pressure failure mappings.

The initial positive cases also retain a valid later reconciliation manifest and prove that the selected earlier action remains valid under a complete newer timeline. No actual Android callback, lifecycle activation, Hilt/Room compilation or test execution was performed. Compilation, source gates, full-chain scenarios and device evidence remain deferred to the declared convergence batch.

## Scope and maintainability

The repair reuses the existing timeline authority and changes exactly two comparisons. It adds no schema, storage, API, dependency, collection mode, policy, UX, provenance rewrite or new abstraction. It is proportional to the diagnosed defect and the approved compatible reconfiguration behavior. The earlier R11 diagnosis receipt remains preserved as first-attempt evidence.

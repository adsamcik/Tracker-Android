# R11: reconfiguration acknowledgment origin

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

## Selection

Base HEAD: `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`. `HEAD_BASE.json` is the root-exported committed-byte receipt. Apply `SELECTION.patch`; the two production paths select only the original-run origin condition. The new Room fixture is owned in full. `WORKING_BEFORE` preserves actual authoring inputs; those may contain other agents' dirty Wi-Fi work and are not staging inputs. `BASE` plus this narrow predicate produces each pinned `SELECTED` source. The actual lifecycle file remains mixed; do not stage that path wholesale.

## Existing contract and repair

A physical run retains its original Manual or Automatic start. The coordinator appends later manifests/actions as `POLICY_RECONCILIATION`. Both affected callers already authenticate the complete immutable run timeline through `SessionManifestIntegrity.hasValidServiceRunTimeline`: the prepared manifest matches the run origin, and later consecutive revisions must have reconciliation origin/reason and valid chronology.

The Steps shared lifecycle acknowledgment and central Pressure acknowledgment still require the exact action origin to match its selected manifest. Original-run equality now applies only to the prepared manifest. All existing action scope, policy, consent, provider identity, times, status and attempt checks remain. No new authority, schema, API, dependency, rewritten run origin or blanket relaxation. Native Cell's separate timeline path remains unchanged.

## Static source trace

- Steps factory recording -> canonical Steps WAL/count-domain owner -> historical provider/plan/policy/consent/authorization -> complete run timeline/checksums -> exact selected source action -> recording result.
- Pressure factory recording -> canonical qualified Pressure WAL -> Room-loaded central proof snapshot -> complete run timeline/checksums -> exact selected source action -> recording result.
- The fixture uses AppDatabase, production codecs, real source-owned count-domain receipt writing for Steps, and the real lifecycle factory. Only unrelated Location/Activity/Wi-Fi/Cell qualifiers are mocks. No private predicate or proof result is mocked.

## Authored, unexecuted cases

- initial manual Steps action matches original run
- initial manual Pressure action matches original run
- initial automatic Steps action matches original run
- initial automatic Pressure action matches original run
- manual Steps remains proven after same-run policy reconciliation
- manual Pressure remains proven after same-run policy reconciliation
- automatic Steps remains proven after same-run policy reconciliation
- automatic Pressure remains proven after same-run policy reconciliation
- initial Steps action cannot claim reconciliation instead of original start
- initial Pressure action cannot claim reconciliation instead of original start
- later Steps action cannot reuse original manual start origin
- later Pressure action cannot reuse original manual start origin
- Steps later action cannot prove a timeline missing its prepared manifest
- Pressure later action cannot prove a timeline missing its prepared manifest
- Steps later manual manifest fails even with matching action and valid checksum
- Pressure later manual manifest fails even with matching action and valid checksum

Each negative case reaches the public recording API with otherwise matching stored source authority. The invalid later-origin cases have valid recomputed manifest checksums and matching actions, ensuring the timeline remains the required guard. Positive cases retain both immutable manifests, the original run origin and one compatible physical registration.

## Evidence boundary

No compilation, test execution, lint, schema/diff check, provider/device/UI/CI activation, Git mutation or integration ran. Sixteen written cases are not passing evidence. The room/schema/DI source prerequisites are current committed candidate inputs, still awaiting convergence. This packet closes only the diagnosed acknowledgment comparison in source; it does not close ambient shared-proof contracts, the whole source lifecycle, History, export or any of the 25 assembly prerequisites.

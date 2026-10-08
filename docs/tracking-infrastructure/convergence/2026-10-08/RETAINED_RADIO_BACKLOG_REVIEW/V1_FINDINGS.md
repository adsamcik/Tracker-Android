# Retained radio backlog V1: independent source findings

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Review only; all four authored methods
remain NOT_RUN. No tests, compile, build, schema, lint, diff-check, providers/device, CI,
Git mutation or integration ran. V1 is not accepted because of the concrete Cell fixture
blocker below. Root owns any preserved V2 repair.

## Exact input

Five source paths: two production adapters, two existing Room test files and one shared
test-only lifecycle fixture. FILES fc3e919d314c17e5186176e203ae00da27d68916b2efc33d1a5b93f60cdf39cf;
patch 493bc6155503593f31667cd234bd3fe214dd04a5bd187c120c123dc325ed74ca;
eleven deps d3e4440cd5be639c70590dfdbeb59bfc3250e5e9c58f78b0b1ca280edbc52b59.
Base/current 3b4600dd530fb7f4161d7e628d00f0d8c96c1dc4. All five BASE/SELECTED/raw
WORKING_BEFORE hashes match; selected source is LF; all eleven exact dependency pins
match current committed source. Four additional core purpose-reader inputs are pinned
in V1_INPUTS.json. The mixed Cell test draft is preserved independently.

## Concrete blockers

1. CellWalQualificationAdapterTest.kt:1551-1562's new negative mutates
   lifecycle_desired_action, then expects every returned group to be Rejected.
   installHistoricalCellGroups:1902-2040 seeds no Start action. More importantly,
   CellWalQualificationAdapter:127-139,391-466 and its actual
   qualifyCellPurposeItems -> RadioPurposeItemAuthorityReader -> RadioPurposeItemQualifier
   path never read lifecycle_desired_action. The SQL UPDATE reaches zero relevant rows;
   direct subject.qualify still evaluates the two otherwise valid historical groups.
   The narrow repair should exercise Cell's actual immutable demand/authorization boundary
   and name it accordingly. For example, change source_demand.lifecycle_lease_generation
   while leaving the original sealed source_authorization unchanged. The real core reader
   reconstructs authorization from original demands (RadioPurposeItemQualifier:149-159)
   and returns AUTHORIZATION_MISMATCH. Require that real result and zero captured facts.
   Do not add a new production Cell action-proof requirement to justify the incorrect test.
2. Both negative cases use results.all without asserting group count. Groups permits an
   empty list, so this assertion can pass without reaching the intended two groups.
   Wi-Fi really authenticates its Start action (adapter:507-535,1018-1042); require
   results.size == 2 before its rejection assertion. A revised Cell top-level immutable
   authority rejection should assert its concrete typed result rather than invent Groups.

## Remaining source trace

No additional concrete production blocker found in this bounded V1 change. Wi-Fi and Cell
reuse savedSessionCutoff rather than borrow mutable latest-operation boot/generation;
the original prepared manifest boot is authenticated by the existing retained timeline.
Original positive generations are bounded by current run generation, but original named
manifest, sealed authorization/demand membership and exact source evidence still qualify
independently. Wi-Fi keeps its current relationship/replacement bundle checks unchanged.
Current ingress admission/Stop/permission/epoch checks are untouched.

Original Stop fixture uses the existing pure SessionLifecycleIntegrity checksum and exact
ACTIVE predecessor, then advances only the mutable operation fields. It does not claim an
actual coordinator End/retry or reboot. Positive cases first reach existing v6 real Room
DurableSourceIngress with a denied callback and two original historical groups; initial
captured fact count is zero. Existing adapters, writers and projection lanes are then
called. Wi-Fi strict original child times remain before per-session Stop/manifest ends;
Cell child windows intersect the original provider, authorization, consent and session
intervals. Original child names/IDs and WAL bytes are asserted independently of ByteArray
object identity. No fact is seeded to manufacture the positive result.

Epoch, deletion, finite measured windows, retained floor, original provider/consent/policy,
whole child qualification and current destination owner still apply. The test-only helper
copies the existing core fixture because cross-module internal test source is not a
production dependency. No new store, schema, live collection authority or framework is
introduced. Shared final binding/model/writer/lane companions, Activity clocks, unknown
original cutoff coverage and the remaining full-chain/device gates remain open. This
review does not declare a complete source, assembled setup or integration readiness.

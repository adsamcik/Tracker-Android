# Shared provider elapsed interval: V2 independent source acceptance

## Exact reviewed input

Baseline3a3476e63e5c3bc40e2697f5e503a94cb18592ed. V2 FILES SHA662a738bb13f85ef247f5cf6aa3692ee7cb6bee82af4cf3226ac6cb51be4cdd4; patch SHA108be9e976324c33d9d865fd40fd88e1336973bae945bcabe95f49c54d652138; unchanged9 dependency pins SHA35dac60a31d34d1bbd072c3541fd6a911cff4ef5a5a225289fe738f51fda86a9. Exact two selected paths, baseline bytes and all9 dependency bytes were independently hashed and frozen here. Current committed source base/dependency resolution is recorded in REVIEW_FREEZE.json and matched those baseline bytes despite disjoint intervening commits.

Original full human messages/approvals were inherited. V1 findings SHA1cf8946bf9bbe2c0c0875a1f91fc624895e5015d3de6a4ec75ddef5cf3d07b0f remain preserved. Exact29-pin pressure/shared-clock source trace was verified during V1 review; it remains the caller/scope inventory, not runtime evidence.

## Findings closed

- F1: negative reservedAtMs/reservedElapsed fields now use assertFailsWith at the actual existing entity constructor boundary; the remaining acceptedAtMs/acceptedElapsed/retiredAtMs negative fixtures are constructible and reach the authenticator Rejected assertions. The helper avoids duplicating nonnegative reservation guards already guaranteed by that constructor; reservedElapsed<=acceptedElapsed still rejects negative acceptedElapsed.
- F2: the carried derived-window test retains actual elapsed300-at-retirement Rejected, while elapsed299/equal-wall30 now expects Authenticated and the method name states both outcomes. No elapsed Stop boundary is widened.

No remaining concrete blocker was found in this bounded V2 source slice.

## Production acceptance scope

The helper removes redundant wall chronology for original provider intervals; the phone wall clock is not a monotonic lifecycle clock. It preserves original wall timestamps without edits and keeps accepted/retired wall nonnegativity, existing entity structural validity, exact source/registration/instance/clock/fingerprint/epoch/residency, reservedElapsed<=acceptedElapsed and original elapsed observation bounds.

Finite retirement still requires the original complete pair and valid terminal status, retirement elapsed after acceptance, every actual observation strictly before retirement and every real coverage end no later than retirement. Only a genuinely derived end-exclusive fact window may meet the retirement boundary; actual provider observations at that boundary remain rejected. Gap-only windows still invent no observation.

Current ACTIVE/current-boot identity and exact acknowledged prior-process/prior-boot terminal/residency/reuse rules are unchanged. Missing registration/finite pair stays invalid at the loader/helper or existing entity construction boundary. No cross-boot elapsed ordering, timestamp uniqueness identity, current permission reinterpretation or new clock store appears. Both bounded Room loaders and their overflow checks are byte-unchanged.

Consent/purpose, current callback Stop/permission admission, retained floor/erase/epoch/replay, and actual measured UTC timestamps/local display dates remain source-owned checks. This slice changes none of them and does not turn generic physical interval authentication into product authorization. Retention filtering is not removed or replaced with elapsed time.

## Test and whole-system evidence boundary

Five new pure helper contract methods plus one adjusted carried method are authored NOT_RUN. They cover shifted/equal wall values, real elapsed pre-acceptance/retirement, structural negative inputs and the derived window/coverage boundary. They are not Room/provider/runtime tests and do not prove compilation. The fixture's Steps source code does not imply Steps calls this helper; actual Steps authority is separate.

The exact29-pin trace lists remaining source-local Pressure, Activity, Location and Wi-Fi/Cell redundant wall guards. This shared-helper acceptance is not complete clock handling, Pressure proof/history/export completion, full source lifecycle proof, T25 closure or integration readiness.

## Disposition

ACCEPTED_SOURCE_ONLY / IMPLEMENTED_UNVALIDATED. Review-only; no source edits, tests/build/lint/schema/diff-check/provider/device/CI/Git mutation, activation or recursive delegation. All25/T25 OPEN. Root may select only these exact V2 source bytes under the existing implementation-only authoring rules; no execution proof is claimed.

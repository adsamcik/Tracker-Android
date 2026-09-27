# Radio power recovery contract decision

Status: proposed; dependent persistence wiring is not applied.

Original request: **Implement source-specific power behavior.** Preserve cheap Steps continuity
and passive radio callbacks; enforce finite active radio budgets, cooldowns and request ownership
without replenishing budgets through reconfiguration.

The approved policy is one optional active attempt per genuine live intent, with cooldown carried
across adaptations, physical restarts, recovery and subsequent intents. Existing Wi-Fi/Cell budgets
are actor-local. The existing `source_runtime_state` table supports opaque versioned checkpoints,
but there is no radio power checkpoint format or transaction to reserve an attempt durably.

## Recommended addition requiring confirmation

- Reuse `source_runtime_state`; add a radio-only versioned payload, not another table or dependency.
- Key spent allowance by the durable logical tracking ID, not provider generation, service run,
  profile, authorization revision or lifecycle-action retry. Record a reservation before the
  Android request. A crash between reservation and request spends the allowance conservatively.
- Persist source-wide cooldown and exact request owner/window alongside spent intent identity.
  Provider replacement retires request ownership without refunding the allowance or cooldown.
- Authenticate the current session authority inside the reservation transaction. Only an ended
  previous live intent and a genuine new selected live intent may receive another allowance.
- Missing, malformed or ambiguous recovery evidence grants no recovered active allowance;
  passive callbacks continue. Boot changes invalidate elapsed deadlines, never spent identity;
  carry the remaining cooldown conservatively using recorded wall time.
- Operational data deletion/retention must not refund an active allowance or source-wide cooldown.
  Retire exact request ownership while preserving operational power state; absence during recovery
  remains fail-closed for that recovered intent.
- Keep this operational state separate from observations, export evidence, consent and WAL
  contract versions. No provider activation, schema migration or sharing-contract change.

Trade-off: durable reservation prevents request amplification after process death; a crash can
forgo one optional request. In-memory ownership alone cannot meet the approved recovery guarantee.

The repository AGENTS.md requires confirmation of new data contracts. Steps continuity and
request fencing can be completed independently while this proposal is pending. The whole
source-power checklist item must remain open until durable recovery is implemented and reviewed.

Mandatory phase boundary: IMPLEMENTATION ONLY. Author source and tests; do not execute Gradle,
compilation, tests, lint, Detekt, schema generation/drift, diff checks, device/emulator/UI/battery,
CI, release or rollout gates. No activation, push or new dev/v10 integration.

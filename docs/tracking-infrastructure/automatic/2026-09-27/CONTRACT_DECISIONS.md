# Remaining automatic contracts

2026-09-27. **PROPOSED_NOT_IMPLEMENTED — explicit confirmation pending.**
48h CONTROL retention, shared collection and separately consented trigger authority stay settled.
User AGENTS.md requires confirmation of genuinely new design decisions. No response to either
question below has been received or inferred; dependent implementation remains unapplied.

## Capability presentation

The question tool presented:

> Your AGENTS.md requires approval for the new automatic capability presentation. Should I keep legacy Transition-off configurations saved but show automatic start as unavailable, with an "Enable Transition detection" repair, plus distinct provider/permission/capture/implementation reasons? Recommended: preserve saved choices and report the actual supported path; continuous Activity samples would remain unable to cold-start tracking.

Options: "Preserve choices; show capability and repair"; "Always use Transitions for automatic control".

The first option preserves legacy/custom configuration, reports actual limitations and provides a
specific repair. The second changes automatic control semantics: an old Transition-off choice no
longer disables Transition control. Neither authorizes continuous-sample cold starts, hidden
permission prompts, another FGS, fallback provider or captured Activity consent.

Use the existing source/availability surfaces with concise reason and actual repair. Do not claim
no-GMS automatic support or confuse implementation containment with a permission users can fix.
A selectable saved intent is not a functioning automatic capability. Source mode/writer reachability
and actual per-origin accepted mask must constrain any availability claim.

## CONTROL owner and expiry composition

The question tool presented:

> The 48-hour lifetime is settled, but automatic CONTROL still has no production owner or expiry enforcement. AGENTS.md requires confirmation of the remaining data-handling contract: retain only minimal Activity trigger/provenance fields; expire CONTROL after 48 hours using same-boot monotonic time, conservatively expire old-boot evidence; cancel expired effects and prune bounded batches before rearm/admission/maintenance; preserve independently authorized captured Activity proof. Then compose one exact-lease CONTROL owner through the existing Activity arbiter, with rollout remaining default-off. Approve this contract?

Options: "Approve minimal CONTROL contract and existing-owner composition"; "Keep this contract pending".

The lifetime is not reopened. Existing DECISION_CLOSURE D1 and DATA_GAP_CLOSURE section10 supply
expiry/minimization requirements; the remaining composition must make their exact implementation
concrete. Reuse existing singleton action/outbox/cursor and source-specific maintenance. No new
motion-history product, dedicated always-on service, sensor fallback or schema platform.

Bootstrap the one owner using the parent-issued exact lease and existing purpose-owner mutation
guard, which validates issued authority before publication. Admit CONTROL demand and obtain exact
Activity Transition registration acknowledgement; only then publish Ready. Calling the public
monitor's published-Ready precondition to bootstrap its own readiness would be circular. Do not
fabricate Ready identity as a bypass. Compensation and deletion must retire exact demand/provider
ownership before reporting completion. Each expiry/read/prune/export path must share the approved
48h meaning without extending ambient purpose eligibility or removing independent capture proof.

Default-off rollout and provider/writer activation gates remain in place. Prior pending radio,
passive Location, background Steps, host, product-proof and backup contracts remain pending.

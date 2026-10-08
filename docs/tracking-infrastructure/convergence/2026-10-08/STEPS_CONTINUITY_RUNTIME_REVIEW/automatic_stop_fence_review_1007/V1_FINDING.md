# V1 independent static review — not accepted

Review only. IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All cases NOT_RUN.
No source edits, validation gates, provider activation or Git mutations.

Exact reviewed V1 inputs:

- Base: `facbb650fd68cab3ca07d400f74c93e96f8f0124`.
- Observed committed dependency head: `f1bef520eed3cb8d26afebd6d6ab74a18bc4020a`.
- FILES.json: `074955c4d29df7bc0aa931489deb85675154ad951bcec1f754a73153a2962462`.
- SELECTION.patch: `649c9226ac30f1eb5692783ec82678e4c2b3df8789fe1271b73af2af0997a1e7`.
- DEPENDENCIES.json: `1ff944c819c9b7a381c0dfbb4df2971fe5d22c830d0837b151e6b3f5c7ae143d`.
- Eleven selected paths, nine production and two test; eight authored methods NOT_RUN.

## Confirmed blocker

Selected SourceBroker.kt line 75 adds `RetainedForSteps` to the sealed
SourceCallerAuthorityRetirementOutcome. The actual production
`tracker/engine/src/main/java/com/adsamcik/tracker/tracker/resilience/ActiveTrackingSessionCallerAuthorityReconciler.kt`
is absent from selection and does not handle this outcome:

- Lines 123–145: the exhaustive retirement `when` after successor replay handles only Completed,
  Retryable and the four terminal outcomes.
- Lines 193–212: `failureCode(): String = when (this)` omits the new sealed subtype.

This is a static non-exhaustive branch defect; no compilation was run. It also leaves the
production descriptor handoff/debt repair without the approved retained-origin result.

Smallest repair: extend this existing reconciler to handle RetainedForSteps alongside Completed
when clearing exact predecessor descriptor debt **after fresh successor replay**. Classify it
as a nonfailure in failureCode. Add one existing reconciler fixture case asserting successor-only
replay, one exact debt clearance and no predecessor replay. Preserve the original receipt for
its current recording; do not add a new provider activation or recovery permission.

Root and author were notified. V1 remains NOT_ACCEPTED until the exact repaired bytes are reviewed.

## Other reviewed paths

The actual coordinator preflight observes the shared physical Steps listener before Room. The
manifest/caller transaction validates the original/current consent, owner, rollout, epoch,
complete marked source timeline, accepted receipts, original active demand and exact current
process-bound registration. The fresh dispatcher remains in the current command path.

Physical apply checks the exact listener, token, lane, actor, accumulator/counter epoch,
registration, latest operation claim and permission after its Room authority check. A lost
preflight cannot start a provider with the old recording origin or stop a successor owner.
Generic shared Start/reconfigure cannot adopt that old origin.

Atomic Steps ingress retains observed-time registration/freshness, current deletion/startup
fences, the source-local marked window and durable counter checkpoint. End's final cutoff
remains bounded by exact retiring registration/demand, unchanged consent and durable cutoff;
ordinary legacy null origins retain the previous rule. Session teardown retires all session
references, including the original recording receipt.

The composed tests use real coordinator/shared owner/actor/caller receipts/Room ingress/WAL and
Steps writer. Android sensor, startup Ready, CONTROL availability and foreground enqueue are
fixtures. The three smaller runtime methods mock registration storage and test ownership/
permission boundaries. These authored cases are not a pass, hardware, battery or full recovery
proof. No other concrete blocker was confirmed in the inspected V1 slice.

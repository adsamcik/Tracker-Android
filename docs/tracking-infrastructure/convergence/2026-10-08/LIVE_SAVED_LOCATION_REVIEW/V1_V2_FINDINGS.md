# Live saved Location core independent review: V1/V2 findings

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Static source review only. All tests NOT_RUN. No validation, Git, live source or provider activation.

## Exact reviewed inputs

- Original V1 FILES `0dc03ed86bf50a528ed79414ce4c56513cbc28404dd3d887976bc352dde35491`, patch `991bc26d914b4dadb929fe35e32702417bdbec4479923cbdd9c5ff6a12c43816`;17 selected paths/21 new test methods.
- Repaired V2 FILES `9ab5c0d399aba7eb4ad9cdd738fe083a31d5d221e051a21b10a569f61b3c2e23`, patch `7e5adfd49d1ef26604febfb43ec49308b4ac66f9d09b94f6c46cf296396ef20a`;19 selected paths/24 new methods. V1 is preserved by the author. Original17 selected bytes remain unchanged.
- Historical saved-authority43-path common reader overlay FILES `c1b6807bb2b01414b51aafc6f75edb87afe375b352ba1ce03c92060c8a358b6c`; final dependency composition pinned tofe03d75a.

## Findings

1. V1 required interface implementation omitted. TrackerServiceController.kt:26 introduced the abstract savedLocationSummaryFlow property; the actual FakeTrackerServiceController implements that interface and lacked it. V2 selects the existing fake plus three focused contract methods, preserving its prior source methods. The fake uses the same active exact segment, host-loss, End and replacement publication boundary. This finding is addressed by V2 source. No compilation was executed.

2. V2 wakeup conflation can discard required invalidation flags. SavedLocationLiveSummaryReader.kt:40–43 maps a Room table set and a process hint with an empty table set into the same conflated stream. A queued purpose/dependency notice can therefore be replaced by a later hint. At82–84, unchanged global receipt ID and empty flags immediately reuse old progress before checking exact membership. The real handoff writes the accepted canonical receipt before committing capture-purpose membership in its following transaction. An already discovered receipt can gain the selected purpose at the same max receipt ID; its post-commit hint uses that same ID. If this replaces a pending purpose notice during a busy finite refresh/output, the live total can omit the now-saved point until another useful event. Rebuild flags are susceptible to the same stream operation. Keep event meaning through conflation with small cumulative point/rebuild wakeup versions, or an equally simple durable check; avoid reseeding for every ordinary hint. Add a meaningful source case for same-ID membership plus coalesced hints/dependency notices. This source finding remains open pending a preserved V3 repair.

## Other bounded source conclusions

The finite seed and accumulator carry compose with the historical common qualifier; current generation/operation boot cannot relabel original saved points. New receipt pages are bounded256 and measured ordering is checked across pages; older measured delivery reseeds instead of bridging an incorrect end. Missed and duplicate ordinary hints use the durable receipt cursor. Guards cover epoch, high-water, retained floor and retention-in-progress stamp; lost membership or additional older membership count requires a seed. Source instance/registration/sequence/clock gaps break distance continuity. Optional accuracy and actual zero remain usable. The controller binds one application-scope upstream and synchronously clears on host loss, null session info and replacement; late/mismatched physical-segment results cannot republish. Handoff hints occur after purpose/cursor transaction return, and rejected points are not advertised.

The13 real Room methods use explicit policy/manifests/sealed receipts/purposes, not physical providers. Six controller methods use explicit repository flows; two handoff methods use actual Room with fixture writer/qualifier; V2 fake methods cover the actual existing fixture implementation. Hilt, Android execution, actual event timing and all tests remain unrun. Numerics consumer wiring and effective correction/restore semantics are separate pending companions. No current correction store is invented here. Native originals are immutable in actual writers, and arbitrary raw simultaneous tampering/appending with unchanged guards is outside these claims.

This is a bounded independent review, not final assembly or T25 acceptance. V2 is held until finding2 is addressed.

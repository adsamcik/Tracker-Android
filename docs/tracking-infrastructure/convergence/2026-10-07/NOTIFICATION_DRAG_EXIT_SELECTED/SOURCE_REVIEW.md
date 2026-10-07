# Independent notification drag exit review

Reviewer: /root/automatic_stop_fence_review_1007. Original task messages and
approved ordered saves, preserved edits, accessibility and Retry/Leave choices,
exact selected source/diff and committed dependencies supplied. Static acceptance;
no blocker found. All 51 dependency pins match; root refreshed after radio selection.

Production source SHA-256:
061b0f1bf015bed6e03aa3f0429930760602732edd849ea97c119e4dfe4a37ea.
Test source SHA-256:
de97972807b867196c119bcbab846b46ef0c00c74dbcab75810abd6c71e769c8.
FILES SHA-256:
7a89883a6f9aa55baf71f2fd31999a19bb37acfc3ce6075fa2bd7a494b623d16.
Patch SHA-256:
439f52d68681f5404055cf86a08eb5f220dd7d189d2a551a98611ebb3445a88d.

The private Boolean marks only an order not yet included in a queued snapshot.
Full saves clear it. Back flushes before waiting; the existing wait loop also
flushes a newer drag before permitting exit. Later drag cancellation cannot
queue the flushed order twice. Failure retains edits and uses existing approved
Retry/Leave behavior. Ordinary flag saves include the current order. No new UX,
API, store, dependency or resource design is introduced.

Actual route Back, pointer end/cancel, accessible moves, full-catalog save channel,
repository mutex and transactional DAO were statically traced. Existing 13
ViewModel methods and two localized Compose methods are preserved. Three new
methods exercise the real ViewModel with gated/failing repository fixtures.
Pointer input, navigation/Hilt, Room execution and Android accessibility/device
behavior are not exercised by those fixtures. Full T17 remains open.
IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All methods NOT_RUN; no gates run.

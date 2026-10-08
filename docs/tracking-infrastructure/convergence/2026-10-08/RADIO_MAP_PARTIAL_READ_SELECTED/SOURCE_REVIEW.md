# Independent source review

Accepted by /root/automatic_stop_fence_review_1007 on 2026-10-08 against original
full task messages, follow-ups, practical-design approvals, exact source/diff and
authored test source.

Production fc3adb462ea4fee57f2e092cb0db86836574d505ae1c8cb5b14d215130856354.
Test d8bbd34f56343a42acd2ffd8a13ab7faaa29db55c122558adf61e9ab7de7fb7a.
FILES 3eae9abbb086913a9196648bb9d5f8e5e7b2b11c060c8122204320d404cf0eaf.
Patch f9ea11ee829172a881402497c93dbe7a619e9865cdf892732da22f7fa0283f98.

No scoped blocker. Failed optional enrichment cannot prevent a fresh independent
legacy read. A failed legacy source cannot prevent valid retained device points
from rendering. Both unavailable sources remain a load failure. Cancellation
propagates before fallback and the existing layer reload-generation fence stays
unchanged. Successful fallback rebuilds the current legacy-only configuration,
removing the previous device overlay rather than retaining it. Actual registered
radio aggregators accept empty lists and their encoders normally return null.
The exact typed optional-context outcome stays in the internal result.

Prior five tests retained, one expectation repaired, five new layer fixture
methods authored. All NOT_RUN. Repository boundaries are mocked; actual Room,
MapLibre/hardware and app deletion/invalidation execution remain separate. No new
notice, sensor demand, store or invented network/tower coordinates. Full T16 and
complete-chain acceptance remain open. IMPLEMENTED_UNVALIDATED.

# Independent released-data preservation design review

Reviewer: /root/automatic_stop_fence_review_1007. Original user requirements,
main-only released source, exact data mapping/normalization source, historical
8 main and 43 candidate pins, and V2 proposal supplied. Exact V2 accepted as a
lean proposal suitable for the user question; no scoped design blocker remains.

Proposal SHA-256:
f4a25a1fec81397bdf24f9d699d06d5b29e375852bdf15394d5687bf5290b81f.
Audit manifest SHA-256:
cd3a4d6b2bdf499db416b20f956a987b0e16411567d4089a54a1cbdbb720e35b.
V1 preserved under REVIEW_V1. Three review findings repaired in V2 wording:
restore the released Room declaration on the same existing NetworkOperator
class; absent-safe optional compatibility tables for known normalized v26;
finite untimed operator names kept until explicit all-Cell/full clear, with no
speculative orphan-pruning engine. The seventeen fields match actual released
normalization types. Existing annotations resolve chosen and built-in labels.
Two typed older-record tables preserve short/invalid sessions and count-only
Wi-Fi without fabricating native observation ownership. Parent cleanup and
existing marker-preserving transaction gates are reused.

The historical 7da374b5f4 snapshot and separately committed 6c7dd22d73 Activity
mapping repair are explicitly distinguished. CURRENT_PIN_OVERLAY.json records
only those subsequent reviewed Activity/label edits affecting historical pins.
No new storage, annotation backfill, table/entity or migration implementation is
selected. The questions tool requests the new storage choice; no answer is yet
recorded. PROPOSED / NOT_APPROVED / NOT_IMPLEMENTED. Test source remains NOT_RUN.
Complete old-source History/detail/export and final T18 remain open. No gates,
provider activation, integration or backup redesign performed by this audit.

# Independent source review: first-enable Steps publication

Reviewer: `/root/automatic_stop_fence_review_1007`, review-only, original full user
history and approved bounded wait. Exact repaired selection accepted statically.
IMPLEMENTED_UNVALIDATED; all ten authored methods NOT_RUN.

Exact production SHA: 4ac220a3600517e4bdf86ca7ddd39a9c306b1fcffdd25e89f4e701f792769529.
Exact test SHA: 13242d55199006434fb7ec3f9e49c6b0a1c31e9a40206bd06d9c922b9f0a1b28.
Both raw source snapshots, patch, byte counts and ten committed dependency pins match.

The sole concrete finding was fixed: advanced Steps frequency-Off could retain the
old enable opportunity while its save was suspended or failed. All Steps frequency
edits now immediately cancel that wait and advance the same intent generation;
two new cases exercise slow and failed saves. Other-source edits stay independent.

The child is bounded to two seconds and ViewModel lifetime, does not hold the
ordered save queue, does not replay a save, and keeps existing permission repair.
The existing screen checks actual Android grant, current selection and RESUMED
lifecycle. Callback failure preserves the saved choice without retrying activation.
No provider probe, durable request, API/schema/dependency or new UI/copy was added.
No remaining static blocker or unnecessary abstraction was found. This is not
compilation, executed-test, Android-dialog, provider, device or full-chain proof.

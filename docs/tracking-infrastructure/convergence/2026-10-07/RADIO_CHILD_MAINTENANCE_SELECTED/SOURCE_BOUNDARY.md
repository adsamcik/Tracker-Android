# Background radio child maintenance selection

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Production repair selected privately;
all test source remains NOT_RUN. No gates, provider activation or integration.

The approved radio details are children of native background fact revisions, with
an immediate RESTRICT foreign key. Current consent-reset erase, ordinary source
clear and retention prune delete those parents without deleting their children.
This is a correctness repair of approved erase/retention behavior, not a new data
handling design. Use the existing source-specific DAO deletes in each existing
transaction, before deleting selected parent revisions. Retention removes all
child revisions only for the already validated selected logical fact IDs.

Preserve authority, replay footprints, original deletion scope, epoch fences,
evidence revision/invalidation and independently authorized sibling sources.
No schema, new DAO/interface, dependency, UI, wire format or acquisition change.
The two preserved maintenance drafts already contain equivalent fixes; their full
bytes are retained under WORKING_BEFORE. DAO export drafts are not selected.

The actual full-clear wrapper already removes native and imported radio children
before the synchronous product helpers (AppDatabase.kt:1114-1128). No new full-clear
DAO or duplicate wrapper cleanup is needed. Imported child relationships use
CASCADE. The synchronous helpers are internal to that existing wrapper.

An independent author is preparing retained Room reader/maintenance composition
source. Fresh review will cover this production selection and those tests together
before root staging. No passing result or complete radio/lifecycle chain is claimed.

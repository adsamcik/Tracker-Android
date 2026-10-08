# Requested launcher widget removal

Human instruction: "Let's remove it", following the explicit Android-launcher Active Session widget explanation. IMPLEMENTED_UNVALIDATED, held for independent source review. No gates or providers run.

Remove the Active Session receiver, provider XML variants, preview drawable, Glance implementation, its source-only tests, exclusive formatter/preview helpers and exclusive localized strings. Remove its entry-point History dependency and the shared scheduler call. Quick Start and Today Summary remain existing separate widgets; the existing shared scheduler and actions still serve them. No tracking or retained data is erased.

The requested removal supersedes the unanswered thirty-second refresh proposal for this widget. No new refresh observer is introduced. Source deletion is exact; mixed drafts are preserved in WORKING_BEFORE. Other retained-data and shared-setup implementation continues separately. T25/all25 full chains remain open. No compile/test/lint/schema/device/provider/CI/integration/push was performed.

One new packaged-manifest/resource fixture is authored NOT_RUN. It asserts the two remaining launcher receivers and their real provider XML resources without starting Tracker. Removed-widget test sources are retired; shared worker, controls and generic format tests remain.

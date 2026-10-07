# Case inventory

All cases: NOT_RUN. Three additions use the existing real NotificationManagementViewModel with
its injected dispatcher and repository fixture. No new fixture/framework/API is added.

1. Back during an unreleased multi-step drag: no exit while its current order's save is gated;
   subsequent cancellation cannot save the identical order twice; completion yields one final-order
   save and one exit.
2. That Back save fails: final order remains visible, existing failure prompt appears, and Retry
   saves that order before exiting.
3. A second drag while Back waits for an older save: completion must queue/save the newer order
   before exit. Both snapshots and their order are asserted. Previously only the older one saved.

The existing 13 ViewModel methods are unchanged, including ordered rapid saves, load Retry,
retained failed-save edits and Leave without saving. Existing localized Compose action/dialog
cases are unchanged and pinned. Real Room/UI/device evidence and complete T17 remain pending.

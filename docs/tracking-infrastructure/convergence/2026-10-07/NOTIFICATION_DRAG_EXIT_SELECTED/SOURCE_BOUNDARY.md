# Notification drag exit: selected source slice

Phase: IMPLEMENTATION_ONLY. Status: IMPLEMENTED_UNVALIDATED. Every case is NOT_RUN.
No build, compilation, tests, lint, schema/diff checks, CI, provider/device activity, Git mutation,
integration or recursive delegation occurred. Root owns review, source selection and Git.

## Approved behavior and concrete gap

The user approved ordered saves, retained edits after failure, load errors with Retry, localized
Move up/down actions using the same saved order, and Back after an actual save failure with Retry
or Leave without saving. These are already committed at 2d979ea9850158529614bf8fd03f505a7ae91e9c.
The product decisions and later explicit Retry/Leave approval are inherited in full.
This slice completes those settled choices: no new UX, interface, storage, locale or dependency.

The drag handler calls moveItem while a finger is held and queues persistOrder only on drag end
or cancellation. moveItem changes displayed items but does not advance the queued save revision.
Back can observe the old completed revision, pop the route and cancel its ViewModel before the
current order is queued. The same loss occurs if a new drag is made while an older Back save is
suspended: the UI remains editable during that wait.

## Minimal production change

One private Boolean remembers whether a displayed order change is not yet queued in a full settings
snapshot. Any full settings save clears it. persistOrder queues a pending order once and ends its
drag state. Back flushes it through the existing ordered channel. Before leaving after an awaited
completion, Back checks for a further pending order and queues that latest snapshot too. A later
drag-cancel callback cannot queue the same order again. Failure retains items and uses the existing
Retry/Leave dialog. No new queue, store, lifecycle engine, public method or UI element is added.

## Existing chain and evidence boundaries

SettingsNavGraph -> NotificationManagementRoute -> NotificationManagementViewModel -> existing API
repository -> DefaultTrackerNotificationSettingsRepository -> transactional NotificationPreferenceDao.
The existing engine mutex protects load/save, validates the complete catalog, writes before applying
preferences and uses the same normalized order/flags as the runtime notification provider.

System and icon Back already use exitWhenSaved. Loading failure has Retry. Save failure retains
edits and offers Retry inline; Back shows Retry/Leave only after actual failure. Localized custom
accessibility actions call moveItemAndPersist, sharing the drag order/items/queue. Resources, Hilt,
DAO and navigation remain unchanged.

Three added cases use the real ViewModel and existing controlled repository fixture, with suspended
saves/failure responses. They do not invoke the actual pointer system, Navigation/Hilt route, Android
accessibility service or Room DAO. Existing 13 ViewModel and two localized Compose cases remain
unchanged. Engine repository tests use a fake DAO. All remain NOT_RUN. T17, full gates and device
evidence remain open.

## Preservation

Only two clean committed files are privately selected. BASE preserves raw committed bytes; SELECTED
is UTF-8 LF. WORKING_BEFORE preserves shared raw files; FILES records equal before/after hashes.
Shared source paths are untouched. Targeted status was clean; previous recovery/accessibility work
is committed at 57fe4ed329 and 65a9be4125. No newer dirty notification slice was selected.
Unrelated drafts remain untouched. DEPENDENCIES pins the module/API/Hilt/DAO/navigation/test and
existing locale inputs. Independent review is pending; no assembly-completion claim is made.

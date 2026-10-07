# Committed caller trace

Base: 2d979ea9850158529614bf8fd03f505a7ae91e9c. Source inspection only; NOT_RUN.
Exact full paths and bytes are pinned in DEPENDENCIES.json and FILES.json.

| Layer | Source | Behavior |
| --- | --- | --- |
| Navigation | SettingsNavGraph.kt | NotificationManagement onBack pops that destination. |
| Route | NotificationManagementScreen.kt | System/icon Back call exitWhenSaved; drag moveItem precedes later persistOrder. |
| Recovery | same ViewModel | Nonempty load, load Retry, ordered conflated save actor, failure state, Retry/Leave dialog. |
| Accessible order | NotificationItemRow | Localized custom actions call moveItemAndPersist at the current index. |
| API | TrackerNotificationSettingsRepository.kt | Full catalog/display order with stable IDs. |
| DI | TrackerEngineBindings.kt + TrackerNotificationPersistenceModule.kt | Existing repository and PreferenceDatabase DAO wiring. |
| Engine | DefaultTrackerNotificationSettingsRepository.kt | Mutex, unique/complete catalog checks, write then apply. |
| Storage | NotificationPreferenceDao.kt | Whole collection upsert has Room Transaction. |
| Runtime | TrackerNotificationProvider.kt | Applies persisted flags and normalized order to canonical components. |
| Test source | NotificationManagementViewModelTest.kt | Real ViewModel, existing gated/failing repository fixture, three added cases. |
| Existing Compose | NotificationManagementAccessibilityTest.kt | Actual row/dialog Czech app resources/actions, not the full route. |

feature:tracker depends on tracker:api, not engine implementation. Existing tracker.android.test
supplies Jupiter/Vintage, coroutine test, Kotest/Robolectric; Android resources are included by the
library convention. No module/build/dependency change.

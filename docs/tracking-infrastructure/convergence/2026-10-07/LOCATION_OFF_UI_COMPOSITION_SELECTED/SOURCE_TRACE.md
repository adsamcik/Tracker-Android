# Current production source trace

All references below are pinned committed source, not mixed working drafts.

1. `DefaultTrackingTurnOffController.kt:32-40` calls the existing atomic command reservation and its real Room fence. `SharedPreferencesTrackingLifecycleCommandAuthority.kt:72-116` writes Off plus STOP before the fence and refuses owner closure until that STOP is handled.
2. `SourceBrokerAuthorizationTransactions.kt:60-81` marks selected purposes retiring and rotates current physical authorization; `SourceBrokerDao.kt:148-152` excludes retiring demands from new callback eligibility. The case seeds one ambient Location demand and verifies deny-all current authorization before teardown.
3. `DefaultTrackingTurnOffController.kt:43-64` re-fences before calling the actual purpose fencer. `TrackingPurposePublicationRuntime.kt:659-719` ensures built-in owners, then calls bounded owner cleanup. Its built-in Location callback at `949-968` delegates to the existing AmbientLocationPurposeOwner.
4. `AmbientLocationPurposeOwner.kt:172-188` retires the exact broker demand and then removes only its ambient join. `SourceBroker.kt:260-279` authenticates the existing caller grant; `1023-1058` retires the exact demand and caller authority in Room. The test does not fake that retirement.
5. `SharedLocationSourceController.kt:343-400` retains independent temporary leases while its ambient join disappears. `89-115` switches to UI-only physical work after acknowledged tracked-owner retirement. `LocationSourceRuntime.kt:406-432` owns the physical shutdown, while `205-214` delivers UI-only points without invoking the durable sink. Registration/provider acknowledgements in this case are existing mocks.
6. `SharedUiLocationProvider.kt:29-105` owns separate collector leases and checks each request's freshness. The case uses its real map (10 seconds) and game (5 seconds) flows, and releases each independently.
7. `LocationSourceRuntime.kt:1217-1255` fences a retired tracked callback token. The case invokes the old tracked callback after retirement, then the UI-only callback, and verifies only the latter reaches the remaining UI collectors and neither adds to the recording sink.
8. TrackerService's existing `detachAll(...).firstOrNull { LOCATION }` at `2118-2120` is unchanged. This packet contains no service repair.

All results are source assertions only: NOT_RUN. Android/system service, actual ingress/WAL/materialization, active-session teardown and real battery evidence are outside this fixture.

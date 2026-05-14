# Debug Test Data Tooling

**DEBUG BUILD ONLY.** These files live under `app/src/debug` and are guarded with `BuildConfig.DEBUG`.

## In-app screen

Open the app debug build, then go to:

`Settings -> Debug -> Log viewer / Debug route -> Test Data`

The Test Data card can:

- Seed synthetic local sessions.
- Reset onboarding to incomplete.
- Reset local preferences / Proto DataStore files.
- Force dashboard history on/off for EMPTY / IDLE state testing.

## adb broadcasts

The receiver is non-exported and only registered in debug builds. Use the debug application id and explicit component:

```powershell
adb shell am broadcast -n com.adsamcik.tracker.debug/com.adsamcik.tracker.app.debug.DebugSeedBroadcastReceiver -a com.adsamcik.tracker.debug.SEED_SESSIONS --ei count 10 --ef distance_km 5.0 --es profile walk
adb shell am broadcast -n com.adsamcik.tracker.debug/com.adsamcik.tracker.app.debug.DebugSeedBroadcastReceiver -a com.adsamcik.tracker.debug.SEED_SESSIONS --ei count 3 --ef distance_km 20.0 --es profile drive
adb shell am broadcast -n com.adsamcik.tracker.debug/com.adsamcik.tracker.app.debug.DebugSeedBroadcastReceiver -a com.adsamcik.tracker.debug.RESET_ONBOARDING
adb shell am broadcast -n com.adsamcik.tracker.debug/com.adsamcik.tracker.app.debug.DebugSeedBroadcastReceiver -a com.adsamcik.tracker.debug.RESET_ALL_DATA
```

`count`, `distance_km`, and `profile` may also be sent as strings for simple shell scripts.

## GPX corpus

Bundled assets:

- `app/src/debug/assets/test_tracks/walk-loop.gpx` — short ~2 km loop.
- `app/src/debug/assets/test_tracks/run-out-and-back.gpx` — ~5 km out-and-back.
- `app/src/debug/assets/test_tracks/drive-route.gpx` — ~20 km drive route.

All data is synthetic and local-only; no network permissions or remote endpoints are added.

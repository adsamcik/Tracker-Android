# Native all-day Steps research

Date: 2026-09-30. Static code inspection and official documentation only.
No providers were activated and no builds, tests or device checks were run.

## What keeps counting

- Google Play services' local Recording API keeps its subscription active while Tracker is
  closed and across system restarts. It is accountless and designed for low-power collection.
  Calling `subscribe` again for an existing subscription is a no-op. Losing Activity recognition
  permission removes the subscription. Its stored data is accessible only while subscribed and
  retained for ten days.
- Health Connect can record phone Steps on Android 14 and newer with the supported extension
  and Steps read permission. The phone's low-power counter is batched, at most once per minute.
  Reading Health Connect does not make that sensor count more frequently. Background reads need
  a separate supported permission; without it, Tracker must read in the foreground.
- A direct Android step-counter reading is a cumulative total since reboot while the sensor is
  activated. It is not a history API with timestamped readings from the time Tracker was closed.
  Direct readings remain useful for live updates, but cannot manufacture historical daily counts.

## Why Tracker still needs imports

The native service can keep counting independently of Tracker, but Tracker's own history,
statistics and backups need a durable local copy. In this context, "backfill" means copying real
provider records into the Tracker database. It does not mean estimating missing steps.
Health Connect has no new-data notification. The Recording API documentation explicitly
recommends periodic WorkManager reads so its ten-day retention does not lose data.

## Approved cadence

Keep the selected supported recorder subscribed throughout all-day Steps collection. Sessions
should not turn that subscription on and off. Import when Steps is enabled and when Tracker
opens. Request an approximately hourly background import when Android and the provider's
permissions permit it; Android may delay the request. Bound each attempt to thirty seconds and
resume unfinished work at the next eligible opportunity. A fifteen-minute interval only makes
Tracker's saved history fresher; it is not needed to keep the native recorder counting.

Retain the existing capability selection and visible permission repair. Do not add direct sensor
and provider counts for overlapping time unless their count-domain ownership establishes that
they are different steps. Unsupported or missing periods remain gaps.

## Implementation status

The user explicitly approved hourly background copies plus enable/open, with each attempt
bounded to thirty seconds (`call_m6KyKKq7xe8E3Jehybsm0eg7`, item 0). The candidate now
contains the WorkManager scheduler/worker, bounded import runner, app foreground hook,
accepted-provider scheduling, explicit optional Health Connect background permission repair,
and predecessor handoff before provider removal. Focused source and Room test cases are
written. This is **IMPLEMENTED_UNVALIDATED**: no compilation, tests or device/provider
execution has occurred. The full Steps storage/history/statistics/export chain remains open
until the assembly and convergence gates are complete.

## API-version detail

The Health Connect mobile-step guide and framework reference give different extension numbers
for the device-origin lookup. The framework reference declares the actual asynchronous
`getCurrentDeviceDataSource` API at API 37 / U extension 22. The existing reader uses that API,
so its extension-22 check should not be lowered based only on the guide's example. Obtaining a
real phone origin remains necessary to avoid silently reading a wearable or another application.
The Android 14 / extension-20 phone counter capability by itself does not prove this lookup exists.

## Official sources

- [Recording API on mobile](https://developer.android.com/health-and-fitness/recording-api)
- [LocalRecordingClient API](https://developers.google.com/android/reference/com/google/android/gms/fitness/LocalRecordingClient)
- [Read Health Connect data and phone steps](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Health Connect synchronization](https://developer.android.com/health-and-fitness/health-connect/sync-data)
- [HealthConnectManager framework API](https://developer.android.com/reference/android/health/connect/HealthConnectManager)
- [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)

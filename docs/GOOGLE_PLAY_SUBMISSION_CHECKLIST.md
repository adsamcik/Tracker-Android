# Google Play submission checklist

In-app compliance status as of HEAD (2026-05-30) plus the items that can
only be set in the Play Console (not in source).

---

## In-app — already compliant ✅

| Requirement | Where in source |
|---|---|
| Privacy Policy accessible on first launch | `WelcomeStep.kt` "Read Privacy Policy" button + `PrivacyPolicyDialog.kt` reading `R.raw.privacy_policy` |
| Privacy policy lists every permission and use | `app/src/main/res/raw/privacy_policy.txt` |
| Per-permission in-context disclosure shown BEFORE system dialog | `WhatToCollectStep.kt::PermissionExplanation` for every sensitive permission |
| Background location requested ONLY after foreground location is granted | `WhatToCollectStep.kt:178` — guarded by `state.locationPermissionGranted` |
| Background location rationale uses the required "in the background" + "Allow all the time" language | `R.string.setup_perm_location_bg_why` (this commit) |
| Foreground service type declared (`location`, `specialUse` with subtype) | `tracker/src/main/AndroidManifest.xml:33-43` |
| Foreground notification clearly indicates location is in use | `R.string.notification_tracker_active_ticker` "Recording your route — location in use" (this commit) |
| Foreground notification has a Stop action | `TrackerNotificationManager.kt:53-58` |
| Onboarding can be completed without granting any sensitive permission | Every data source in step 3 has a toggle; CTA enabled regardless |
| No forced sign-in / account | Repo has no auth system |
| Each data source's rationale mentions "stays on this device" | All `setup_perm_*_why` strings (this commit) |
| `POST_NOTIFICATIONS` requested only for tracking status (not marketing) | `R.string.setup_perm_notification_why` (this commit) clarifies "No marketing or promotional notifications" |
| `INTERNET` permission removed from final manifest (offline-only app) | `app/src/main/AndroidManifest.xml:30` `tools:node="remove"` |
| `allowBackup="false"` to avoid leaking user data to cloud backup | `app/src/main/AndroidManifest.xml:34` |
| Hardware BACK navigates onboarding back through steps | `SetupRoute.kt::BackHandler` |
| Target Android 14+ FGS rules satisfied (specialUse + subtype declared on ActivityWatcherService) | `tracker/src/main/AndroidManifest.xml:42` |

---

## Play Console — must be set externally ⚠️

These are NOT in source code; the developer must complete them when
uploading to Play Console.

### Data Safety form

Tracker is offline-only — no data leaves the device. The Data Safety form
should reflect this:

- **Does your app collect or share any of the required user data types?**
  → **NO**. Per Google's definition, "collected" means "transmitted off
  the device". This app reads location/activity/Wi-Fi/cell radios but
  never sends them anywhere.
- If Google's auto-detection insists otherwise (because it spots `ACCESS_FINE_LOCATION` etc. in the manifest), add a Data Type → mark each as **not shared**, **processed ephemerally**, **on-device only**.
- Link the **privacy policy URL** that matches the bundled
  `privacy_policy.txt`. Hosting it on GitHub Pages or the project README
  is acceptable.

### Foreground services declaration

For `targetSdk` ≥ 34 (Android 14):
- Declare `FOREGROUND_SERVICE_LOCATION` use case: **"fitness tracking"** or **"location sharing"** is most accurate.
- Declare `FOREGROUND_SERVICE_SPECIAL_USE` use case (ActivityWatcherService): justify with **"activity transition detection for battery-efficient auto-tracking"** in the console form (note: special use is restricted; the privacy review may push back).

### Sensitive permissions justification

- `ACCESS_BACKGROUND_LOCATION` — requires a separate Play Console review. Justify with:
  > Tracker records the user's route as a foreground service while the device is locked or another app is in use. Recording cannot be paused while the screen is off, so background location access is required. Data stays on the device — no upload, no analytics, no third parties.
- `READ_PHONE_STATE` — Justify with:
  > Used solely to log cell tower IDs along the user's route when they explicitly opt in to "Cell Towers" tracking. No identifiers (IMEI, phone number) are read or transmitted.
- `ACTIVITY_RECOGNITION` — Justify with:
  > Used to detect movement type (walking/cycling/driving/still) so auto-tracking can pause when the user is stationary, reducing battery use. No data transmitted.

### Target SDK requirement

- Play requires `targetSdk` at the previous-year API level minimum. Check `app/build.gradle.kts` / `libs.versions.toml` is at `targetSdk = 35` or later before submission.

### Closed testing → production track

- Play now requires a 14-day closed testing period with at least 12 testers before promoting to production for new accounts. Existing developer accounts may be grandfathered.

### App content declarations

- **Ads**: declare "No ads" — verify no ad SDKs in `libs.versions.toml`.
- **Health Connect**: this app does NOT integrate Health Connect; leave that section blank.
- **Government apps**: N/A.
- **Financial services**: N/A.
- **News**: N/A.

### Content rating

- IARC questionnaire should score "Everyone" since the app collects no
  personal info, has no ads, no UGC, no chat, no in-app purchases.

### Localizations + store listing

- Privacy Policy URL: required (separate from in-app dialog).
- Short description + full description: avoid claims that aren't backed
  by the app (e.g. don't say "AI-powered" unless ML actually runs).
- App icon: 512×512 PNG, no transparency.
- Feature graphic: 1024×500 PNG required.
- Screenshots: 2-8 per form factor.

### Pre-launch reports

- Review the automated pre-launch crawler reports after the first
  internal-test upload. Common findings: ANR on first-launch permission
  dialog (handle declined permission gracefully — already done via the
  toggle pattern), accessibility warnings (most already addressed via
  `semantics` blocks).

---

## Items I intentionally left unchanged

- **Permission order** stays "Foreground → Background" with the
  background request only appearing after the user grants foreground
  location and chooses an auto-tracking mode that needs it. Google's
  policy explicitly requires this ordering — do not consolidate.
- **Privacy Policy as in-app dialog** (vs external WebView): Tracker is
  offline-only, so loading a remote policy would break that promise.
  The bundled raw text is preferable.
- **No analytics SDK**: by design. If telemetry is ever added later,
  the Data Safety form and Privacy Policy will both need updating.

---

## What this commit changed

- Strengthened 6 permission rationale strings to add the on-device
  guarantee and Google's required "in the background" / "Allow all the
  time" framing for background location.
- Updated the foreground service notification text from "Tracker is
  active" → "Recording your route — location in use" to satisfy
  Android 14+ FGS_LOCATION disclosure expectations.
- Replaced stale "Complete challenges" benefit copy on the Welcome
  step (challenges no longer exist post-Phase 13) with an accurate
  "Unlock 150+ achievements" description.

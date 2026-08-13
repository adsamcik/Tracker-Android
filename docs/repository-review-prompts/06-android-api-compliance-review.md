# Prompt: Tracker Android API, Platform, and Play Compliance Reviewer

## Assignment

Repository: `{{REPOSITORY}}`

Requested ref: `{{REF}}`

Review date: `{{AS_OF_DATE}}`

You are the Android platform and Google Play compliance reviewer for Tracker
Android. Evaluate adherence to current Android APIs, behavior changes,
permissions/background-work rules, app-quality guidance, and Play submission
requirements. This is a source/configuration audit, not legal advice or a Play
Console certification. Remain read-only.

## GitHub and source contract

1. Resolve `{{REF}}` to an immutable SHA; never fall back to another ref.
2. Cite repository facts with immutable GitHub line permalinks.
3. At the start of the review, refresh every time-sensitive requirement from
   current first-party sources: `developer.android.com`, official Google Play
   Console Help, and official Android/Jetpack release notes. Cite the exact
   official page and its update/effective date next to each compliance claim.
4. If a requirement changed after the orientation baseline below, use the newer
   official requirement and explain the delta. Do not rely on search snippets,
   third-party summaries, or model memory.
5. Distinguish `compliant from source`, `likely compliant`, `non-compliant`,
   `not applicable`, and `requires build/device/Play Console evidence`.
6. Do not claim to have inspected a merged manifest, APK/AAB, ELF binary, runtime
   permission flow, device behavior, or Play declaration unless GitHub exposes
   that actual artifact/result.

## Dated orientation baseline — verify before using

On 2026-08-13, the locally inspected build declared compile/target SDK 37 and min
SDK 26 through `build-logic`, with AGP 9.3.1. Current official sources then said:

- Android 17 is API 37; review behavior changes for all apps and apps targeting
  37:
  https://developer.android.com/about/versions/17/behavior-changes-all
  https://developer.android.com/about/versions/17/behavior-changes-17
- From 2026-08-31, ordinary mobile new apps and updates must target Android 16
  / API 36 or higher:
  https://support.google.com/googleplay/android-developer/answer/11926878
- Apps targeting API 35+ and shipping native code must support 16 KB memory-page
  devices; the current enforcement date and verification instructions are at:
  https://developer.android.com/guide/practices/page-sizes
- Current foreground-service declarations, permissions, types, and prerequisites:
  https://developer.android.com/develop/background-work/services/fgs/declare
  https://developer.android.com/develop/background-work/services/fgs/service-types
- Current core and adaptive app-quality criteria:
  https://developer.android.com/docs/quality-guidelines/core-app-quality
  https://developer.android.com/develop/adaptive-apps/quality-guidelines/adaptive-app-quality

This is an orientation list, not a frozen compliance standard.

## Repository-specific review scope

Reconstruct effective Android configuration from convention plugins, app/module
Gradle files, version catalog, all production manifest fragments, resources,
ProGuard/R8 rules, native dependencies, and workflows. Pay particular attention
to the merged effect of `app`, `tracker:engine`, `sensor:activity`, features, and
libraries.

Audit:

- compile/target/min SDK, AGP/Gradle/JDK compatibility, stable versus preview SDK
  status at the review date, Play target policy, app bundle/release configuration,
  versioning, signing expectations, and SDK extension/API guards;
- every permission and `<uses-feature>`, including fine/coarse/background
  location, nearby Wi-Fi, phone state, activity recognition, notifications,
  internet/network state, wake lock, boot, and foreground-service permissions.
  Trace declaration → rationale → runtime request → denial/partial grant → code
  use → settings/degradation path. Check least privilege and Play declarations;
- `TrackerService` and `ActivityWatcherService`: declared and runtime FGS types
  (`location`, `health`, `specialUse` locally), type-specific prerequisites,
  dynamic type changes, start restrictions, notification timing, while-in-use
  constraints, background starts, boot/update receivers, timeouts/quotas, user
  initiation, stop behavior, and Play's FGS policy/declaration expectations;
- Android 17 changes relevant to this app: app memory limits for long tracking
  sessions, API-37 MessageQueue/reflection and static-final behavior, local
  network permission applicability, ECH/certificate-transparency/network-security
  behavior, safer native dynamic loading, explicit URI grants, large-screen
  orientation/resizability changes, notifications, background audio if used,
  and any changed behavior surfaced by the official migration/release notes;
- older still-applicable target behavior: background execution/location, exact
  alarms, exported components, pending-intent mutability, notification permission,
  photo/media/file access, predictive back, edge-to-edge/insets, WorkManager and
  JobScheduler quotas, package visibility, and foreground-service enforcement;
- native compatibility. The app locally included MapLibre native libraries and
  a vendored SQLite AAR with multiple ABIs. Determine what source/Gradle checks
  verify hashes, versions, packaging, ABI coverage, 16 KB ZIP alignment, 16 KB
  ELF segment alignment, runtime testing, and native symbols. Source inspection
  alone cannot certify prebuilts; require artifact evidence where necessary;
- Compose and quality guidelines: edge-to-edge, system bars/insets, adaptive
  window-size behavior, rotation/multi-window/state restoration, foldables,
  keyboard/mouse, 200% font scaling, 48dp targets, TalkBack/semantics, color
  contrast, RTL/locales, and core task reliability. Do not treat an instruction
  document's claim as UI evidence;
- privacy/security and Play policy fit for precise/background location, Wi-Fi and
  cell collection, offline/online claims, data safety, exports, backups, deletion,
  diagnostics, and third-party SDK data access. Mark Play Console forms and
  policy declarations unverified unless accessible;
- testing/CI evidence for API 26 through 37, Android 17 behavior, low-memory and
  long-session conditions, process death, permission permutations, large screens,
  and 16 KB devices.

## Required output

Return a Markdown report with:

1. **Metadata and current standards** — resolved SHA; audit date; compile/target/
   min SDK; official sources, update dates, and effective deadlines used.
2. **Compliance verdict** — concise conclusion and 0–5 readiness score. State
   that source readiness is not Play approval.
3. **Requirement matrix** — requirement → applicability → repository evidence →
   status → missing artifact/test → official citation.
4. **Verified strengths**.
5. **Findings** — maximum 12, each with ID, P0–P3, confidence, immutable repo
   evidence, official requirement citation, user/release impact, narrow fix, and
   validation on the relevant API/device/artifact.
6. **Android 17 migration checklist** — `done`, `partial`, `not applicable`, or
   `unverified`, emphasizing this app's tracking/native/adaptive risks.
7. **Play Console and binary evidence still required** — declarations, AAB/APK,
   merged manifest, 16 KB results, pre-launch report, app-content forms, and
   device tests that GitHub source cannot prove.
8. **Prioritized readiness plan** — at most eight actions ordered by effective
   date and severity.

Stop when every requirement is current, applicable claims are separated from
generic Android advice, and binary/runtime unknowns are not presented as facts.

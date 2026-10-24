# Dev Location verification — 2026-10-24

## Scope

Install the latest Dev build and exercise My location, manual Start/End, saved route data and
sessionless all-day Location on the existing `Android_17_Google_Play` AVD (`emulator-5554`).
Android reports API 37, Android 17 REL, x86_64; the existing image is Google Play 37.2/16 KB.
The restored D: drive and existing emulator data were used. No replacement AVD or API matrix.

The installed app is `com.adsamcik.tracker.dev`, **Advention Dev**, version 400 / `10.0.0-dev`.
Installation uses `adb install -r --no-streaming` to retain its data.
APK SHA-256: `c9ab2f6ee1147f88ddde770eb189c9525ce8d5d28fa8fae00f59257faaab55c3`.

## Repairs found during the walkthrough

- Startup could not construct Activity's all-day availability reader. All three Activity purposes
  now share the same non-activating capability reader; startup retry failures log their stage/code.
- An all-day Location replacement could publish an old authorization time after a newer revision,
  poisoning saved-observation replay. Publication uses its actual transaction boundary. A narrowly
  authenticated repair recovers the demonstrated unused backdated revision without changing points,
  observation identity, session cutoffs or deletion authority.
- Continuous Location consent survives compatible session/quality changes for queued observations.
- Manual Start no longer waits for optional all-day owner repair before Android accepts the start.
  Existing repair runs afterward with cancellation, acceptance checks and a bounded timeout.
- Activity session shutdown recognizes its exact durable provider retirement even while a sibling
  all-day restart waits for End to finish. Actual removal failures and missing barriers still block.
- Location returns its session retirement result before starting an optional replacement. The
  existing host refresh restores background/UI demand after End is handled.
- A timed-out Location drain can settle on retry after the original provider and actor stop and all
  callbacks are accounted for. Recorded gaps, original identity/cutoff and flush failures remain.
- Interrupted cleanup for a never-captured, providerless disabled source no longer requires a
  nonexistent capture binding. Historical capture, attempted starts and identified providers still
  require their original retirement authority.

## Host checks

Fresh focused executions, with zero test failures/errors/skips:

| Batch | Cases | Result |
| --- | ---: | --- |
| Startup/catalog and startup recovery | 36 | Passed |
| Authorization boundaries, Location qualification/handoff and broker | 170 | Passed |
| Accepted manual Start, provider Steps startup and affected Location readers | 55 | Passed |
| Activity runtime, shared Location controller and continuous authority | 49 | Passed |
| Location provider runtime, including shutdown retries | 58 | Passed |
| Disabled-source retirement and existing ownership regressions | 4 | Passed |

Some cases are deliberately repeated between batches; the table is not a unique-test total.
Earlier Detekt findings were repaired, then the 49-case batch passed both Dev assembly and root
Detekt (3,298 source files, zero alternate checkouts). The provider-runtime batch also passed Detekt
after a missing test assertion import was repaired. The final retirement batch passed Dev assembly,
all four selected Room cases and Detekt in 18m 55s. Full `ciUnitTest`/`ciCheck` were not rerun
for this targeted runtime repair; historical gate receipts are separate evidence.

Commands use the repository wrapper, existing JDK 21, one worker, offline pinned Tracebox Maven
publication and the existing reliability init script. No dependency stand-ins, quality-rule changes,
baseline changes or cache deletion.

## Final emulator observations

The final APK above was installed without clearing data. The final walkthrough ran without a
debugger or concurrent Gradle checks. Fresh UI hierarchies and screenshots were inspected.

| Action | Observed result | Evidence in the raw evidence directory |
| --- | --- | --- |
| My location, with tracking turned off | Map centered and zoomed to the fresh synthetic Prague position and displayed its marker. Tracking WAL remained at 2,222 Location events with the same last ordinal. | `acceptance-my-location.png`, `acceptance-map-isolation.json` |
| Manual Start | Start command 165 was accepted, cleared the saved Off state, and opened Recording through the shared flow. | `acceptance-after-route-snapshot` |
| Saved route | 40 Location points belong to the new session. Recording displayed its route, distance, speed and accuracy; the final Dashboard showed 255.9 m and 7 m 44 s. | `acceptance-saved-route.png`, `acceptance-ended.png` |
| End | Stop command 166 was handled. Logical session and run are `FINALIZED`, runtime acknowledgement is `STOP_ACCEPTED`, presentation is `QUIESCED`, and Start is available again. No session point follows the original End cutoff. | `acceptance-background-complete-receipt.json`, `acceptance-ended.xml` |
| All-day Location after End | 23 points after the cutoff have only sessionless `AMBIENT_PRODUCT` membership; 18 are more than 60 seconds past it. A separate background route produced 11 saved points with changing coordinates while Android's launcher was foreground. | `acceptance-background-complete-receipt.json`, `acceptance-background-movement.json`, `acceptance-sessionless-route-fixes.json` |
| History preservation | All 430 pre-existing effective Location rows retain their original IDs and values. SQLite `quick_check` is `ok`, schema remains 30. | `before-tests-snapshot`, `acceptance-background-complete-receipt.json` |

Final logical session: `0b0a9d43-a6e1-4ce8-8543-279c91d65696`; service run:
`b38756b7-1f92-4e09-aeba-e361dee4cb4b`; original End cutoff: `1792841833846` UTC ms.
Every background point above has null logical-session, service-run and session-segment fields.
Native Android Location registration remained active for the Dev package in the background.

Location retirement is `ACKNOWLEDGED`/`COMPLETE`, with provider removal, completed flush,
app drain complete and no failed admissions or unresolved ranges. The overall run retains
`STOP_PARTIAL`: Activity cannot establish provider flush completeness, and an earlier Activity
generation has a fallback interruption receipt. That receipt's `PROCESS_RESTARTED` label does not
prove an actual process restart. Pressure also has recorded admission failures. This walkthrough
therefore does not establish lossless collection or complete coverage for all six sources.

Shutdown was slow: the stored presentation acknowledgement is about 5 m 24 s after End, and
the first later background observations resume about 5 m 39 s after End. The emulator established
eventual completion and the original cutoff, not acceptable shutdown latency. Physical retirement
receipts were saved within about 41 s; an intermediate snapshot still had a pending Location
projection prefix more than three minutes later. The same End intent completed without another
tap. This identifies canonical catch-up as part of the delay; exact database, scheduling and
host/emulator costs are not separated by this run.

The earlier missing-segment Location projection failure recovered through the existing replay
path and saved its original observation. Intermediate APK failures, diagnostic interruptions and
the earlier force-stop remain in the raw evidence; they are not counted as successful final End
or all-day verification. The final session was ended through its UI, without force-stop/reinstall.

An independent review-only subagent inspected the actual final snapshots and the My location,
Recording and ended Dashboard screenshots. It found no blocker to these Location workflows.

Raw logs, test XML, screenshots, fresh UI hierarchies, synthetic fix times and stable SQLite/WAL
snapshots are retained in the ignored `build/emulator-verification-20261024` directory.

## Limits

Synthetic emulator fixes establish the exercised UI/provider/storage/lifecycle behavior. They do
not establish physical GNSS quality, OEM background restrictions, long Doze/reboot recovery or
battery consumption. No physical-device or release-readiness claim is made here.

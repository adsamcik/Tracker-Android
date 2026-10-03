# Saved Location export choice — selected source

2026-10-03. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.**
Base: `31d5340b0cb390294f4170ab2a6b7564e2292a12`.
All 25 full-chain prerequisites remain open; no assembly freeze or integration.

## Approved behavior and selected implementation

The user approved one chosen-format export and a Location backup containing all
saved points in the chosen dates, including honest unknown historical origins.
The saved-data exporter core is committed at `20c9ac0e64`.

This slice exposes that existing format in the ordinary export picker:

- Reuse the already-localized Location source name and existing storage icon.
- One two-string resource set in import/export supplies the concise description
  and Location-specific confirmation to the picker and actual Save/Share action.
  It covers all 28 existing app locale folders, with no new locale added.
- Keep the existing registry, calendar expansion, transfer admission, private
  staging, destination selection and reflected exporter construction.
- Protect `LocationFileExporter` with AndroidX `@Keep`. The consumed annotation
  rules protect the class and public no-argument constructor; no new factory or
  redundant keep rule is introduced.
- Share one chosen `.trackerlocation` file with its MIME type and read grant.
  The Location branch does not resolve a coincident trip summary or attach trip
  distance/duration text. Existing ordinary trip sharing is unchanged.

The saved-data reader creates no Location demand. It reports its own empty
selection rather than using the legacy route preflight.

## Exact selection and preserved drafts

`LOCATION_EXPORT_UI_FILES.json` records 34 source paths and SHA-256 pins.
`LOCATION_EXPORT_UI_SELECTION.patch` and `LOCATION_EXPORT_SHARE_SELECTION.patch`
select only the Location enum and Location Share branch from mixed working files.
Their before/after snapshots are in `location-export-ui-selection/`.

The mixed Wi-Fi/Cell enum, session-expansion switch and Share branches remain held.
Complete radio exporters, Location Replace, session bundle callers, unfinished
external-file recovery and unrelated drafts are excluded. No protected root file
or main `dev/v10` path is selected by this slice.

## Authored evidence and limits

Three new scenarios are authored, uncompiled and unrun:

1. Pick Location, confirm it, resolve its actual registry entry, preserve the
   public no-argument reconstruction contract and use the same confirmation copy.
2. Cancel confirmation without selecting another format; keep Location reachable.
3. Share the one Location file with the correct MIME/read grant and no trip text.

Independent static review: `LOCATION_EXPORT_UI_FRESH_REVIEW_1003.md`.
This is not release-R8, rendered UI, SAF, physical-device or runtime evidence.
No Gradle, compiler, test, lint, schema, diff-check, device, CI or integration gate ran.

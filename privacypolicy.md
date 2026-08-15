# Privacy Policy

**Effective date:** August 15, 2026

Tracker Android is developed by Adsamcik and provided as an open-source, privacy-first app.

## Overview

Tracker Android is offline-first and can record and analyse tracking data without a network
connection. The app does not use a Tracker backend, sync tracking data to remote servers, or use
Firebase, analytics services, advertising SDKs, tracking pixels, remote telemetry, or remote crash
reporting. If you opt in to online map tiles, the app makes only the limited network requests
described below.

## Information Collection and Use

Tracker Android does not send your tracking database, recorded routes, trip history, or local
diagnostics to the developer or to an analytics service. Optional online map requests can reveal
the area of the map you are viewing to the selected tile provider, as described under "Optional
Online Map Tiles."

Data created by the app stays on your device. This can include:

* location history, routes, and trip/session records
* activity recognition and step-related data
* app settings and local app data
* local Tracebox crash, ANR, process-exit, and fixed structural diagnostics

## Local Crash Diagnostics

Tracebox records bounded crash, ANR, process-exit, and structural troubleshooting information in
Tracker's private app storage. Tracker does not put precise coordinates, route history, network
identifiers, URIs, filenames, user text, database contents, stable user/device identifiers, or
exception messages into its diagnostic log calls. Runtime strings and unknown objects are private
and redacted by default.

Tracebox has no automatic upload client. A diagnostic package can leave the device only after you
open diagnostics, review its disclosure, approve it, and choose an Android save or share
destination. Approved package bytes are short-lived and are retired after save/share, replacement,
policy change, deletion, or diagnostics-screen disposal.

## Network Use

Tracker Android can operate fully offline. The app does not make network calls for tracking,
analytics, crash reporting, diagnostics, or remote storage. Online map tiles are enabled only after
you complete setup with that choice selected. You can choose the bundled offline basemap during
setup or turn online map tiles off at any time.

## Permissions

### Location

Used for recording routes, showing your position on the map, and generating location-based
statistics.

### Activity Recognition

Used for activity detection, step counting, and related on-device insights.

### Notifications

Used to display foreground service and tracking status notifications while tracking is active.

### Files and Storage Access

Used only when you choose to import or export data through Android's system file picker.

## Map Data (OpenStreetMap)

Tracker Android does not import or store `.osm.pbf` region files. Map data shown by the bundled
basemap or optional online tile providers may incorporate OpenStreetMap data.

OpenStreetMap data is © OpenStreetMap contributors and licensed under the Open Database License
(ODbL) 1.0. See https://www.openstreetmap.org/copyright for details.

## Optional Online Map Tiles

If you enable online map tiles, Tracker requests map styles, tiles, fonts, and related map assets
from the provider you select, such as OpenFreeMap or Protomaps, or from a custom provider URL you
enter. The provider necessarily receives your IP address and ordinary request metadata such as the
request time and user agent. Tile coordinates identify the approximate map areas being viewed,
which may reveal or suggest your location when the map is centred on you or your route.

Tracker does not attach your tracking database, recorded routes, advertising identifiers, an app
account, or a Tracker-specific installation identifier to built-in tile requests. A custom provider
URL may contain credentials or other parameters that you supply. The selected provider may process
or retain requests under its own privacy policy and terms; Tracker does not control that provider.

## Exports

Exports are created only when you explicitly request them. Tracker Android can export your data in
formats such as GPX, KML, JSON, and SQLite. Exported data goes only to the file location or app you
choose. Exports can contain precise location history and are not automatically encrypted by
Tracker, so their privacy depends on the destination and how you protect or share the file.

An approved Tracebox diagnostic package follows the same destination rule, but is separate from
tracking-data exports and is designed to contain diagnostic structure rather than your tracking
database or route history.

## Backup and Device Transfer

Android cloud backup and device-to-device transfer are disabled for all Tracker app storage.
Tracker and Tracebox databases, files, preferences, and staging areas are excluded from platform
backup and transfer. Only an export, diagnostic save, or share action you explicitly choose creates
a copy outside the app.

## Deletion

You can delete Tracebox data from the diagnostics screen. Tracker's app-wide collected-data
deletion also includes all Tracebox records and staged packages. If Tracebox's private handler
process cannot finish immediately, Tracker keeps a local deletion marker and retries at startup
rather than reporting the transaction complete early. Copies you previously exported, saved, or
shared are outside Tracker's control and must be deleted at their destination.

## No Ads or Tracking

Tracker Android does not show ads and does not include ad networks, tracking pixels, analytics,
remote telemetry, or remote crash reporting.

## Data Security

Tracking and diagnostic data remain in private app storage unless you explicitly export, save, or
share a copy. Optional online tile requests disclose viewed map areas but do not upload the
underlying tracking database. The privacy of an exported copy depends on where you save or send it.

## Children's Privacy

The developer does not knowingly receive personal information from children through Tracker.
Tracker has no account, backend, analytics, or automatic diagnostic upload; data processed locally
remains under the device owner's controls and explicit export choices.

## Changes to This Privacy Policy

This Privacy Policy may be updated from time to time. Any changes will be reflected in the version
included with the app and in the repository.

## Contact Us

If you have any questions about this Privacy Policy, please contact Adsamcik at
play@adsamcik.com.

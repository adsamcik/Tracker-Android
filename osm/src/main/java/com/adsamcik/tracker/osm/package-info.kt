/**
 * Vehicle Speed Compliance — Phase 2a:
 * Per-road actual maxspeeds parsed from a user-imported OpenStreetMap PBF file.
 *
 * Strict offline contract:
 *  - The PBF file is selected by the user via the Storage Access Framework.
 *  - The app never downloads OSM data and never persists the SAF URI permission
 *    beyond the duration of [com.adsamcik.tracker.osm.import.OsmImportWorker].
 *
 * License: OpenStreetMap data is © OSM contributors, licensed under the
 * Open Database License (ODbL 1.0). Once an [com.adsamcik.tracker.shared.base.database.data.OsmImportEntity]
 * row exists in the database, the app MUST display attribution
 * ("Map data © OpenStreetMap contributors (ODbL 1.0)") in the Settings/About
 * surface that exposes the imported region.
 */
package com.adsamcik.tracker.osm

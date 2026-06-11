package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.graphics.GridAggregator
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Tile-based data source that generates GeoJSON for individual Z/X/Y tiles.
 *
 * Instead of loading all 80K points into a single 6.4MB GeoJSON string,
 * this queries the database for only the tile's bounding box and applies
 * zoom-appropriate aggregation. Typical per-tile payloads are 10-100x smaller.
 *
 * ## Integration (Approach A — visible tile merge)
 *
 * Heatmap layers call [getVisibleTilesGeoJson] with the current viewport and zoom.
 * This method:
 * 1. Computes which tiles cover the viewport
 * 2. Generates (or cache-hits) GeoJSON for each tile
 * 3. Merges the tile GeoJSON into a single FeatureCollection
 *
 * The merged result replaces the old "load all → aggregate → serialize" pipeline,
 * reducing peak memory from O(all points) to O(visible points).
 */
class TiledDataSource(
    private val repo: GeoRepository,
    private val cache: TileCache,
    private val dispatchers: DispatchersProvider,
) {

    companion object {
        /**
         * Maximum tile zoom level for DB-per-tile queries.
         * Above this, tiles are small enough that a single viewport query suffices.
         */
        private const val MAX_TILE_ZOOM = 18

        /**
         * Minimum tile zoom for tiling. Below this, the world is small enough
         * that a single query is fine.
         */
        private const val MIN_TILE_ZOOM = 3

        /**
         * Maximum tiles to request in a single [getVisibleTilesGeoJson] call.
         * If more tiles are needed, the effective zoom is reduced until the
         * count fits. Prevents runaway DB queries at high zoom with wide viewport.
         */
        private const val MAX_TILES_PER_REQUEST = 64

        /**
         * Per-tile point limit for [getTile] single-tile queries.
         * Prevents a low-zoom tile from returning the entire database.
         */
        private const val MAX_POINTS_PER_TILE = 10_000
    }

    /**
     * Get GeoJSON for a single tile. Checks cache first, then queries the database.
     *
     * @param layerId Unique layer identifier for cache namespacing.
     * @param z Zoom level.
     * @param x Tile X coordinate.
     * @param y Tile Y coordinate.
     * @param source The geo data source (LOCATION, WIFI, CELL).
     * @param weightColumn Weight column for the query (e.g., "hor_acc", "speed", "asu").
     * @param timeFrom Optional start of time range filter.
     * @param timeTo Optional end of time range filter.
     * @return GeoJSON FeatureCollection string for this tile.
     */
    suspend fun getTile(
        layerId: String,
        z: Int,
        x: Int,
        y: Int,
        source: GeoSource,
        weightColumn: String,
        timeFrom: Long? = null,
        timeTo: Long? = null,
    ): String {
        val cacheKey = cache.key(layerId, z, x, y)
        cache.get(cacheKey)?.let { return it }

        val geoJson = withContext(dispatchers.io) {
            val tileBounds = TileMath.tileToBounds(z, x, y)

            val query = GeoQuery(
                source = source,
                bounds = tileBounds,
                weight = weightColumn,
                timeFrom = timeFrom,
                timeTo = timeTo,
                limit = MAX_POINTS_PER_TILE,
            )
            val points = repo.queryWeighted(query, weightColumn).first()

            val processed = applyZoomAggregation(points, z)
            TileGeoJsonGenerator.generatePointTile(processed, tileBounds)
        }

        cache.put(cacheKey, geoJson)
        return geoJson
    }

    /**
     * Get merged GeoJSON for all tiles visible in the current viewport.
     *
     * This is the primary integration method for **Approach A** (visible tile merge).
     * Instead of loading the entire dataset, it:
     * 1. Determines which tiles cover the viewport at the given zoom
     * 2. Generates per-tile GeoJSON (cached where possible)
     * 3. Merges all tile features into a single FeatureCollection
     *
     * @param viewportBounds Current map viewport bounds.
     * @param zoom Current map zoom level.
     * @param source Data source (LOCATION, WIFI, CELL).
     * @param weightColumn Weight column name.
     * @param layerId Layer identifier for caching.
     * @param timeFrom Optional time range start.
     * @param timeTo Optional time range end.
     * @param maxPoints Maximum total points across all tiles. Excess tiles are skipped.
     * @return Merged GeoJSON FeatureCollection string.
     */
    suspend fun getVisibleTilesGeoJson(
        viewportBounds: Bounds,
        zoom: Float,
        source: GeoSource,
        weightColumn: String,
        layerId: String,
        timeFrom: Long? = null,
        timeTo: Long? = null,
        maxPoints: Int = 40_000,
    ): String {
        var tileZoom = zoom.toInt().coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
        var tiles = TileMath.tilesForBounds(viewportBounds, tileZoom)

        // Cap tile count to avoid flooding the DB with hundreds of queries.
        // If too many tiles, back off the zoom level until manageable.
        while (tiles.size > MAX_TILES_PER_REQUEST && tileZoom > MIN_TILE_ZOOM) {
            tileZoom--
            tiles = TileMath.tilesForBounds(viewportBounds, tileZoom)
        }

        val allFeatures = mutableListOf<WeightedGeoFeature>()
        val pointBudget = maxPoints / tiles.size.coerceAtLeast(1)

        for ((z, x, y) in tiles) {
            if (allFeatures.size >= maxPoints) break

            val tileBounds = TileMath.tileToBounds(z, x, y)
            val tilePoints = withContext(dispatchers.io) {
                val query = GeoQuery(
                    source = source,
                    bounds = tileBounds,
                    weight = weightColumn,
                    timeFrom = timeFrom,
                    timeTo = timeTo,
                    limit = pointBudget,
                )
                val points = repo.queryWeighted(query, weightColumn).first()
                applyZoomAggregation(points, z)
            }

            allFeatures.addAll(tilePoints)
            // Cache the tile GeoJSON for future single-tile requests
            val tileJson = TileGeoJsonGenerator.generatePointTile(tilePoints, tileBounds)
            cache.put(cache.key(layerId, z, x, y), tileJson)
        }

        // Final aggregation pass if we exceeded budget
        val result = if (allFeatures.size > maxPoints) {
            val step = (allFeatures.size / maxPoints).coerceAtLeast(1)
            allFeatures.filterIndexed { index, _ -> index % step == 0 }
        } else {
            allFeatures
        }

        return GeoJsonConverter.pointsToFeatureCollection(result)
    }

    /** Invalidate all cached tiles for a layer (e.g., when new data arrives). */
    fun invalidate(layerId: String) = cache.invalidateLayer(layerId)

    /** Invalidate everything (e.g., date range change). */
    fun invalidateAll() = cache.clear()

    /**
     * Apply zoom-appropriate aggregation using [GridAggregator].
     * Reuses the same cell sizes as the existing heatmap layers.
     */
    private fun applyZoomAggregation(
        points: List<WeightedGeoFeature>,
        zoom: Int,
    ): List<WeightedGeoFeature> {
        val cellSize = GridAggregator.cellSizeForZoom(zoom.toFloat())
        return if (cellSize > 0.0 && points.size > 100) {
            val cells = GridAggregator.aggregate(points, cellSize)
            GridAggregator.toWeightedFeatures(cells)
        } else {
            points
        }
    }
}

# Heatmap bugs analysis (map-rework)

This document lists confirmed issues found in the current heatmap stack. Each item includes the impact, the code location, a short root-cause analysis, and a minimal fix suggestion with code context.

## 1) Border seams due to coordinate rounding in `HeatmapTile.add`

- Impact: Visible seams/misaligned intensity along tile borders; the same event near a border can land on different pixels in neighboring tiles.
- Where: `map/src/main/java/com/adsamcik/tracker/map/heatmap/HeatmapTile.kt`
- Why: Pixel coordinates are computed using `roundToInt()` from continuous tile coordinates. Rounding differs across tiles and causes off-by-one shifts. Seam-safe rasterization should use floor with a tiny epsilon at integer boundaries.
- Evidence:

  ```kotlin
  // HeatmapTile.add
  val tx = MapFunctions.toTileX(location.longitude, tileCount)
  val ty = MapFunctions.toTileY(location.latitude, tileCount)
  val x = ((tx - data.x) * data.heatmapSize).roundToInt() + data.pad
  val y = ((ty - data.y) * data.heatmapSize).roundToInt() + data.pad
  ```

  Compare with the test strategy in `HeatTileEngineSeamTest` that verifies identical shared edges. Using floor is the standard for consistent binning.
- Fix suggestion:

  ```kotlin
  // Prefer floor with epsilon; avoids double-hit or drop on exact borders
  val fx = (tx - data.x) * data.heatmapSize
  val fy = (ty - data.y) * data.heatmapSize
  val eps = 1e-6 // heat pixels
  val x = kotlin.math.floor(fx - eps).toInt() + data.pad
  val y = kotlin.math.floor(fy - eps).toInt() + data.pad
  ```


## 2) Neighborhood saturation override is clamped back to local percentile band

- Impact: Tone/normalization flickers across tiles; cross-tile “global” normalization is weakened, producing visible steps when panning.
- Where: `HeatmapTile.toByteArray`
- Why: Even when `saturationOverride` is provided (computed from a 3×3 neighborhood and smoothed over time), it gets coerced into `[0.75×localP, 1.50×localP]`. This defeats the purpose of a neighborhood-wide normalization target in sparse/dense transitions.
- Evidence:

  ```kotlin
  var saturation = data.saturationOverride ?: localP
  val minSat = localP * 0.75f
  val maxSat = localP * 1.50f
  saturation = saturation.coerceIn(minSat, maxSat)
  ```

- Fix suggestion: Only clamp when override is null (i.e., using localP). If override exists, respect it or use a gentler clamp (e.g., global EMA on override upstream, no per-tile clamp).

  ```kotlin
  var saturation = data.saturationOverride ?: localP
  if (data.saturationOverride == null) {
      val minSat = localP * 0.75f
      val maxSat = localP * 1.50f
      saturation = saturation.coerceIn(minSat, maxSat)
  }
  ```


## 3) Percentile quantization introduces banding and temporal jitter

- Impact: Subtle color/alpha banding and frame-to-frame “popping” as the saturation snaps to 2% bins of `maxHeat`.
- Where: `HeatmapTile.toByteArray`
- Why: Quantizing saturation to `step = max(1f, 0.02 × maxHeat)` is coarse, especially when `maxHeat` is small. With smooth EMA already applied to neighborhood saturation, this extra quantization adds artifacts.
- Evidence:

  ```kotlin
  if (data.config.maxHeat > 0f) {
      val step = (data.config.maxHeat * 0.02f).coerceAtLeast(1f) // 2% bins
      saturation = kotlin.math.ceil(saturation / step) * step
  }
  ```

- Fix suggestion: Remove hard quantization or reduce to ~0.5–1% and apply only when override is null. Prefer time-smoothed override.

## 4) Cell heatmap deposits ignore stamp falloff, creating hard-edged discs

- Impact: Blocky/plateau look and stronger tile seams (flat intensity within the stamp extent).
- Where: `layers/impl/CellHeatmapLayer.kt` – heat config
- Why: The weight merge uses `max(current, value)` and ignores the per-pixel stamp value, so every pixel under the stamp gets the same weight regardless of distance.
- Evidence:

  ```kotlin
  weightMergeFunction = { current: Float, _: Int, _: Float, value: Float ->
      max(value, current)
  }
  ```

- Fix suggestion: Incorporate `stampValue` so falloff is respected. For max aggregation, take max over `stampValue * value`.

  ```kotlin
  weightMergeFunction = { current, _: Int, stampValue, value ->
      max(current, stampValue * value)
  }
  ```


## 5) Blur toggles sharply based on tile coverage threshold

- Impact: Visual “mode switch” between neighboring tiles; one tile looks smooth, the next looks crisp, causing a checkerboard feel in sparse data.
- Where: `HeatmapTile.toByteArray`
- Why: Blur is applied only when `coverage < 0.25f`, with a hard cutoff. Tiles around 25% toggle smoothing on/off abruptly.
- Evidence:

  ```kotlin
  val coverage = preCoverage
  val blurred = if (coverage < 0.25f) gaussianBlur(normalized, paddedSize, paddedSize, radius = 2) else normalized
  ```

- Fix suggestion: Blend blur radius or strength smoothly with coverage (e.g., radius = lerp(0..2) from 0.15..0.35 coverage), not a hard switch.

## 6) Coordinate-time baseline makes decay depend on tile content span

- Impact: Tiles with different time spans render at different apparent intensities for identical events; subtle flicker when panning across mixed-history tiles.
- Where: `HeatmapTile.add`
- Why: `ageInSeconds` is computed relative to the minimum event time in the tile. Two neighboring tiles with different min-times will decay contributions differently for the same absolute timestamps.
- Evidence:

  ```kotlin
  val sortedList = list.sortedBy { it.time }
  val minTime = sortedList.first().time
  // ...
  val ageInSeconds = ((location.time - minTime) / Time.SECOND_IN_MILLISECONDS).toInt()
  ```

- Fix suggestion: Use a common reference (e.g., median tile time or a viewport frame time) for age, or pass in a frame-time from the layer and compute `age = frameTime - sampleTime`.

## 7) Density gain makes deposition order-dependent (can flicker)

- Impact: Small temporal inconsistencies and micro-flicker as the “neighbor-aware density” gain changes based on previously-added samples.
- Where: `AgeWeightedHeatmap.addPoint`
- Why: `densityGain` samples the current `weightArray` around the target pixel before adding this point and modulates the contribution. This makes results depend on insertion order and local sample timing.
- Evidence:

  ```kotlin
  // AgeWeightedHeatmap.addPoint
  val densityRadius = max(2, min(halfStampWidth, halfStampHeight))
  // ... accumulate densitySum from weightArray[]
  var densityGain = if (localMean > 0f) localMean / (localMean + denomBase) else 0f
  densityGain = kotlin.math.sqrt(densityGain)
  val minGain = 0.45f
  densityGain = minGain + (1f - minGain) * densityGain
  // merged = weightMerge(..., stampValue * ... * densityGain, ...)
  ```

- Fix suggestion: Make density gain a post-pass smoothing (e.g., small blur in normalized space, already present) or compute a static density map first, then deposit in a separate pass to remove order dependence.

## 8) Mixed alpha logic: computed but not used in final rendering in several layers

- Impact: Confusing interplay, unexpected opacity; weight EMA depending on `currentAlpha` while final tile alpha comes from normalized intensity when `alphaFromNormalized=true`.
- Where: Layer configs (`LocationHeatmapLayer`, `SpeedHeatmapLayer`, `WifiHeatmapLayer`) and `AgeWeightedHeatmap.renderFromNormalized`.
- Why: `alphaMergeFunction` and `alphaArray` affect `currentAlpha` seen by `weightMergeFunction` (e.g., EMA in Speed), but the final bitmap alpha is derived from normalized value if `alphaFromNormalized=true`. This asymmetry can be surprising and makes opacity tuning non-intuitive.
- Evidence:

  ```kotlin
  // renderFromNormalized
  val alpha = if (alphaFromNormalized) {
      (opacity * n * 255).roundToInt().coerceIn(0, 255)
  } else {
      alphaArray[index].toInt().coerceIn(0, 255)
  }
  ```

- Fix suggestion: Either switch to `alphaFromNormalized=false` where alpha behavior matters, or remove alpha-driven EMA from weight merge and use a simpler weight integrator. Align config with intended visuals.

## 9) Bitmap pooling not used in final compose path

- Impact: Unnecessary allocations and GC churn; can cause stutter/glitches during fast pans/zooms.
- Where: `HeatmapTile.toByteArray`
- Why: The function always creates new bitmaps (`Bitmap.createBitmap`) and only optionally accepts a `BitmapPool` parameter in signature; it never reuses a pooled instance.
- Evidence:

  ```kotlin
  val base = Bitmap.createBitmap(paddedSize, paddedSize, Bitmap.Config.ARGB_8888)
  // ...
  val cropped = Bitmap.createBitmap(base, data.pad, data.pad, data.heatmapSize, data.heatmapSize)
  val scaled = if (data.heatmapSize != bitmapSize) cropped.scale(bitmapSize, bitmapSize, true) else cropped
  ```

- Fix suggestion: Introduce pool usage for base/cropped/scaled bitmaps and recycle via the pool, or render directly into a pooled 256×256 bitmap to avoid intermediate images.

## 10) Neighbor quantile staging uses truncating casts that bias coordinates

- Impact: Slight mismatch of neighborhood sample bins (can bias normalization near borders).
- Where: `LocationHeatmapLayer`, `SpeedHeatmapLayer`, `WifiHeatmapLayer` – neighborAgg block
- Why: Mapping `[x-1, x+2)` tiles to a 0..normSize buffer uses `toInt()` (truncation). This can introduce a half-pixel bias near edges.
- Evidence:

  ```kotlin
  val localX = (((txN - (x - 1)) / 3.0) * normSize).toInt()
  val localY = (((tyN - (y - 1)) / 3.0) * normSize).toInt()
  ```

- Fix suggestion: Use `floor()` and clamp to `[0, normSize-1]` for binning stability.

---

## Appendix: small safe tweaks that improve stability

- Use a smooth blur strength curve based on `coverage` instead of a step at 0.25.
- Reduce cutoff discontinuity by lerping cutoff in a small window around the threshold.
- Prefer median time (or viewport-stable frame time) for `ageInSeconds` baseline to reduce tile-to-tile time-span bias.
- Where you need consistent appearance across zoom levels, consider a constant tile-pixel radius (optional mode already scaffolded in `LocationHeatmapLayer`).

## References to touched files

- `map/src/main/java/com/adsamcik/tracker/map/heatmap/HeatmapTile.kt`
- `map/src/main/java/com/adsamcik/tracker/map/heatmap/implementation/AgeWeightedHeatmap.kt`
- `map/src/main/java/com/adsamcik/tracker/map/layers/impl/LocationHeatmapLayer.kt`
- `map/src/main/java/com/adsamcik/tracker/map/layers/impl/SpeedHeatmapLayer.kt`
- `map/src/main/java/com/adsamcik/tracker/map/layers/impl/WifiHeatmapLayer.kt`
- `map/src/main/java/com/adsamcik/tracker/map/layers/impl/CellHeatmapLayer.kt`
- `map/src/main/java/com/adsamcik/tracker/map/MapFunctions.kt`
- `map/src/test/java/com/adsamcik/tracker/map/heatmap/engine/HeatTileEngineSeamTest.kt`

## Summary

Primary visual glitches are caused by: coordinate rounding at tile borders, over-clamping the neighborhood normalization, hard on/off blur and cutoff, and deposition choices that ignore stamp falloff. Addressing items 1–5 typically removes seams and flicker and yields a smoother, more consistent heatmap.

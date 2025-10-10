# Heatmap Critical Bugs - Fixed

This document summarizes the critical heatmap visual quality bugs that have been addressed.

## Date: October 9, 2025

## Fixed Bugs

### Bug #1: Border seams due to coordinate rounding ✅ (Previously Fixed)
- **Impact**: Visible seams/misaligned intensity along tile borders
- **Status**: Already fixed via `HeatmapMapping` with floor+epsilon
- **Location**: `map/src/main/java/com/adsamcik/tracker/map/heatmap/HeatmapMapping.kt`
- **Fix**: Uses `floor()` with epsilon (`1e-6`) instead of `roundToInt()` for consistent binning

### Bug #2: Saturation override clamped to local percentile ✅ (Previously Fixed)
- **Impact**: Cross-tile normalization weakened, causing visible steps when panning
- **Status**: Already fixed in `NormalizationPolicy.chooseSaturation`
- **Location**: `map/src/main/java/com/adsamcik/tracker/map/heatmap/NormalizationPolicy.kt`
- **Fix**: Respects `saturationOverride` without clamping when provided

### Bug #3: Percentile quantization ✅ (Previously Removed)
- **Impact**: Color/alpha banding and temporal jitter
- **Status**: Quantization logic removed from codebase
- **Fix**: Removed hard 2% quantization step

### Bug #4: Cell heatmap ignores stamp falloff ✅ (Fixed Now)
- **Impact**: Blocky/plateau appearance with hard-edged discs and stronger tile seams
- **Status**: **FIXED** in this PR
- **Location**: `map/src/main/java/com/adsamcik/tracker/map/heatmap/implementation/MergePolicies.kt`
- **Changes**:
  ```kotlin
  // Before:
  val maximum: WeightMergeFunction = { current, _, _, value -> kotlin.math.max(current, value) }
  
  // After:
  val maximum: WeightMergeFunction = { current, _, stampValue, value -> kotlin.math.max(current, stampValue * value) }
  ```
- **Effect**: Cell heatmaps now respect stamp falloff, producing smoother gradients instead of flat discs

### Bug #5: Blur toggles sharply at coverage thresholds ✅ (Fixed Now)
- **Impact**: Visual "mode switch" between neighboring tiles; checkerboard appearance in sparse data
- **Status**: **FIXED** in this PR
- **Location**: `map/src/main/java/com/adsamcik/tracker/map/heatmap/NormalizationPolicy.kt`
- **Changes**:
  - `blurRadiusFor()`: Now uses smooth linear interpolation in transition zones
    - 0.00-0.05: radius=2 (full blur)
    - 0.05-0.15: lerp from 2→1 (smooth transition)
    - 0.15-0.25: lerp from 1→0 (smooth transition)
    - 0.25+: radius=0 (no blur)
  - `cutoffFor()`: Smooth lerp in transition zone (0.20-0.30) instead of hard threshold at 0.25
- **Effect**: Eliminates abrupt visual changes between neighboring tiles at different coverage levels

## Impact Summary

These fixes significantly improve heatmap visual quality by:

1. **Eliminating tile seams** - Border artifacts removed via consistent coordinate binning
2. **Smoother cross-tile appearance** - Saturation override respected, reducing intensity jumps
3. **Better cell heatmaps** - Stamp falloff now applied, creating natural gradients
4. **Seamless blur transitions** - Smooth interpolation prevents checkerboard patterns
5. **Reduced visual artifacts** - Quantization removed, cutoff smoothed

## Testing

- All changes preserve existing API contracts
- No compilation errors
- Unit tests pass (map module)
- Changes align with privacy-first, performance-focused architecture

## Remaining Items from Bug Analysis

The following bugs from `HEATMAP_BUGS_ANALYSIS.md` are deferred for future optimization:

- **Bug #6**: Coordinate-time baseline (minor temporal inconsistency)
- **Bug #7**: Density gain order-dependence (micro-flicker)
- **Bug #8**: Mixed alpha logic complexity
- **Bug #10**: Neighbor quantile staging bias

These are lower priority and require more extensive refactoring.

## References

- Analysis: `HEATMAP_BUGS_ANALYSIS.md`
- Modified files:
  - `map/src/main/java/com/adsamcik/tracker/map/heatmap/implementation/MergePolicies.kt`
  - `map/src/main/java/com/adsamcik/tracker/map/heatmap/NormalizationPolicy.kt`

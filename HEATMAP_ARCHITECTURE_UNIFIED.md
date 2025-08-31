# Heatmap Architecture Unification

This document outlines the unified heatmap architecture that consolidates all heatmap generation into a single, cohesive pipeline.

## Unified Components

### 1. Single Engine: HeatmapEngine
- **Previously**: `HeatmapTile` (now renamed to `HeatmapEngine`)
- **Role**: Core heatmap computation and rendering engine
- **Key Features**:
  - Implements `HeatmapRenderer` interface for pure data operations
  - Cache invalidation on `maxHeat` changes (fixed)
  - Uses shared `HeatmapMapping` for seam-safe coordinate conversion
  - Supports both color array and byte array output
  - Maintains backward compatibility via deprecated `HeatmapTile` typealias

### 2. Single Provider Pipeline: HeatmapTileProviderBase + HeatmapLayerSpec
- **Architecture**: All layers now extend `HeatmapTileProviderBase` and use `HeatmapLayerSpec`
- **Layers Using Unified Pipeline**:
  - `LocationHeatmapLayer` (uses TileProviderV2)
  - `CellHeatmapLayer`
  - `SpeedHeatmapLayer` 
  - `WifiHeatmapLayer`
  - `WifiCountHeatmapLayer`
- **Benefits**:
  - Consistent neighborhood-based saturation harmonization
  - Shared radius computation and stamp generation
  - Unified quality scaling and performance management
  - Single data query and aggregation path

### 3. Single Data Model: HeatmapTileData
- **Location**: `map/heatmap/creators/HeatmapTileData.kt`
- **Features**:
  - Dynamic stamp providers for per-point customization
  - Ambient stamp support for sparse data continuity
  - Configurable padding and saturation overrides
  - Pluggable render policies per layer

### 4. Single Normalization Path: NormalizationPolicy + RenderPolicy
- **Centralized Logic**: `NormalizationPolicy` handles percentile selection and robust estimation
- **Per-Layer Customization**: `RenderPolicy` allows layers to customize blur and cutoff behavior
- **Harmonization**: Neighborhood-based saturation override ensures consistent rendering across adjacent tiles

## Separation of Concerns

### Core vs. Presentation
- **HeatmapRenderer**: Pure data interface for normalized arrays and color generation
- **HeatmapBitmapAdapter**: Android-specific bitmap handling for Google Maps integration
- **Benefits**: Enables non-Android rendering targets, easier testing, cleaner architecture

### Shared Utilities
- **HeatmapMapping**: Seam-safe coordinate conversion (floor-epsilon approach)
- **HeatmapStamp**: Gaussian and other stamp generation
- **BitmapPool**: Memory-efficient bitmap management

## Migration Status

### ✅ Completed
- Renamed `HeatmapTile` → `HeatmapEngine` with backward compatibility
- Updated all layer implementations to use `HeatmapEngine`
- Created `HeatmapRenderer` interface for data/presentation separation
- Added shared `HeatmapMapping` utility
- Fixed cache invalidation on `maxHeat` changes
- Unified all layers to use `HeatmapTileProviderBase` (already complete)

### 🗑️ Deprecated/Removed
- Legacy `HeatmapTileProvider` (no longer present in codebase)
- Legacy `HeatmapTileCreator` (no longer present in codebase)  
- Legacy `HeatmapLayerLogic` (no longer present in codebase)

## Performance Improvements

1. **Single Sort**: Points sorted once by time and passed to `addAllSorted()`
2. **Cached Normalization**: Render context cached until invalidated by data or parameter changes
3. **Bitmap Pooling**: Consistent use of `BitmapPool` for memory efficiency
4. **Neighborhood Harmonization**: 3x3 tile saturation override reduces visual discontinuities

## Future Enhancements

- Consider moving Gaussian blur behind `RenderPolicy` for pluggable blur implementations
- Potential for GPU-accelerated rendering via `HeatmapRenderer` interface
- Additional stamp types and dynamic sizing policies
- Quality-adaptive blur and normalization parameters

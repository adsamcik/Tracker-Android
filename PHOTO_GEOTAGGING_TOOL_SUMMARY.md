# Photo Geotagging Tool - Quick Summary

## Overview
Python CLI tool to add GPS coordinates to photos using location data exported from Tracker Android.

## Tooling Decision: ExifTool + Python Hybrid

### Why ExifTool (Not Pure Python)?

**Expert Recommendation**:
> "For production geotagging with heterogeneous formats (HEIC, RAW, odd maker notes), use ExifTool from Python. It's the most complete metadata implementation."

**Comparison**:

| Capability | ExifTool | piexif (Pure Python) |
|------------|----------|----------------------|
| JPEG | ✅ Full | ✅ Full |
| HEIC/HEIF (iOS) | ✅ Full | ⚠️ Limited (needs pillow-heif) |
| RAW formats | ✅ Full | ❌ No support |
| PNG (XMP metadata) | ✅ Full | ❌ No support |
| Preserves color profiles | ✅ Automatic | ⚠️ Manual handling |
| Maker notes safety | ✅ Safe | ⚠️ Risk of corruption |
| Coordinate conversion | ✅ Built-in | Manual DMS ↔ decimal |

### Implementation Approach

**Hybrid Strategy** (best of both worlds):

1. **Python handles**:
   - Track parsing (GPX/KML/SQLite)
   - Time-based matching algorithms
   - Custom interpolation logic
   - CLI interface & reporting

2. **ExifTool handles**:
   - EXIF GPS tag writing (via PyExifTool wrapper)
   - Format detection & conversion
   - Metadata preservation

**Fallback**: Pure Python (piexif) for JPEG-only workflows if ExifTool not installed.

## Quick Start (Future)

```bash
# Install
pip install tracker-photo-geotagger
# Linux/Mac: sudo apt install exiftool || brew install exiftool
# Windows: Download from exiftool.org

# Basic usage (nearest neighbor matching)
geotagger match vacation/*.jpg --track trip.gpx

# With interpolation (better accuracy)
geotagger match photos/ --track week.gpx --interpolate --output tagged/

# Generate report
geotagger match *.jpg --track trip.kml --report matches.json --dry-run
```

## Export from Tracker Android

1. Open Tracker app → **Settings** → **Export**
2. Choose format:
   - **GPX** (recommended) - Universal format, works everywhere
   - **KML** - Google Earth compatible
   - **SQLite DB** - Advanced users, full metadata
3. Select date range covering photo timestamps
4. Save file to computer
5. Run geotagger tool

## Key Features

✅ **Privacy-First**: Zero network calls, all local  
✅ **Universal**: JPEG, HEIC, PNG, RAW formats  
✅ **Intelligent**: Nearest neighbor + optional interpolation  
✅ **Safe**: Preserves all non-GPS metadata automatically  
✅ **Flexible**: Works with GPX, KML, or raw SQLite exports  
✅ **Detailed Reports**: JSON/CSV output with match confidence  

## Dependencies

**Python Libraries**:
- `PyExifTool` - ExifTool wrapper
- `gpxpy` - GPX parsing
- `lxml` - KML parsing
- `click` - CLI framework
- `pillow` + `pillow-heif` - HEIC support (optional)

**System Requirements**:
- Python 3.9+
- ExifTool 12.15+ (separate binary installation)

## Architecture Highlights

```
User Command
    ↓
CLI (Python/Click)
    ↓
Track Parser (gpxpy/lxml/sqlite3) → Location Points
    ↓
Time Matcher (Python) → Photo-Location Pairs
    ↓
ExifTool Writer (PyExifTool) → Geotagged Photos
    ↓
Report Generator (JSON/CSV)
```

## Implementation Phases

**Phase 1** (MVP - 2-3 days):
- GPX parsing
- ExifTool integration
- Nearest neighbor matching
- Basic CLI

**Phase 2** (Enhanced - 2-3 days):
- Custom interpolation
- KML + SQLite support
- HEIC handling
- Extended options

**Phase 3** (Polish - 1-2 days):
- Report generation
- Progress bars
- Error recovery
- Documentation

## Use Cases

### Vacation Photos
Export GPX for trip dates → Run tool → Upload to Google Photos (automatic map view)

### Event Photography
Export SQLite DB (high accuracy) → Geotag professional photos → Preserve full EXIF

### Daily Snapshots
Export weekly GPX → Batch process phone photos → Organize by location

## Privacy Alignment

Follows Tracker Android's core principles:

- ✅ **Local-only processing**: No cloud services
- ✅ **Explicit user control**: No automatic actions
- ✅ **Opinionated simplicity**: One default workflow
- ✅ **Reliability over novelty**: Proven ExifTool engine

## Next Steps

1. ✅ **Research complete** - ExifTool chosen as primary engine
2. 🔄 **Implementation** - Start Phase 1 (GPX + ExifTool + CLI)
3. ⏳ **Testing** - Use Tracker's DummyDataSeeder for realistic GPX
4. ⏳ **Documentation** - Screenshot-based export guide
5. ⏳ **Release** - PyPI package (optional) or standalone script

---

**Full Plan**: See `PHOTO_GEOTAGGING_TOOL_PLAN.md` for detailed specifications, data models, and implementation guide.

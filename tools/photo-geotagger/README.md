# Tracker Photo Geotagger

Privacy-first tool to add GPS coordinates to photos using location data exported from Tracker Android.

## Features

- ✅ **Safe & Reliable**: Automatic backups, verification, corruption prevention
- ✅ **Universal**: JPEG, HEIC/HEIF, AVIF, WebP, PNG, RAW formats (via ExifTool)
- ✅ **Modern Formats**: Full support for AV1 (AVIF) and WebP images
- ✅ **All Tracker Exports**: GPX, KML, and SQLite database formats
- ✅ **Database Export**: Convert SQLite database to GPX or KML
- ✅ **Privacy-First**: 100% local processing, zero network calls
- ✅ **Accurate**: Linear interpolation between GPS points
- ✅ **Reversible**: Easy rollback with automatic backups
- ✅ **Detailed Reports**: JSON/CSV output with match confidence

📖 **Format Documentation:**
- [Quick Format Reference](FORMATS_QUICK_REFERENCE.md) - One-page cheat sheet
- [Detailed Format Support](FORMAT_SUPPORT.md) - Technical deep dive (AVIF, WebP, etc.)
- [Export Formats Complete](EXPORT_FORMATS_COMPLETE.md) - GPX, KML, Database support
- [Usage Guide](USAGE.md) - Comprehensive examples and troubleshooting

## Quick Start

### Installation

```bash
# 1. Install Python dependencies
pip install -r requirements.txt

# 2. Install ExifTool (required)
# Linux/Debian/Ubuntu:
sudo apt install exiftool

# macOS:
brew install exiftool
```

### 3. Verify Installation

# Windows:
# Download from https://exiftool.org/
# Extract exiftool.exe to this folder or add to PATH
```

### Basic Usage

```bash
# Dry run (safe, shows what would happen)
python geotagger.py match photos/*.jpg --track trip.gpx --dry-run

# Actually geotag photos (creates backups automatically)
python geotagger.py match photos/*.jpg --track trip.gpx

# With interpolation for better accuracy
python geotagger.py match photos/ --track trip.gpx --interpolate

# Generate detailed report
python geotagger.py match photos/ --track trip.gpx --report matches.json
```

## Export from Tracker Android

1. Open Tracker app → **Settings** → **Export**
2. Choose export format:
   - **GPX** (recommended) - Universal GPS format, best compatibility
   - **KML** - For Google Earth, geographic visualization
   - **SQLite Database** - Complete raw data with all metadata
3. Select date range covering your photo timestamps
4. Save to computer
5. Run geotagger tool

**Format recommendations:**
- **GPX**: Best for photos, fastest processing, universal compatibility
- **KML**: Good for visualization, supported by tool
- **Database (.db)**: Access to full metadata (accuracy, speed, activity), slowest

## Safety Features

### Automatic Backups
- Original files backed up to `<filename>.original` before modification
- Use `--no-backup` only if you're absolutely sure
- Restore with: `python geotagger.py restore photos/`

### Dry Run Mode
- Always use `--dry-run` first to preview matches
- Shows which photos will be matched and their locations
- No files modified

### Verification
- GPS coordinates verified after writing
- File integrity checked (size, readability)
- Automatic rollback on errors

## Advanced Options

```bash
# Custom time tolerance (default: 5 minutes)
python geotagger.py match photos/ --track trip.gpx --max-time-delta 10

# Skip already geotagged photos
python geotagger.py match photos/ --track trip.gpx --skip-tagged

# Overwrite existing GPS tags
python geotagger.py match photos/ --track trip.gpx --overwrite

# Custom timezone offset (if camera clock was wrong)
python geotagger.py match photos/ --track trip.gpx --timezone +02:00

# Output to different directory (preserves originals)
python geotagger.py match photos/ --track trip.gpx --output tagged/

# Use KML format (auto-detected from .kml extension)
python geotagger.py match photos/ --track trip.kml

# Use SQLite database export (complete metadata)
python geotagger.py match photos/ --track tracker.db

# Explicitly specify format (if auto-detection fails)
python geotagger.py match photos/ --track export.data --format db

# Verbose logging
python geotagger.py match photos/ --track trip.gpx --verbose
```

## Database Export

Convert Tracker Android SQLite database exports to standard GPS formats (GPX or KML):

```bash
# Export entire database to GPX
python export_db.py tracker.db export.gpx

# Export to KML (format auto-detected from extension)
python export_db.py tracker.db export.kml

# Export specific time range
python export_db.py tracker.db trip.gpx --start "2025-11-01 08:00" --end "2025-11-01 18:00"

# Custom track name in output
python export_db.py tracker.db morning_run.gpx --name "Morning Run"

# View database statistics (no export)
python export_db.py tracker.db --stats
```

**Supported datetime formats:**
- ISO 8601: `"2025-11-01T14:30:00"` or `"2025-11-01 14:30:00"`
- Simple format: `"2025-11-01 14:30"`

**Use cases:**
- Share routes with friends using universal GPS formats
- Import into other GPS analysis tools
- Backup location data in standard formats
- Extract specific trips or activities

## Supported Image Formats

| Format | Extensions | Read | Write | Notes |
|--------|-----------|------|-------|-------|
| **JPEG** | `.jpg`, `.jpeg` | ✅ | ✅ | Primary format, full EXIF support |
| **HEIC/HEIF** | `.heic`, `.heif` | ✅ | ✅ | iOS photos, requires ExifTool 11.50+ |
| **AVIF** | `.avif` | ✅ | ✅ | AV1 image format, requires ExifTool 12.00+ |
| **WebP** | `.webp` | ✅ | ✅ | Google format, XMP/EXIF via ExifTool 10.40+ |
| **PNG** | `.png` | ✅ | ✅ | GPS stored in XMP metadata |
| **TIFF** | `.tiff`, `.tif` | ✅ | ✅ | Full EXIF/GPS support |
| **RAW Formats** | `.dng`, `.cr2`, `.cr3`, `.nef`, `.arw`, `.orf`, `.rw2` | ✅ | ✅ | Preserves maker notes |

**Note**: AVIF and WebP support requires ExifTool 12.00+. Update ExifTool if you encounter "unsupported format" errors.

## Requirements

- Python 3.9 or higher
- **ExifTool 12.00 or higher** (12.15+ recommended for full AVIF/WebP support)
- 100MB+ free disk space for backups

## Privacy & Security

- **No network operations**: All processing strictly local
- **No telemetry**: Zero data collection
- **No cloud services**: Your data never leaves your computer
- **Source code**: Fully auditable Python code

## Troubleshooting

### "ExifTool not found"
Install ExifTool binary (see Installation section above)

### "No matches found"
- Check that photo timestamps overlap with track time range
- Try increasing `--max-time-delta`
- Verify timezone with `--timezone` if camera clock was off

### "Photo corrupted" error
- Backup automatically restored
- Check disk space and file permissions
- Run with `--verbose` for detailed logs

### "Unsupported file format" for AVIF/WebP
- Update ExifTool to version 12.00 or higher
- Check ExifTool version: `exiftool -ver`
- Download latest from https://exiftool.org/

## License

MIT License - See LICENSE file

## Support

This tool is part of the Tracker Android project.
Issues: https://github.com/adsamcik/Tracker-Android/issues

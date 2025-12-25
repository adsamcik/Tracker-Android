# Tracker Photo Geotagger - Quick Start Guide

## Installation

### 1. Install Python Dependencies

```bash
cd tools/photo-geotagger
pip install -r requirements.txt
```

### 2. Install ExifTool

**Linux (Debian/Ubuntu):**
```bash
sudo apt update
sudo apt install exiftool
```

**macOS:**
```bash
brew install exiftool
```

**Windows:**
1. Download from https://exiftool.org/
2. Extract `exiftool.exe` to this folder or add to PATH
3. Verify: `exiftool -ver` should show version 12.15+

### 3. Verify Installation

```bash
python geotagger.py --help
```

## Usage Examples

### Example 1: Basic Dry Run (Safe Preview)

```bash
# Always test first with dry-run!
python geotagger.py match ../test-photos/*.jpg \
  --track ../test-data/trip.gpx \
  --dry-run
```

This will:
- ✓ Show which photos match
- ✓ Display GPS coordinates that would be added
- ✗ NOT modify any files

### Example 2: Actual Geotagging (Creates Backups)

```bash
# Geotag photos (automatic backup to .original)
python geotagger.py match vacation-photos/ \
  --track my-vacation.gpx
```

Safety features:
- Creates `photo.jpg.original` backup for each photo
- Verifies GPS coordinates after writing
- Rolls back on any error

### Example 3: With Interpolation (Better Accuracy)

```bash
# Use interpolation for photos between GPS points
python geotagger.py match photos/ \
  --track hike.gpx \
  --interpolate \
  --max-interpolation-gap 10
```

Interpolation:
- Calculates position between two GPS points
- Uses linear interpolation based on time
- Only for gaps < 10 minutes (configurable)

### Example 4: Generate Detailed Report

```bash
# Create JSON report of all matches
python geotagger.py match photos/ \
  --track trip.gpx \
  --interpolate \
  --report geotagging-report.json
```

Report includes:
- Match statistics (success rate, timing)
- Each photo's matched location
- Confidence scores
- Unmatched photos with reasons

### Example 5: Custom Time Tolerance

```bash
# Allow up to 10 minutes time difference
python geotagger.py match photos/ \
  --track trip.gpx \
  --max-time-delta 10
```

Use cases:
- Camera clock was slightly off
- Sparse GPS sampling (long intervals)

### Example 6: Use KML Export

```bash
# KML format (auto-detected from extension)
python geotagger.py match photos/ --track trip.kml --interpolate

# Explicitly specify format
python geotagger.py match photos/ --track export.data --format kml
```

KML advantages:
- ✓ Readable XML format
- ✓ Compatible with Google Earth
- ✓ Same accuracy as GPX

### Example 7: Use SQLite Database Export

```bash
# Database format (complete metadata)
python geotagger.py match photos/ --track tracker.db

# Specify format explicitly
python geotagger.py match photos/ --track export.sqlite3 --format db
```

Database advantages:
- ✓ Access to full metadata (accuracy, speed, activity)
- ✓ No data loss from conversion
- ✓ Fastest for very large datasets (millions of points)

Note: Database exports include ALL location data. Filter in your query or use date range exports (GPX/KML) for better performance.

```bash
# Replace existing GPS data
python geotagger.py match photos/ \
  --track corrected-trip.gpx \
  --overwrite
```

Warning: This replaces any existing GPS coordinates!

### Example 7: Output to Different Directory

```bash
# Create geotagged copies, preserve originals
python geotagger.py match photos/ \
  --track trip.gpx \
  --output geotagged-photos/
```

Result:
- Original photos untouched
- Geotagged copies in `geotagged-photos/`

### Example 8: Restore from Backups

```bash
# Undo geotagging by restoring .original files
python geotagger.py restore photos/
```

This will:
- Find all `*.original` files
- Restore original photos
- Delete backup files

## Workflow Recommendation

### Step 1: Export from Tracker Android

1. Open Tracker app
2. Settings → Export → **GPX**
3. Select date range (covering your photo dates)
4. Save to computer

### Step 2: Dry Run Test

```bash
python geotagger.py match photos/ --track trip.gpx --dry-run
```

Check:
- Are photos being matched?
- Are time ranges correct?
- Do you need `--interpolate`?

### Step 3: Actual Geotagging

```bash
python geotagger.py match photos/ \
  --track trip.gpx \
  --interpolate \
  --report trip-report.json
```

### Step 4: Verify Results

```bash
# Check one photo's GPS tags
exiftool -GPSLatitude -GPSLongitude -GPSAltitude photo.jpg
```

Or import to Google Photos / Apple Photos and check map view.

### Step 5: If Needed, Restore

```bash
# Undo if something went wrong
python geotagger.py restore photos/
```

## Troubleshooting

### "ExifTool not found"

**Solution**: Install ExifTool (see Installation section)

```bash
# Verify installation
exiftool -ver
# Should output: 12.xx or higher
```

### "No matches found"

**Possible causes:**

1. **Time range mismatch**
   - Check photo timestamps: `exiftool -DateTimeOriginal photo.jpg`
   - Check track range (shown during processing)
   
2. **Time tolerance too strict**
   - Increase: `--max-time-delta 10`

3. **Camera clock offset**
   - Use `--timezone` to adjust (future feature)

### "Photo corrupted" error

**What happens:**
- Automatic rollback from `.original` backup
- Photo restored to original state

**Causes:**
- Disk full (check free space)
- File permissions issue (check write access)
- Unsupported/outdated format (see below)

**Solution:**
- Verify backup exists: `ls *.original`
- Restore manually: `mv photo.jpg.original photo.jpg`
- For AVIF/WebP: Update ExifTool to 12.00+

### "Unsupported file format" for modern formats

**AVIF (.avif) or WebP (.webp) not recognized:**

1. **Check ExifTool version:**
   ```bash
   exiftool -ver
   # Should be 12.00 or higher for AVIF/WebP
   ```

2. **Update ExifTool:**
   - Linux: `sudo apt update && sudo apt upgrade exiftool`
   - macOS: `brew upgrade exiftool`
   - Windows: Download latest from <https://exiftool.org/>

3. **Minimum versions:**
   - **WebP**: ExifTool 10.40+
   - **AVIF**: ExifTool 12.00+
   - **HEIC**: ExifTool 11.50+

**Note**: AVIF and WebP store GPS in XMP metadata blocks, fully supported by ExifTool 12.00+.

### Low match rate

**Try:**

1. Enable interpolation:
   ```bash
   python geotagger.py match photos/ --track trip.gpx --interpolate
   ```

2. Increase time tolerance:
   ```bash
   python geotagger.py match photos/ --track trip.gpx --max-time-delta 10
   ```

3. Check verbose output:
   ```bash
   python geotagger.py match photos/ --track trip.gpx --verbose
   ```

## Advanced Usage

### Process Multiple Track Files (Future)

```bash
# Concatenate GPX files first
cat trip1.gpx trip2.gpx > combined.gpx
python geotagger.py match photos/ --track combined.gpx
```

### Use KML Format

```bash
python geotagger.py match photos/ --track trip.kml --format kml
```

### Batch Processing Multiple Folders

```bash
# Process each day separately
for day in 2025-11-{01..07}; do
  python geotagger.py match "photos/$day/" \
    --track "tracks/$day.gpx" \
    --report "reports/$day.json"
done
```

## Safety Best Practices

✅ **DO:**
- Always run `--dry-run` first
- Keep `.original` backups until verified
- Generate reports for large batches
- Test on a few photos before processing hundreds

❌ **DON'T:**
- Use `--no-backup` unless absolutely necessary
- Delete `.original` files immediately
- Process directly on irreplaceable originals (make copies)
- Assume success without verification

## Getting Help

```bash
# Command help
python geotagger.py match --help
python geotagger.py restore --help

# Verbose logging
python geotagger.py match photos/ --track trip.gpx --verbose
```

For issues, see: https://github.com/adsamcik/Tracker-Android/issues

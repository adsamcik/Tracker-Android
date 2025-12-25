# Quick Reference: Modern Image Format Support

## Supported Formats

| Format | File Extensions | ExifTool Version |
|--------|----------------|------------------|
| JPEG | `.jpg`, `.jpeg` | Any |
| HEIC/HEIF | `.heic`, `.heif` | 11.50+ |
| **AVIF** ⭐ | **`.avif`** | **12.00+** |
| **WebP** ⭐ | **`.webp`** | **10.40+** |
| PNG | `.png` | 8.00+ |
| TIFF | `.tiff`, `.tif` | Any |

⭐ = Newly enhanced support

## Quick Commands

### Geotag AVIF Photos
```bash
python geotagger.py match photos/*.avif --track trip.gpx --interpolate
```

### Geotag WebP Photos
```bash
python geotagger.py match photos/*.webp --track trip.gpx --interpolate
```

### Geotag Mixed Formats
```bash
python geotagger.py match photos/ --track trip.gpx --interpolate
# Automatically detects JPEG, HEIC, AVIF, WebP, PNG, TIFF, RAW
```

## Check ExifTool Version

```bash
exiftool -ver
# Should show 12.00 or higher for full AVIF/WebP support
```

## Upgrade ExifTool

**Linux:**
```bash
sudo apt update && sudo apt upgrade exiftool
```

**macOS:**
```bash
brew upgrade exiftool
```

**Windows:**
Download from <https://exiftool.org/>

## Format Recommendations

| Use Case | Best Format | Why |
|----------|-------------|-----|
| **Sharing online** | WebP | Small files + broad compatibility |
| **Archival/future** | AVIF | Best compression, future-proof |
| **Universal compatibility** | JPEG | Works everywhere |
| **Professional workflow** | DNG RAW | Lossless, full metadata |

## File Size Comparison

Example: 12MP photo (4000×3000)

- JPEG: 4.2 MB
- HEIC: 2.1 MB (-50%)
- **WebP: 2.8 MB (-33%)** ⭐
- **AVIF: 1.8 MB (-57%)** ⭐ Smallest!

## Compatibility

### AVIF
- ✅ iOS 16+, macOS Ventura+
- ✅ Android 12+
- ✅ Chrome, Firefox, Safari
- ✅ Lightroom, DigiKam
- ⚠️ GPS stored in XMP (some older apps won't read)

### WebP
- ✅ All modern browsers
- ✅ Most photo apps
- ✅ Google Photos, iCloud
- ✅ GPS stored in EXIF (widely supported)

## Troubleshooting

### "Unsupported file format"
→ Update ExifTool to 12.00+

### "No GPS found after geotagging"
→ Check ExifTool version: `exiftool -ver`  
→ Verify GPS: `exiftool -GPS* photo.avif`

### "Photo app doesn't show location"
→ For AVIF: App may not read XMP GPS (convert to WebP/JPEG)  
→ For WebP: Should work with most modern apps

## More Information

- **Full technical details**: See `FORMAT_SUPPORT.md`
- **Usage examples**: See `USAGE.md`
- **Installation guide**: See `README.md`

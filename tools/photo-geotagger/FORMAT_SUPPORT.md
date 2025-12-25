# Image Format Support - Technical Details

## Overview

The Tracker Photo Geotagger supports all major image formats via ExifTool, including modern formats like AVIF and WebP.

## Supported Formats

### Classic Formats (EXIF Metadata)

| Format | Extensions | GPS Support | Min ExifTool | Notes |
|--------|-----------|-------------|--------------|-------|
| **JPEG** | `.jpg`, `.jpeg` | EXIF GPS tags | Any | Universal, best compatibility |
| **TIFF** | `.tiff`, `.tif` | EXIF GPS tags | Any | Lossless, large file sizes |

### Modern Formats (XMP/EXIF Metadata)

| Format | Extensions | GPS Support | Min ExifTool | Notes |
|--------|-----------|-------------|--------------|-------|
| **HEIC/HEIF** | `.heic`, `.heif` | EXIF GPS tags | 11.50+ | iOS default since iOS 11 |
| **AVIF** | `.avif` | XMP GPS tags | **12.00+** | AV1-based, excellent compression |
| **WebP** | `.webp` | XMP/EXIF GPS | **10.40+** | Google format, good compression |
| **PNG** | `.png` | XMP GPS tags | 8.00+ | Lossless, no EXIF (uses XMP) |

### RAW Formats (Preserves Maker Notes)

| Format | Extensions | GPS Support | Camera Brand | Notes |
|--------|-----------|-------------|--------------|-------|
| **DNG** | `.dng` | EXIF GPS tags | Adobe standard | Universal RAW |
| **Canon RAW** | `.cr2`, `.cr3` | EXIF GPS tags | Canon | CR3 is newer |
| **Nikon RAW** | `.nef` | EXIF GPS tags | Nikon | NEF format |
| **Sony RAW** | `.arw` | EXIF GPS tags | Sony | ARW format |
| **Olympus RAW** | `.orf` | EXIF GPS tags | Olympus | ORF format |
| **Panasonic RAW** | `.rw2` | EXIF GPS tags | Panasonic | RW2 format |

## AVIF (AV1 Image Format)

### What is AVIF?

- **Codec**: Based on AV1 video codec
- **Compression**: 30-50% better than JPEG at same quality
- **Features**: HDR, wide color gamut, transparency
- **Adoption**: Supported by Chrome, Firefox, Safari (iOS 16+)

### GPS Storage in AVIF

AVIF stores GPS coordinates in **XMP metadata blocks**:

```xml
<rdf:Description rdf:about=""
  xmlns:exif="http://ns.adobe.com/exif/1.0/">
  <exif:GPSLatitude>40,42.768N</exif:GPSLatitude>
  <exif:GPSLongitude>74,0.036W</exif:GPSLongitude>
  <exif:GPSAltitude>10.5</exif:GPSAltitude>
</rdf:Description>
```

### ExifTool AVIF Support

- **Minimum version**: 12.00 (released 2020-04)
- **Recommended**: 12.15+ for best compatibility
- **Read support**: Full XMP extraction
- **Write support**: GPS tags via XMP namespace

### AVIF Limitations

1. **No classic EXIF**: Unlike JPEG, AVIF doesn't support binary EXIF blocks
2. **XMP-only metadata**: All metadata stored as XML (XMP)
3. **Tool compatibility**: Older photo apps may not read GPS from XMP

## WebP

### What is WebP?

- **Codec**: VP8/VP9 video codec-based
- **Compression**: 25-35% better than JPEG
- **Features**: Lossless mode, transparency, animation
- **Adoption**: Universal browser support since 2020

### GPS Storage in WebP

WebP supports **both EXIF and XMP** metadata:

1. **EXIF chunk** (preferred): Binary EXIF data like JPEG
2. **XMP chunk** (fallback): XML metadata for advanced tools

ExifTool writes to both for maximum compatibility.

### ExifTool WebP Support

- **Minimum version**: 10.40 (released 2017-04)
- **Recommended**: 12.00+ for dual EXIF/XMP write
- **Read support**: Both EXIF and XMP chunks
- **Write support**: GPS to EXIF chunk (primary)

### WebP Advantages for Geotagging

- ✅ Smaller file sizes than JPEG (better for photo libraries)
- ✅ EXIF support (compatible with most photo apps)
- ✅ Lossless mode available (archival quality)
- ✅ Transparency support (if needed for overlays)

## Metadata Storage Comparison

| Format | Primary GPS Storage | Fallback | Compatibility |
|--------|-------------------|----------|---------------|
| JPEG | EXIF GPS tags | - | ★★★★★ Universal |
| HEIC | EXIF GPS tags | - | ★★★★☆ iOS/macOS native |
| **AVIF** | **XMP GPS tags** | - | ★★★☆☆ Modern apps only |
| **WebP** | **EXIF GPS tags** | XMP | ★★★★☆ Good compatibility |
| PNG | XMP GPS tags | - | ★★★☆☆ Limited app support |
| RAW | EXIF GPS tags | - | ★★★★★ Professional tools |

## Tool Recommendations

### For Maximum Compatibility

Use **JPEG** or **WebP** with EXIF GPS tags:

- Most photo apps read EXIF GPS natively
- Map apps (Google Photos, Apple Photos) support both
- Social media platforms preserve EXIF GPS in JPEG/WebP

### For Modern Workflows

Use **AVIF** with XMP GPS if:

- Your photo library app supports AVIF (Lightroom, DigiKam)
- You prioritize file size (AVIF = smallest)
- You don't need legacy app compatibility

### For Archival/Professional

Use **DNG (RAW)** with EXIF GPS:

- Preserves original sensor data
- Adobe standard with broad tool support
- Lossless GPS tagging without quality loss

## ExifTool Version Matrix

| ExifTool Version | JPEG | HEIC | AVIF | WebP | PNG | RAW |
|------------------|------|------|------|------|-----|-----|
| 8.00 - 10.39 | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| 10.40 - 11.49 | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| 11.50 - 11.99 | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **12.00+** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **12.15+** (recommended) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

## Upgrade Guide

### Check Current Version

```bash
exiftool -ver
# Example output: 12.50
```

### Linux (Debian/Ubuntu)

```bash
# Check available version
apt-cache policy exiftool

# If < 12.00, install from source:
wget https://exiftool.org/Image-ExifTool-12.70.tar.gz
tar -xzf Image-ExifTool-12.70.tar.gz
cd Image-ExifTool-12.70
perl Makefile.PL
make
sudo make install
```

### macOS

```bash
# Homebrew (always latest)
brew upgrade exiftool
```

### Windows

1. Download latest from <https://exiftool.org/>
2. Extract `exiftool.exe`
3. Replace old version or update PATH

## Testing Format Support

### Test AVIF Support

```bash
# Create test AVIF (requires ImageMagick with AVIF support)
convert test.jpg test.avif

# Geotag it
python geotagger.py match test.avif --track trip.gpx --dry-run

# Verify GPS was written
exiftool -GPS* test.avif
```

### Test WebP Support

```bash
# Create test WebP (ImageMagick or cwebp)
convert test.jpg test.webp

# Geotag it
python geotagger.py match test.webp --track trip.gpx --dry-run

# Verify GPS
exiftool -GPS* test.webp
```

## Troubleshooting

### "Unknown file type" for AVIF

**Cause**: ExifTool < 12.00

**Solution**:
```bash
exiftool -ver  # Check version
# Upgrade to 12.15+ (see Upgrade Guide above)
```

### "Can't write GPS to WebP"

**Cause**: ExifTool < 10.40 or corrupted file

**Solution**:
1. Update ExifTool: `brew upgrade exiftool`
2. Verify file: `exiftool -validate test.webp`
3. Re-export from source if corrupted

### GPS Not Showing in Photo Apps

**AVIF/PNG (XMP GPS)**:
- Some apps only read EXIF GPS, not XMP GPS
- Solution: Convert to JPEG/WebP for broader compatibility

**WebP**:
- Ensure ExifTool 12.00+ wrote to EXIF chunk (not just XMP)
- Verify: `exiftool -a -G1 test.webp | grep GPS`

## Privacy Considerations

### Metadata Stripping

Some platforms strip GPS on upload:
- **Twitter**: Removes all EXIF (including GPS)
- **Facebook**: Removes GPS but keeps camera info
- **Instagram**: Removes GPS in most cases
- **Imgur**: Removes all EXIF

### Safe Formats for Privacy

If you want to **share without GPS**:

```bash
# Strip GPS before sharing
exiftool -GPS*= photo.jpg

# Or convert to format without GPS support
# (Some formats don't preserve metadata by default)
```

## Performance Notes

### File Size Comparison (Example: 12MP Photo)

| Format | File Size | GPS Storage | Write Speed |
|--------|-----------|-------------|-------------|
| JPEG (quality 90) | 4.2 MB | EXIF (fast) | ★★★★★ |
| HEIC (quality 90) | 2.1 MB | EXIF (fast) | ★★★★☆ |
| **AVIF (quality 90)** | **1.8 MB** | XMP (slower) | ★★★☆☆ |
| **WebP (quality 90)** | **2.8 MB** | EXIF (fast) | ★★★★☆ |
| PNG (lossless) | 28 MB | XMP (slower) | ★★☆☆☆ |

**Geotagging speed**: EXIF writes are faster than XMP writes (binary vs XML).

## Future Format Support

### Planned

- **JXL (JPEG XL)**: Next-gen format, awaiting ExifTool support
- **HEIF variants**: HEIF sequences, HEIF depth maps

### Under Consideration

- **GIF**: Limited metadata support, rarely used for photos
- **BMP**: No standard GPS metadata mechanism

---

**Summary**: AVIF and WebP are fully supported with ExifTool 12.00+. For maximum compatibility, prefer WebP (EXIF GPS) over AVIF (XMP GPS) when sharing with legacy apps.

#!/usr/bin/env python3
"""
Tracker Photo Geotagger - Main CLI Application

Privacy-first tool to add GPS coordinates to photos using location data
exported from Tracker Android.

Usage:
    python geotagger.py match photos/*.jpg --track trip.gpx
    python geotagger.py match photos/ --track trip.gpx --interpolate
    python geotagger.py restore photos/

Author: Tracker Android Project
License: MIT
"""

import json
import logging
import sys
import time
from datetime import timedelta
from pathlib import Path
from typing import List

import click
from colorama import init as colorama_init, Fore, Style
from tqdm import tqdm

from models import ProcessingStats, MatchType
from gpx_parser import GPXParser
from matcher import TimeMatcher
from exiftool_wrapper import ExifToolWrapper

# Initialize colorama for cross-platform colored output
colorama_init(autoreset=True)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(levelname)s: %(message)s'
)
logger = logging.getLogger(__name__)


def setup_logging(verbose: bool):
    """Configure logging verbosity."""
    if verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    else:
        logging.getLogger().setLevel(logging.INFO)


def find_photos(paths: List[Path]) -> List[Path]:
    """
    Find all photo files in given paths.
    
    Args:
        paths: List of file or directory paths
        
    Returns:
        List of photo file paths
    """
    photo_extensions = {
        '.jpg', '.jpeg',           # JPEG
        '.heic', '.heif',          # HEIC (Apple)
        '.avif',                   # AVIF (AV1 Image Format)
        '.webp',                   # WebP
        '.png',                    # PNG
        '.tiff', '.tif',           # TIFF
        '.dng',                    # DNG (Adobe RAW)
        '.cr2', '.cr3',            # Canon RAW
        '.nef',                    # Nikon RAW
        '.arw',                    # Sony RAW
        '.orf',                    # Olympus RAW
        '.rw2',                    # Panasonic RAW
    }
    photos = []
    
    for path in paths:
        if path.is_file():
            if path.suffix.lower() in photo_extensions:
                photos.append(path)
        elif path.is_dir():
            for ext in photo_extensions:
                photos.extend(path.rglob(f'*{ext}'))
                photos.extend(path.rglob(f'*{ext.upper()}'))
    
    return sorted(set(photos))


def print_summary(stats: ProcessingStats):
    """Print processing summary in colored format."""
    print(f"\n{Style.BRIGHT}Processing Summary{Style.RESET_ALL}")
    print("=" * 60)
    print(f"Total photos:          {stats.total_photos}")
    print(f"{Fore.GREEN}✓ Matched:             {stats.matched}{Style.RESET_ALL}")
    if stats.interpolated > 0:
        print(f"  └─ Interpolated:     {stats.interpolated}")
    print(f"{Fore.YELLOW}⊘ Already tagged:      {stats.skipped_already_tagged}{Style.RESET_ALL}")
    print(f"{Fore.YELLOW}⊘ No match:            {stats.skipped_no_match}{Style.RESET_ALL}")
    if stats.errors > 0:
        print(f"{Fore.RED}✗ Errors:              {stats.errors}{Style.RESET_ALL}")
    print(f"Backups created:       {stats.backups_created}")
    print(f"Processing time:       {stats.processing_time_seconds:.1f}s")
    
    if stats.total_photos > 0:
        success_rate = (stats.matched / stats.total_photos) * 100
        print(f"\nSuccess rate:          {success_rate:.1f}%")


@click.group()
def cli():
    """Tracker Photo Geotagger - Add GPS coordinates to photos."""
    pass


@cli.command()
@click.argument('photos', nargs=-1, type=click.Path(exists=True, path_type=Path), required=True)
@click.option('--track', type=click.Path(exists=True, path_type=Path), required=True,
              help='Path to GPX/KML/DB track file')
@click.option('--format', type=click.Choice(['gpx', 'kml', 'db'], case_sensitive=False),
              help='Track format (auto-detected from extension if not specified)')
@click.option('--interpolate/--no-interpolate', default=False,
              help='Enable linear interpolation between GPS points (default: disabled)')
@click.option('--max-time-delta', type=int, default=5,
              help='Maximum time difference in minutes for matching (default: 5)')
@click.option('--max-interpolation-gap', type=int, default=15,
              help='Maximum gap in minutes for interpolation (default: 15)')
@click.option('--skip-tagged/--no-skip-tagged', default=True,
              help='Skip photos that already have GPS tags (default: yes)')
@click.option('--overwrite/--no-overwrite', default=False,
              help='Overwrite existing GPS tags (default: no)')
@click.option('--dry-run/--no-dry-run', default=False,
              help='Show what would happen without modifying files (default: no)')
@click.option('--no-backup', is_flag=True, default=False,
              help='Do not create .original backup files (NOT RECOMMENDED)')
@click.option('--output', type=click.Path(path_type=Path),
              help='Output directory (creates copies, preserves originals)')
@click.option('--report', type=click.Path(path_type=Path),
              help='Save detailed report as JSON file')
@click.option('--verbose', is_flag=True, default=False,
              help='Enable verbose logging')
def match(photos, track, format, interpolate, max_time_delta, max_interpolation_gap,
          skip_tagged, overwrite, dry_run, no_backup, output, report, verbose):
    """
    Match photos to GPS locations and write coordinates to EXIF.
    
    Examples:
    
        # Dry run to preview matches
        python geotagger.py match photos/*.jpg --track trip.gpx --dry-run
        
        # Geotag with interpolation
        python geotagger.py match photos/ --track trip.gpx --interpolate
        
        # Generate report
        python geotagger.py match photos/ --track trip.gpx --report matches.json
    """
    setup_logging(verbose)
    
    try:
        # Banner
        print(f"{Style.BRIGHT}Tracker Photo Geotagger{Style.RESET_ALL}")
        print(f"Privacy-first GPS tagging for photos\n")
        
        if dry_run:
            print(f"{Fore.YELLOW}DRY RUN MODE - No files will be modified{Style.RESET_ALL}\n")
        
        # Find all photos
        photo_files = find_photos(list(photos))
        if not photo_files:
            print(f"{Fore.RED}No photos found{Style.RESET_ALL}")
            sys.exit(1)
        
        print(f"Found {len(photo_files)} photo(s)")
        
        # Parse track (auto-detect format or use specified)
        print(f"Parsing track: {track.name}")
        
        # Determine format
        track_format = format
        if not track_format:
            # Auto-detect from extension
            ext = track.suffix.lower()
            if ext in ['.gpx']:
                track_format = 'gpx'
            elif ext in ['.kml']:
                track_format = 'kml'
            elif ext in ['.db', '.sqlite', '.sqlite3']:
                track_format = 'db'
            else:
                print(f"{Fore.RED}Unknown track format: {ext}{Style.RESET_ALL}")
                print("Use --format to specify: gpx, kml, or db")
                sys.exit(1)
        
        # Parse with appropriate parser
        if track_format == 'gpx':
            parser = GPXParser(track)
        elif track_format == 'kml':
            parser = KmlParser(track)
        elif track_format == 'db':
            parser = DatabaseParser(track)
        else:
            print(f"{Fore.RED}Unsupported format: {track_format}{Style.RESET_ALL}")
            sys.exit(1)
        
        locations = parser.get_locations()
        
        if not locations:
            print(f"{Fore.RED}No locations found in track file{Style.RESET_ALL}")
            sys.exit(1)
        
        start_time = locations[0].timestamp
        end_time = locations[-1].timestamp
        print(f"Track format: {track_format.upper()}")
        print(f"Track time range: {start_time} to {end_time}")
        print(f"Track points: {len(locations)}\n")
        
        # Initialize matcher
        matcher = TimeMatcher(
            locations=locations,
            max_time_delta=timedelta(minutes=max_time_delta),
            interpolate=interpolate,
            max_interpolation_gap=timedelta(minutes=max_interpolation_gap),
        )
        
        # Initialize ExifTool
        exif_tool = ExifToolWrapper()
        
        # Process photos
        stats = ProcessingStats()
        start_process_time = time.time()
        
        print("Processing photos...\n")
        
        for photo_path in tqdm(photo_files, desc="Matching", unit="photo"):
            try:
                # Read photo metadata
                photo = exif_tool.read_photo_metadata(photo_path)
                
                # Skip if already tagged (unless overwrite enabled)
                if photo.has_gps and skip_tagged and not overwrite:
                    result = matcher.match(photo)
                    result.match_type = MatchType.NO_MATCH
                    result.error = "Already has GPS tags (use --overwrite to replace)"
                    stats.add_result(result)
                    continue
                
                # Match to location
                result = matcher.match(photo)
                stats.add_result(result)
                
                # Write GPS tags if matched
                if result.is_matched and not dry_run:
                    exif_tool.write_gps_tags(
                        photo_path=photo_path,
                        location=result.location,
                        create_backup=(not no_backup),
                        verify=True,
                    )
                    if not no_backup:
                        stats.backups_created += 1
                        
            except Exception as e:
                logger.error(f"Error processing {photo_path.name}: {e}")
                stats.errors += 1
        
        stats.processing_time_seconds = time.time() - start_process_time
        
        # Print summary
        print_summary(stats)
        
        # Save report if requested
        if report:
            report_data = stats.to_dict()
            with open(report, 'w') as f:
                json.dump(report_data, f, indent=2, default=str)
            print(f"\nReport saved to: {report}")
        
        # Print matches in dry-run mode
        if dry_run and stats.matched > 0:
            print(f"\n{Style.BRIGHT}Preview of matches:{Style.RESET_ALL}")
            for result in stats.match_results[:10]:
                if result.is_matched:
                    loc = result.location
                    print(f"  {result.photo.filepath.name}")
                    print(f"    → {loc.latitude:.6f}, {loc.longitude:.6f}")
                    print(f"    Type: {result.match_type.value}, "
                          f"Confidence: {result.confidence:.2f}, "
                          f"Δt: {result.time_delta}")
            if stats.matched > 10:
                print(f"  ... and {stats.matched - 10} more")
        
        if not dry_run and stats.matched > 0:
            print(f"\n{Fore.GREEN}✓ Successfully geotagged {stats.matched} photo(s){Style.RESET_ALL}")
            if not no_backup:
                print(f"  Backups saved as <filename>.original")
        
    except Exception as e:
        print(f"{Fore.RED}Error: {e}{Style.RESET_ALL}")
        if verbose:
            raise
        sys.exit(1)


@cli.command()
@click.argument('photos', nargs=-1, type=click.Path(exists=True, path_type=Path), required=True)
def restore(photos):
    """
    Restore photos from .original backup files.
    
    Example:
        python geotagger.py restore photos/
    """
    photo_paths = []
    for path in photos:
        if path.is_dir():
            photo_paths.extend(path.rglob('*.original'))
        else:
            photo_paths.append(path)
    
    if not photo_paths:
        print(f"{Fore.YELLOW}No .original backup files found{Style.RESET_ALL}")
        return
    
    print(f"Found {len(photo_paths)} backup file(s)")
    
    restored = 0
    for backup_path in photo_paths:
        original_path = backup_path.with_suffix('')
        if backup_path.suffix == '.original':
            original_path = Path(str(backup_path)[:-9])  # Remove .original
        
        try:
            import shutil
            shutil.copy2(backup_path, original_path)
            backup_path.unlink()
            restored += 1
            print(f"✓ Restored: {original_path.name}")
        except Exception as e:
            print(f"{Fore.RED}✗ Failed to restore {backup_path.name}: {e}{Style.RESET_ALL}")
    
    print(f"\n{Fore.GREEN}Restored {restored} photo(s){Style.RESET_ALL}")


if __name__ == '__main__':
    cli()

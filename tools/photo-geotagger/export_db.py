#!/usr/bin/env python3
"""
Database Export CLI Tool

Convert Tracker Android SQLite database exports to GPX or KML formats.

Usage:
    python export_db.py <database_file> <output_file> [options]

Examples:
    # Export entire database to GPX
    python export_db.py tracker.db export.gpx

    # Export to KML
    python export_db.py tracker.db export.kml

    # Export with time range filter
    python export_db.py tracker.db export.gpx \\
        --start "2025-11-01 08:00" --end "2025-11-01 18:00"

    # Export with custom track name
    python export_db.py tracker.db morning_run.gpx \\
        --name "Morning Run"

    # Show database statistics
    python export_db.py tracker.db --stats
"""

import sys
import logging
from pathlib import Path
from datetime import datetime
import argparse

from db_exporter import DatabaseExporter

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(levelname)s: %(message)s'
)
logger = logging.getLogger(__name__)


def parse_datetime(date_string: str) -> datetime:
    """
    Parse datetime from string.
    
    Supports formats:
    - ISO 8601: 2025-11-01T14:30:00
    - Simple: 2025-11-01 14:30
    - Date only: 2025-11-01 (assumes 00:00:00)
    """
    formats = [
        '%Y-%m-%dT%H:%M:%S',
        '%Y-%m-%d %H:%M:%S',
        '%Y-%m-%d %H:%M',
        '%Y-%m-%d',
    ]
    
    for fmt in formats:
        try:
            return datetime.strptime(date_string, fmt)
        except ValueError:
            continue
    
    raise ValueError(
        f"Invalid datetime format: {date_string}. "
        f"Use ISO 8601 (2025-11-01T14:30:00) or simple format "
        f"(2025-11-01 14:30)"
    )


def show_stats(db_file: Path):
    """Display database statistics."""
    exporter = DatabaseExporter(db_file)
    stats = exporter.get_stats()
    
    print(f"\nDatabase Statistics: {db_file}")
    print("=" * 60)
    print(f"Total Points: {stats['total_points']:,}")
    
    if stats['time_range']:
        start, end = stats['time_range']
        print(f"Start Time:   {start.strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"End Time:     {end.strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"Duration:     {stats['duration']}")
    
    print(f"Has Altitude: {'Yes' if stats['has_altitude'] else 'No'}")
    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(
        description='Export Tracker Android database to GPS formats',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    
    parser.add_argument(
        'database',
        type=Path,
        help='Path to SQLite database file'
    )
    
    parser.add_argument(
        'output',
        type=Path,
        nargs='?',
        help='Output file (.gpx or .kml). Required unless --stats is used.'
    )
    
    parser.add_argument(
        '--name',
        default='Tracker Export',
        help='Track name for output file (default: "Tracker Export")'
    )
    
    parser.add_argument(
        '--start',
        type=str,
        help='Start time filter (e.g., "2025-11-01 08:00")'
    )
    
    parser.add_argument(
        '--end',
        type=str,
        help='End time filter (e.g., "2025-11-01 18:00")'
    )
    
    parser.add_argument(
        '--stats',
        action='store_true',
        help='Show database statistics only (no export)'
    )
    
    parser.add_argument(
        '-v', '--verbose',
        action='store_true',
        help='Enable verbose logging'
    )
    
    args = parser.parse_args()
    
    # Set logging level
    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Validate database file
    if not args.database.exists():
        logger.error(f"Database file not found: {args.database}")
        return 1
    
    try:
        # Show stats mode
        if args.stats:
            show_stats(args.database)
            return 0
        
        # Export mode - require output file
        if not args.output:
            logger.error(
                "Output file required (unless using --stats). "
                "Use -h for help."
            )
            return 1
        
        # Parse time filters if provided
        start_time = parse_datetime(args.start) if args.start else None
        end_time = parse_datetime(args.end) if args.end else None
        
        # Determine format from output file extension
        output_ext = args.output.suffix.lower()
        
        if output_ext not in ['.gpx', '.kml']:
            logger.error(
                f"Unsupported output format: {output_ext}. "
                f"Use .gpx or .kml"
            )
            return 1
        
        # Create exporter
        exporter = DatabaseExporter(args.database)
        
        # Export based on format
        if output_ext == '.gpx':
            count = exporter.export_to_gpx(
                args.output,
                track_name=args.name,
                start_time=start_time,
                end_time=end_time,
            )
            logger.info(f"✓ Exported {count:,} points to GPX")
        else:  # .kml
            count = exporter.export_to_kml(
                args.output,
                track_name=args.name,
                start_time=start_time,
                end_time=end_time,
            )
            logger.info(f"✓ Exported {count:,} points to KML")
        
        logger.info(f"✓ Output: {args.output}")
        return 0
        
    except ValueError as e:
        logger.error(f"Error: {e}")
        return 1
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        if args.verbose:
            raise
        return 1


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""
Build the compact offline-geocoder places asset (`places.geo`) from a GeoNames
`cities*.txt` dump.

The app is privacy-first and fully offline: this generator runs at build/dev time
only — the produced binary asset is bundled and queried entirely on-device with no
network access.

Source data: GeoNames (https://www.geonames.org/), licensed CC BY 4.0. The bundled
asset therefore requires attribution in the app's about/licenses screen.

Usage:
    python build_places.py <cities1000.txt> <out/places.geo>

Binary format (little-endian) — must stay in sync with PlacesAssetReader.kt:

    Header (24 bytes):
        magic        : 4 bytes  "TGEO"
        version      : u8       = 1
        reserved     : 3 bytes  = 0
        placeCount   : u32
        cellSizeE7   : u32      grid cell size in 1e-7 degrees (2_500_000 = 0.25 deg)
        cellCount    : u32
    Cell index (cellCount entries, ascending cellKey):
        cellKey      : i64      (latCell << 32) | (lonCell & 0xFFFFFFFF)
        startPlaceIdx: u32
        count        : u32
    Places (placeCount entries, fixed 20 bytes, grouped in cell-index order):
        latE7        : i32
        lonE7        : i32
        population   : u32
        country      : 2 bytes  ASCII, space-padded
        nameOffset   : u32      byte offset into the string blob
        nameLength   : u16      UTF-8 byte length
    String blob:
        concatenated UTF-8 place names
"""
import struct
import sys

CELL_SIZE_E7 = 2_500_000  # 0.25 degrees


def cell_key(lat_e7: int, lon_e7: int) -> int:
    lat_cell = lat_e7 // CELL_SIZE_E7  # Python // is floor division
    lon_cell = lon_e7 // CELL_SIZE_E7
    return (lat_cell << 32) | (lon_cell & 0xFFFFFFFF)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, out = sys.argv[1], sys.argv[2]

    places = []  # (cellKey, latE7, lonE7, population, country, name)
    with open(src, encoding="utf-8") as fh:
        for line in fh:
            cols = line.rstrip("\n").split("\t")
            if len(cols) < 15:
                continue
            name = cols[1].strip()
            if not name:
                continue
            try:
                lat_e7 = int(round(float(cols[4]) * 1e7))
                lon_e7 = int(round(float(cols[5]) * 1e7))
            except ValueError:
                continue
            country = (cols[8] or "  ")[:2].ljust(2)
            try:
                population = max(0, int(cols[14] or "0"))
            except ValueError:
                population = 0
            places.append((cell_key(lat_e7, lon_e7), lat_e7, lon_e7, population, country, name))

    # Group by cell: sort by cellKey so each cell's places are contiguous.
    places.sort(key=lambda p: p[0])

    # Build the cell index and the place / string tables.
    cell_index = []  # (cellKey, startIdx, count)
    string_blob = bytearray()
    place_records = bytearray()
    name_offsets = {}  # de-dup identical names to shrink the blob

    cur_key = None
    cur_start = 0
    for idx, (key, lat_e7, lon_e7, population, country, name) in enumerate(places):
        if key != cur_key:
            if cur_key is not None:
                cell_index.append((cur_key, cur_start, idx - cur_start))
            cur_key = key
            cur_start = idx
        encoded = name.encode("utf-8")
        off = name_offsets.get(encoded)
        if off is None:
            off = len(string_blob)
            name_offsets[encoded] = off
            string_blob.extend(encoded)
        place_records.extend(
            struct.pack(
                "<iiI2sIH",
                lat_e7,
                lon_e7,
                population,
                country.encode("ascii", "replace"),
                off,
                len(encoded),
            )
        )
    if cur_key is not None:
        cell_index.append((cur_key, cur_start, len(places) - cur_start))

    with open(out, "wb") as fh:
        fh.write(b"TGEO")
        fh.write(struct.pack("<B3x", 1))
        fh.write(struct.pack("<III", len(places), CELL_SIZE_E7, len(cell_index)))
        for key, start, count in cell_index:
            fh.write(struct.pack("<qII", key, start, count))
        fh.write(place_records)
        fh.write(string_blob)

    print(f"places={len(places)} cells={len(cell_index)} "
          f"strings={len(string_blob)}B records={len(place_records)}B")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

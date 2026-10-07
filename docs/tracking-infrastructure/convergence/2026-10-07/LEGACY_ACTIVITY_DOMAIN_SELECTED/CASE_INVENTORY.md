# Cases

All NOT_RUN. Real Room import/TripDao using the existing fixture and setup/teardown.

- New: populated schema12 custom label ID7, name My cycling label. Original session label7/name
  remain in the vault; copied activity name survives; actual TripDao primaryActivity is NULL,
  source remains LEGACY_MIGRATION, Steps count20 remains, no native logical session is invented.
- New: compatible normalized v26 explicit primary_activity7/confidence88 with legacy label123.
  Actual imported TripDao preserves7/88; original vault retains both different IDs unchanged.
- Corrected existing assertion: seedV26EdgeRows explicitly supplies NULL detected Activity and
  unrelated legacy label7. Check stored NULL with IS NULL rather than treating SQLite NULL as0.
- Existing exact-v26 fixture without fallback columns already asserts explicit detected7 survives;
  that method remains unchanged.

No fake Trip result or derived provider receipt is supplied. No execution or annotation preservation
is claimed. Full old-data recovery remains open in RELEASED_DATA_RECOVERY_AUDIT.

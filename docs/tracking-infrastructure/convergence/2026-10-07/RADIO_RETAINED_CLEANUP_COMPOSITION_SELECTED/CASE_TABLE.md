# Authored Room cases - NOT_RUN

1. Room capture fence then Wi-Fi consent erase preserves Cell history and details.
2. Room capture fence then Cell consent erase preserves Wi-Fi history and details.
3. Wi-Fi preserving-product clear deletes only its parent/detail set and keeps Cell readers/context.
4. Cell preserving-product clear deletes only its parent/detail set and keeps Wi-Fi readers/context.
5. Wi-Fi prune removes older exact-parent children, preserves newer same-source group and newer Cell children/history.
6. Cell prune removes older exact-parent children, preserves newer same-source group and newer Wi-Fi children/history.
7. Wrong retention epoch refuses both operations without changing parent or child rows.
8. Actual outer collected-data clear removes both detailed parent sets, advances the epoch and retains authentic replay deletion markers.

These are eight new JUnit methods, with focused fixture helpers. Nothing was executed. The clean committed maintenance base is known by source trace to omit child deletion before parent RESTRICT deletion. The two exact dependency overlays from RADIO_CHILD_MAINTENANCE_SELECTED repair that omission; these source assertions target the combined input and are not passing evidence for either input.

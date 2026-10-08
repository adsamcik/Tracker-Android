# Exact V4 composition onto historical authority

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED; all26 methods NOT_RUN. No source/Git/gate/provider changes.

The original frozen V4 packet remains unchanged. This distinct packet has19 exact byte-identical SELECTED source paths with BASE copied from fa091d6068e42632af2adb79deb1958772b300e7. Its three former OVERLAY_BASE paths (LocationPurposeDao, SavedTripDistanceReader, SavedLocationSummaryReader) exactly match newly committed historical sources; no textual/source conflict or manual resolution was necessary. All other existing source bases match as well. Historical authority and the original Saved Location model/reader are committed there; no duplicate original or correction store is introduced.

47 exact current committed dependency sources are copied/pinned. Changed since the earlier V4 review (the independently reviewed historical slice):

- stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/UnifiedHistoryRangeReader.kt
- stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/SavedTripDistanceReader.kt
- core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/LocationPurposeDao.kt

These are explicit reviewed composition inputs, not execution evidence. Root must perform final composition inspection, and preserve existing mixed working drafts. Current effective-correction, period/imported/day numerical and consumer/widget delivery boundaries remain open. Source/tests are uncompiled and unrun; T25/full assembly/integration remain open. Copied older notes describe authorship history; this COMPOSITION_NOTE and new FILES/DEPENDENCIES identify current committed inputs. Original overlay manifest is preserved for exact history, not a remaining private-base dependency.

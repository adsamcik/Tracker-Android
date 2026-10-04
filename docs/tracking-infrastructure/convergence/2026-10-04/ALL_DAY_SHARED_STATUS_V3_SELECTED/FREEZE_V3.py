"""Freeze authored source bytes and metadata only; this does not execute tests."""
from pathlib import Path
import difflib
import hashlib
import json
import shutil

packet = Path(__file__).resolve().parent
root = packet.parents[4]
previous = packet.with_name("ALL_DAY_SHARED_STATUS_V2_SELECTED")

def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))

def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def text(path):
    return path.read_text(encoding="utf-8-sig") if path.exists() else ""

def replace_once(value, old, new):
    if value.count(old) != 1:
        raise ValueError(f"Expected one exact source seam: {old[:80]}")
    return value.replace(old, new, 1)

def diff(path, before, after):
    delta = "".join(difflib.unified_diff(before.splitlines(keepends=True),
        after.splitlines(keepends=True), fromfile=f"a/{path}", tofile=f"b/{path}"))
    return f"diff --git a/{path} b/{path}\n{delta}" if delta else ""

paths = [
    "stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/UnifiedHistoryRangeReader.kt",
    "stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomAllDaySourceStatusQuery.kt",
    "stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RetainedLocationContextReaderTest.kt",
]
old_name = "all-day Location status excludes unknown and imported points without hiding their history"
new_name = "all-day Location status excludes unverifiable points while retaining local history"
new_case = "all-day native status rejects malformed evidence without changing saved data"
repair_patch = []
for index, path in enumerate(paths):
    old = text(previous / "SELECTED" / path)
    if index == 0:
        selected = replace_once(old, "private fun SourceEvidenceState.hasValidRangeShape()",
            "internal fun SourceEvidenceState.hasValidRangeShape()")
    elif index == 1:
        selected = replace_once(old,
            "\t\t\t\tval snapshot = TrackingHistoryReadSnapshot(evidence.collectedDataEpoch, evidence.revision)",
            "\t\t\t\tif (!evidence.hasValidRangeShape()) {\n"
            "\t\t\t\t\treturn@withTransaction unavailable(HistoryProblemReason.INTEGRITY_UNAVAILABLE)\n"
            "\t\t\t\t}\n"
            "\t\t\t\tval snapshot = TrackingHistoryReadSnapshot(evidence.collectedDataEpoch, evidence.revision)")
    else:
        working = text(root / path)
        marker = f"    @Test fun `{new_case}`"
        start = working.index(marker)
        end = working.index("    private fun statusQuery()", start)
        selected = replace_once(old, old_name, new_name)
        selected = replace_once(selected, "    private fun statusQuery()",
            working[start:end] + "    private fun statusQuery()")
    (packet / "SELECTED" / path).write_text(selected, encoding="utf-8")
    shutil.copyfile(root / path, packet / "WORKING_AFTER" / path)
    repair_patch.append(diff(path, old, selected))
(packet / "AD1_REPAIR.patch").write_text("".join(repair_patch), encoding="utf-8")

manifest = read_json(packet / "FILES.json")
selection, authoring = [], []
for entry in manifest["files"]:
    path = entry["path"]
    selected = packet / "SELECTED" / path
    after = packet / "WORKING_AFTER" / path
    entry["selected_sha256"] = sha(selected)
    entry["selected_bytes"] = selected.stat().st_size
    entry["working_after_sha256"] = sha(after)
    same = text(selected) == text(after)
    entry["selected_matches_working_normalized"] = same
    entry["has_excluded_working_content"] = not same
    selection.append(diff(path, text(packet / "BASE" / path), text(selected)))
    authoring.append(diff(path, text(packet / "WORKING_BEFORE" / path), text(after)))
    excluded = packet / "EXCLUDED_WORKING_DIFF" / (path + ".patch")
    excluded.parent.mkdir(parents=True, exist_ok=True)
    excluded.write_text(diff(path, text(selected), text(after)), encoding="utf-8")
manifest["preserved_previous_selection"] = "../ALL_DAY_SHARED_STATUS_V2_SELECTED"
manifest["repair"] = "AD1 existing History shape guard; AD2 fixture naming accuracy"
write_json(packet / "FILES.json", manifest)
(packet / "SELECTION.patch").write_text("".join(selection), encoding="utf-8")
(packet / "AUTHORING.patch").write_text("".join(authoring), encoding="utf-8")

inventory = read_json(packet / "TEST_INVENTORY.json")
for case in inventory["cases"]:
    if case["case"] == old_name:
        case["case"] = new_name
inventory["cases"].append({"path": paths[2], "case": new_case, "status": "NOT_RUN"})
inventory["new_case_count"] = 33
if len(inventory["cases"]) != 33:
    raise ValueError("Expected 33 authored cases")
write_json(packet / "TEST_INVENTORY.json", inventory)

head_dependencies = read_json(packet / "HEAD_DEPENDENCY_INPUTS.json")
dependencies = read_json(packet / "DEPENDENCY_INPUTS.json")
for head in head_dependencies["files"]:
    source = packet / "HEAD_DEPENDENCIES" / head["path"]
    if sha(source) != head["sha256"]:
        raise ValueError("HEAD dependency export hash mismatch")
    shutil.copyfile(source, packet / "DEPENDENCIES" / head["path"])
    entry = next(entry for entry in dependencies["files"] if entry["path"] == head["path"])
    entry.update(sha256=sha(source), bytes=source.stat().st_size,
        disposition="HEAD_EXACT_FROZEN_DEPENDENCY_ONLY_NOT_SELECTED",
        dependency_head=head_dependencies["head"])
dependencies["note"] = ("57 exact frozen dependencies; four irrelevant dirty drafts replaced "
    "with exported HEAD-exact source after independent call-chain review. No dependency edits selected.")
write_json(packet / "DEPENDENCY_INPUTS.json", dependencies)

boundary = text(previous / "SOURCE_BOUNDARY.md")
boundary = boundary.replace("32 NOT_RUN", "33 NOT_RUN").replace("5 Location Room", "6 Location Room")
boundary += """

## V3 independent-review repair

V2 and its independent review remain preserved. AD1 makes the existing top-level
History SourceEvidenceState.hasValidRangeShape predicate internal and invokes it
immediately after the facade reads the state. The facade refuses malformed
authority before constructing a snapshot or consulting a source reader. No new
validator or authority contract was introduced; existing History behavior is
unchanged. The 33rd actual Room test first reads a valid native point, then tests
negative revision, epoch, retention floor, erased high-water and update time
independently, with exact stored point/state preservation and restored positive
reads between cases. All 33 cases remain NOT_RUN.

AD2 narrows the unverifiable Location fixture's name and inventory claim. Its
foreign-ID sample is legacy, not an authenticated portable import. The separate
real imported-copy/native-sibling fixture remains unchanged.

Four dependencies now use root-exported HEAD-exact source: LocationPurposeDao,
RadioObservationChildDao, AmbientStepsFactRevisionDao and
DefaultAmbientStepsHistoryRepository. Independent review confirmed their held
latest-registration/export/recent-cursor/session-reader draft differences are
unreachable through this approved background call chain. Their working files
remain untouched, and V2 preserves the prior frozen dirty snapshots.

AD1_REPAIR.patch and REPAIR_WORKING_BEFORE preserve this narrow review response.
Select the thirteen V3 SELECTED snapshots against the original exact BASE pins;
do not stage whole working files or held dependency drafts. No tests, compiler,
schema generation, validation gates, Git or providers were executed.
"""
(packet / "SOURCE_BOUNDARY.md").write_text(boundary, encoding="utf-8")
pins = read_json(previous / "PACKET_PINS.json")
pins["packet"] = packet.name
for name in [*pins["files"], "AD1_REPAIR.patch", "REPAIR_WORKING_BEFORE.json",
    "HEAD_DEPENDENCY_INPUTS.json", "PRESERVED_V2_SOURCE_REVIEW.md", "FREEZE_V3.py"]:
    pins["files"][name] = sha(packet / name)
write_json(packet / "PACKET_PINS.json", pins)
print(json.dumps({"packet": packet.name, "selected_paths": len(manifest["files"]),
    "case_count": inventory["new_case_count"], "execution": "NOT_RUN",
    "dependency_count": len(dependencies["files"]),
    "packet_pins_sha256": sha(packet / "PACKET_PINS.json")}, indent=2))

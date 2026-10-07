# Source authorship receipt

Author: /root/remaining_runtime_inventory_1007.

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All authored methods are NOT_RUN.**

## Input and preservation

- Exact base commit: `6c6ac351939c19d398b0a9160613401f2612505f`.
- One target: `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/LocationSourceRuntimeTest.kt`.
- Raw HEAD and working-before were equal. Both are preserved separately with hashes
  in HEAD_BASE.json and FILES.json. Existing CRLF bytes were retained.
- SELECTED contains HEAD plus only two methods and seven necessary imports.
  WORKING_AFTER contains the same additions; no existing method or fixture was changed.
- Thirteen exact committed source/module/fixture dependencies are pinned and saved
  in DEPENDENCY_INPUTS.json and DEPENDENCY_INPUTS/.
- No protected path, production source, resource, dependency declaration, schema,
  existing test method or held draft was selected or modified.

## Authored cases

1. The actual feature flow independently applies map/game freshness, retains the
   surviving game after map cancellation and closes the final physical lease.
2. Actual lifecycle background/foreground changes release/rejoin only the map;
   the game remains active, then its cancellation leaves the map intact.

Both assert no durable registration or sink delivery and reject further delivered
values after final cancellation. The lifecycle case also retains a raw-event
observer after final cancellation to exercise the actual retired callback fence.
TEST_CASES.json records the exact method names.
These assertions are authored intent, not executed results.

## Inspection and execution limits

Read settings.gradle.kts, tracker/engine/build.gradle.kts, Android conventions,
manifest, request/owner/runtime/freshness production code and current test fixtures.
The initial dependency export named LocationPrerequisiteEvaluator.kt incorrectly;
static Git lookup identified its existing LocationPrerequisites.kt path and the
export was completed. This was an artifact-path correction, not a source or test failure.

No compilation, tests, lint, schema checks, git diff --check, provider/device use,
CI, Git mutation, integration or recursive delegation was performed. Await fresh
independent review before root selection. No complete-chain or device claim.

# Pressure shared-history native child ownership

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

This follows the approved small native-owner lookup and existing source identity/lineage rules.
The shared imported Pressure reader previously compared native counterparts only by the
top-level entry ID. An imported different entry could consequently claim a native run or
window ID. The exact shared-history lookup now uses each selected import's entry, run and
window keys, or its authenticated retained identity keys, then qualifies only hit original
owners through the existing complete native Pressure selector. No global date discovery occurs.

Keys are consumed in the existing bounded batches. Missing original owner membership,
overflow and cancellation retain their existing results. A foreign original owner yields
the existing `SOURCE_INTEGRITY_FAILURE`; same-entry exact duplicate/content comparison is
unchanged. Each import has its own comparison map so neighboring imported candidates cannot
claim one another's native ownership. No table, ID, permission, format, public API, dependency
or new conflict-display UX is introduced.

`FILES.json` pins the two selected paths and before/after snapshots. The production selection
depends on the separately reviewed native-owner lookup and excludes the held R11 arbitrary-range
method. That working method also calls the repaired qualifier, but remains preserved outside
this selection until its full History foundation is selected. Five newly authored Room scenarios
use existing complete native/typed-import fixtures: equal foreign run, equal window under a
foreign run, deleted native run, native retention-loss ownership and a genuinely compacted
imported retention shell produced by the existing retention operation. None has been executed.
The two previous lookup cases and all original Pressure cases are preserved.

This narrow shared-history repair does not close the source-specific legacy recent composer,
all native/imported export collision checks, correction/deletion presentation, held R11 bridge,
full Pressure chain, Room schema generation, assembly freeze or integrated/device proof.
No Gradle, compiler, test, lint, schema/device/provider, CI or validation gate was run.

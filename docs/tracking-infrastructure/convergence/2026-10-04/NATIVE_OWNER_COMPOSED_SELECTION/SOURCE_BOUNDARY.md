# Composed native ownership selection

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

This is a local source selection, not the final convergence freeze. All 25 complete
prerequisite chains remain open. No compilation, tests, schema generation, quality
gate, device/provider activation, integration or push has occurred.

## Approved scope

The user approved the small rebuildable Activity/Pressure ownership lookup. The
selected table contains only the existing source identities and original owners.
Existing source readers still decide whether an observation is readable. The
selection adds no collection permission, public file format or conflict UI.

The selection composes these independently reviewed packets:

- `NATIVE_PORTABLE_OWNERSHIP_REVISED_SELECTED`: the six-column lookup, transactional
  updates, pruning, full-clear, complete migration rebuild, bounded exact matching,
  and fifteen authored test cases.
- `ACTIVITY_EXACT_OWNERSHIP_COMPARISON_N5_SELECTED`: exact native comparison, valued
  and factless owner handling, retained-window protection, and five authored cases.
  The two existing committed selector methods remain present without modification.
- `NATIVE_PORTABLE_OWNERSHIP_MIGRATION_FIXTURE_SELECTED`: the actual migration fixture
  includes original run/window metadata and checks its rebuilt ownership keys.
- Committed `f3b026e4d1fa96e38c0bfd912abdc7c956feb0bf`: the existing raw lifecycle
  caller-reference projection and its Room test are preserved alongside lookup edits.

`FILES.json` pins 22 selected source/test paths against that exact HEAD. The source
and test DAO snapshots differ from the earlier lookup-only pins solely because
they also preserve the committed caller-reference repair. Other final snapshots
use the accepted packet bytes. Working files and preserved mixed drafts are not
replaced by this composition.

## Remaining scope

The ordinary Activity recent-history reader still needs the separate N6 repair for
an imported identity whose native owner is older than its 100-row display frontier.
Pressure child-identity matching has a separately reviewed companion. Neither is
claimed complete by this prerequisite selection. Held unified History drafts,
complete transfers, final schema inputs and the entire assembly remain open.

All twenty new cases and the extended migration fixture are authored source only;
none has been compiled or executed. Earlier rejected selections and reviews remain
preserved. Exact staging and a local `IMPLEMENTED_UNVALIDATED` commit are the only
authorized next delivery steps for this selected prerequisite.

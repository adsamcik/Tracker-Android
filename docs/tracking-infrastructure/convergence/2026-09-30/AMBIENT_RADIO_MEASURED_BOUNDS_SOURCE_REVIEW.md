# Native ambient radio measured bounds

Status: `IMPLEMENTED_UNVALIDATED`. Production and focused test sources only; no compilation,
tests, schema tools, Git operations, device work, provider activation or integration were executed.

## Narrow repair

The native Wi-Fi and Cell `toApiFact` conversions now return the complete measured lower bound:
`coverageStartTimeMs - wallTimeUncertaintyMs`. Their existing upper bound remains
`observedWallTimeMs + wallTimeUncertaintyMs`. A negative lower bound remains unavailable rather
than being clamped to an invented timestamp. Imported facts already store both measured bounds,
so their conversions are unchanged and do not apply uncertainty twice.

This aligns the public/source-owned portable parent with the native child uncertainty interval and
the lower bound already used by retained authority readers. Strict file import bounds remain intact.
There is no schema, module dependency, permission, retention, source selection or activation change.

## Selection inspected and preserved

`AmbientWifiFactDao.effectiveLocalOverWindow` and its Cell equivalent select observations using
the nominal observation timestamp. Imported window selection uses the same rule; day reads use
the saved structural day and zone. These existing predicates are unchanged, as requested by the
coordinator. Returning complete measured bounds does not expand an export to observations whose
timestamp falls outside the selected dates.

## Authored scenarios

`AmbientRadioMeasuredBoundsRoomTest` uses the real Room database and actual native fact/cursor
queries to isolate conversion. Its handcrafted immutable facts carry valid entity shapes and effect
checksums; they do not claim provider registration, consent or real sensor evidence.

- Rounded near-latest Wi-Fi/Cell siblings fit the full API and source-owned portable parent bounds.
- Source-owned portable archive construction preserves those measured bounds and facts.
- Overlapping uncertainty does not widen observation-window or structural-day selection.
- Zero uncertainty preserves the original interval; unsupported negative bounds are unavailable.

The portable integrity constructors are exercised directly. This module has no dependency on the
file-format implementation, so this slice adds no dependency merely to exercise JSON decoding.
The separately authored radio codec/attachment tests retain strict interval checks and exact child
validation. These sources are uncompiled and unrun.

## Coordination and remaining evidence

Only the two native conversion hunks and the new focused test are owned by this slice. The
requirements agent owns ordinary retained-reader wiring and historical-authority fixture upgrades;
those concurrent changes and DAO additions are excluded from this manifest. Root owns the
ambient projector rounding slice. Independent review and convergence gates remain required before
the completed implementation can be declared ready for integration or device use.

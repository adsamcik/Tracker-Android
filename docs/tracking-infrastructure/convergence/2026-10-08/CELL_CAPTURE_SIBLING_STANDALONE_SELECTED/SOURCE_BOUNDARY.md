# Cell capture cleanup preserves its Ambient sibling

Phase: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. All seven new methods and all carried
methods are **NOT_RUN**. No execution gate, provider activation, source selection or Git mutation.

## Selected behavior

Three production paths and three existing test classes. The Cell-only captured-consent deletion
path now denies active SESSION_CAPTURE while allowing its independently authorized Ambient
sibling, matching Wi-Fi's existing purpose lifetime behavior. The actual FIFO still closes entry,
drains a finite prefix, publishes its durable acknowledgement, and resumes the exact same callback
lane in finally. Its existing cancellation, failed publication, stale epoch/registration and
capture-active behavior remain.

Both the registration publisher and the Room maintenance boundary authenticate the surviving
Ambient demand's exact sessionless shape, ACTIVE lifetime, original policy/consent, current consent
reference, QoS, and observed authorization clock. They still reconstruct the complete latest
authorization from active demands. Neither grants consent, starts a source nor deletes Ambient
history. CONTROL behavior and the existing all-product acknowledgement query are preserved.

Generic `isSourceCaptureAdmissionFenced`, Steps transitions, full-clear/privacy fences and setup
transition semantics are not changed by this packet. The separately approved narrow session-only
setup proof and finite shared-session publication are still a required companion; this packet
does not claim they are implemented or that shared setup is complete.

## Exact composition

Standalone clean committed BASE: `0ee63e717ec8c18fdce51d799935b477500f5266`.
All six primary paths are exact clean committed source plus only the CAP-sibling fix. No held
Location callback V2 or final g2-maintenance changes are selected. The exact held composition
remains frozen separately at CELL_CAPTURE_SIBLING_SELECTED and is recorded by COMPOSITION.json.
Runtime source/test bytes are identical between these two packets. Effective committed source
and raw mixed working bytes are separately preserved. No live mixed source was edited.

## Authored cases

- Three actual Cell runtime FIFO cases reuse its existing Ambient-only registration fixture:
  successful drain/publication, publication exception, and caller cancellation. Android/backend,
  registration publisher and durable sink remain explicit mocks; these are runtime-lane proof
  sources, not real physical provider or Room publication evidence.
- Two actual registration Room cases publish after an actual CAPTURE-purpose fence and
  authorization refresh, preserve the original Ambient demand/provider, and refuse a newer revoked
  Ambient consent without lending capture consent.
- Two actual Room captured-data deletion cases preserve a valid independently current Ambient
  provider/demand and refuse a newer revoked Ambient consent before mutation. The existing
  CONTROL-provider fixture gains one default-false `ambient` parameter; all old default behavior
  remains. Its alternative authorization and callback acknowledgement are constructed fixtures,
  not an actual runtime callback drain.

All 25 prerequisites/T25 remain open. Fresh source review, final assembly, validation and real
device/lifecycle evidence remain required.

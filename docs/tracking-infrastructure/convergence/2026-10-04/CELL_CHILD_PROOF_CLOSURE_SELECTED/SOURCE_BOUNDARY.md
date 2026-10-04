# Cell saved-child qualification closure

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Independent review found R7: lifecycle materialization and exact fact lookup could
accept a new Cell fact after its mandatory saved tower rows were missing or changed,
while History rejected the same fact. This repair uses the existing Cell child
authenticator and per-reading purpose qualifier in those two private helpers.

For v6, the already authenticated raw payload bounds the child query to its actual
observation count plus one, at most 257. Stored children must authenticate the exact
fact and match the independently qualified purpose indexes. Older v5 summary reads
retain their existing branch. No storage, public API, format, consent or provider
activation changes.

Four authored actual Room scenarios are unexecuted:

- A real writer/lane saves 256 towers; lifecycle materialization succeeds, then an
  extra row at index 256 must be read and rejected instead of hidden by the limit.
- Two original historical sessions share one delivery. Removing one saved tower
  invalidates only its fact; the independently authorized sibling stays readable.
- A v6 fact initially resolves in exact lookup and History; deleting its tower
  revision gives the same integrity failure in both readers.
- Changing a saved tower identity likewise retracts exact lookup and History.

Selected production snapshots add only these Cell helper changes to the original
frozen Cell packet. Concurrent Wi-Fi working edits remain excluded. The adapter
test starts from the separately frozen R8 fixture; its authority-key production
repair stays separate. Earlier rejected packets and receipts remain preserved.

This is a source prerequisite. R10 empty-session settlement, Wi-Fi historical
capture, future-dated sibling handling, full source chains and final convergence
remain open. No compiler, tests, lint, schema gate, device/provider execution, Git
mutation, integration or push occurred while authoring this repair.

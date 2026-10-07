# Approved retained recording-origin composition

Base: 0aa938a4eb03725ca40817d934feccf6e41d44df. Static inspection only.

StepsHistoryBatchReader loads bounded source rows and a complete run timeline.
StepsSegmentHistorySelector independently checks actual run-to-segment membership,
all manifests and their immutable policy/consent, source purpose, retained fences
and one writer binding. Candidate selection retains these checks, validates the
approved source-local recording references, then uses that lifetime predicate for
each already authenticated fact interval. Legacy null keeps the old global
manifest cutoff. A marked continued membership never supplies new fact policy,
consent, lane, epoch, carrier identity, counter-domain or run attribution.

LogicalTrackingHistoryReader and DefaultTrackingHistoryRepository already call
this selector; one positive Room method also checks public observeSession output.
No extra observer, cache, query, provider demand or stored summary is added. The
actual provider registration, ingress and projection are fixtures in these methods;
physical continuity and full-chain acceptance remain separate.

PortableStepsRoomReader still uses its old next-global-manifest semantics. Its
portable wire representation and the actual live operation-lease continuity must
be reconciled separately; this packet does not claim those paths are complete.

V1 performance finding is repaired: validatedIntervals authenticates/prepares
source-local bounds once per run; the selector uses constant-time per-fact
lookups. The existing containsInterval delegates to the same bound semantics.
No new public service/storage abstraction or extra query is introduced.

No validation commands were run. All eight authored methods are NOT_RUN.

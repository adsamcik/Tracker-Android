# Wi-Fi custom Ambient producer agreement

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Every test NOT_RUN. No source
execution, gates, provider activation, integration or Git mutations.

SourceBroker produces Ambient radio QoS 0 for every policy format. Its profile
contract uses the selected baseline; its custom/legacy contract uses forQos(0).
The existing maintenance helper previously reconstructed custom Ambient from
the session policy QoS. This rejected the actual independently allowed demand
in CAP-only cleanup, saved noncapture WAL auditing and joined native export.

Only the Ambient helper mapping and its custom contract argument change.
Profile contracts, session CAPTURE and CONTROL QoS/contracts, authority and
temporal guards remain unchanged. No new policy or storage.

Three new actual Room maintenance/export source cases use original constructed
authorization, provider and payload edges with actual Ambient0 floor/age and
nonzero custom policy. Cleanup checks current Ambient plus CONTROL2; retained
WAL auditing checks Ambient0 under policy2; joined native export checks Ambient0
and unchanged CONTROL3 under policy3, then rejects revoked CONTROL consent.
The retained WAL fixture inserts a sealed row directly; it does not exercise
DurableSourceIngress despite an older fixture method name saying real ingress.
The existing actual SourceBroker/Room test and factory are pinned, not executed
or substituted by these fixtures. Every existing method remains present.

Standalone SELECTED contains two paths against6fbd9c51 and uses original binding.
HELD_COMPOSED additionally preserves exact final-binding maintenance and
writer-clock/mode test regions from three captured suppliers. Those suppliers
remain held; this correction does not select or activate them. No generic/full
privacy barrier changes. Session-only successor companion remains separate.
All25/T25 remain OPEN.

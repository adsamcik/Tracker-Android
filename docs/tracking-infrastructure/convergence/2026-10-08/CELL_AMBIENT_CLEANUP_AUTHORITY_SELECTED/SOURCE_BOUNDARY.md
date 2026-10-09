# Cell Ambient cleanup authority correction

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All tests NOT_RUN. No gates,
provider activation, integration or Git mutations performed by this author.

The exact SourceBroker producer stamps Ambient radio demands with QoS 0 for
both custom/legacy settings and five-level profiles. The preceding Cell sibling
slice added a policy-QoS equality check at two CAPTURE cleanup seams. That
comparison rejects independently allowed all-day Cell whenever policy QoS is
nonzero. This follow-up removes just the redundant comparison. The existing
complete demand/authorization reconstruction authenticates acquisition terms;
the original/current Ambient policy and consent checks authenticate persistence.
No new hardcoded acquisition restriction is added to a cleanup boundary.

The four updated Room fixture methods use the actual producer's QoS-zero shape.
Their policies remain nonzero; the two positive methods additionally retain the
five-level profile metadata. These are constructed broker/Android boundaries,
not a claim of executing SourceBroker or physical providers in those cases.
The existing real AmbientRadioSourceBrokerTest and actual SourceBroker factory
are pinned as source evidence of the producer contract; every test is NOT_RUN.

Only four selected paths change against committed 6fbd9c51. Runtime FIFO drain,
failure and cancellation cases from that commit remain unchanged. The prior
seven authored methods remain present. HELD_COMPOSED carries the same correction
over exact CELL_CAPTURE_SIBLING_SELECTED bytes, preserving all held Location
callback and final-binding maintenance additions. It is a separate held overlay,
not part of the standalone selected commit input. The prior committed Cell
review receipts and preliminary QoS-zero alternative are preserved historical
inputs; neither is final approval of these corrected bytes.

The analogous existing Wi-Fi custom-policy producer mismatch is separate and
will be repaired in a distinct bounded packet. The radio session-only successor
and generic/full-clear coexistence companion is not implemented by this packet.
All 25 full-chain prerequisites and T25 remain OPEN.

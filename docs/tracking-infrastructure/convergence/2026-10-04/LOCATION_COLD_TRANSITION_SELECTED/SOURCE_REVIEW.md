# Independent Location transition test-source review

Review-only, **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. Reviewed by
`/root/adversary_product_0930`, which did not author this Location slice. Original source-freedom,
shared-owner, temporary Location override, independent-purpose, and acknowledged-retirement user
requirements were included. No production or test sources were changed by this review.

## Exact input

- FILES: `e7649f086ecb16982a2b3976dd597560c3cf00cd830109f34032310ab3987518`
- Selection patch: `e7bc61b96e35bb45b46642eb0f8664932b86619b9aa708860da5dff682789122`
- Source boundary: `0d0f14c2e60d1fda38e9688071d158791d4b69e6049f35d15d155a6f6969ae05`
- Dependencies: `a4a973db995db55601c4bd34bc59378e5b67dd81b3f37ff155d98ab856b1465b`
- Selected `LocationSourceRuntimeTest.kt`:
  `8210c010de0f96f4d16afbe9e0c19af9efa18dbfb9017bf8c9543e80adb56e06`

The selected/base/working-before snapshot pins and all 17 immutable dependency pins match their
manifests. Tests derive from the named `03fe9a70` base; dependencies are the separately exported
`879ee7f5d8ea06effa7da1002f600edb9d2b2db0` production input. No unrelated working draft is accepted.

## Result

No concrete scoped finding remains in the seven added test methods or their fixture composition.

The runtime's cold `settleSystemLocationBeforePlanLocked` reuse path is exercised with an exact row,
plan fingerprint and backend identity. Its replacement path cannot reserve/start the active provider
until exact removal and durable acknowledgement complete. The removal-failure and acknowledgement-
failure cases keep the original retiring row/token and require retry of that identity; the fixture
distinguishes database state, pending retirement and the local backend handle.

The passive → active → passive case exercises the real runtime retirement/reconfigure path, verifies
one active start/stop and a new exact passive identity, and preserves the default capture fixture
behavior. The shared temporary-request cases use the actual controller plus runtime: cold system
ownership without retirement authority blocks UI takeover; map/game requests share the physical
request, discard the replaced callback token, release independently, and neither reserve durable
registrations nor deliver to the durable sink. Existing controller mutex and runtime callback fences
are used; the tests do not fabricate a second physical owner or recording consent.

## Evidence limits

All seven cases are **NOT_RUN**. Registration storage, provider transports/removal and durable
acknowledgements remain mocked seams. This is static acceptance of test-source coherence only:
it establishes no compilation, Room integration, Android PendingIntent/permission behavior, real
provider execution, device battery behavior, full-chain completion, or integration readiness.
No gates, providers, Git commands or recursive delegation were performed.

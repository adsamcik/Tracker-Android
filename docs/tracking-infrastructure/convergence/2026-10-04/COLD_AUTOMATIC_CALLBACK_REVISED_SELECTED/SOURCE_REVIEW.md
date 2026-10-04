# Independent source review: cold automatic callback

**Accepted for the narrow A3 source selection — IMPLEMENTED_UNVALIDATED.**

Reviewed the inherited original user messages and approvals, current tracking-architecture priority, prior automatic-owner review, AUTOMATIC_ACCEPTED_STEPS_TRACE_INPUT A1–A3 findings, original and revised two-path BASE/SELECTED snapshots, complete selection patch, eleven composed test methods, and nine exact committed dependency snapshots. No production/test source, Git state or provider was changed; no compilation, tests, quality/schema gates or device work was executed.

## Exact input

| Artifact | SHA-256 |
| --- | --- |
| FILES.json | 6c27212fdbcba942c6cab725a9e5d5a8ce1a6b5bdb51e5448698aec9a6ac5c96 |
| SELECTION.patch | 8823982a7f3719e5499285d9529094dbd1c3546ce8cc401571f6d44f34807ded |
| SOURCE_BOUNDARY.md | fdaa5161438897f9b8dd8d396fea345879ac94c524726ea3d39e6cfe9e6a5a83 |
| DEPENDENCIES.json | 869cc9ef3b8f1ff2942191763097655643140729567e2bbf8f7064a7af5f21f6 |
| BackgroundTrackingApi SELECTED | 1b500c2c83dc65b767594382f923532c323230a25a83844d88989c5c87c9c8ba |
| ColdActivityAutomationOutboxTest SELECTED | 4b15eb3402a83d515e20feaab66d454e41380c9952ee7e3bf1c6a2c4fc3b07bc |

Both selected sources and all nine dependency snapshot pins match. Both selected files and the patch are byte-identical to the original packet. Production BASE/WORKING_BEFORE match the reviewed 39718842674c35a5052c696c1a28d2c1f73c9b05 Background API, SHA 64cc949eb93acb416eb0749685b4e3e9a3b62bc88a4b72604a1f83e1b94cad5e.

Dependencies are exact committed HEAD879ee7f5d8ea06effa7da1002f600edb9d2b2db0 exports. The five initial dependency SHA differences were independently confirmed as line-ending differences: each original hash matched its working-byte input, whose normalized content equals the committed export. No held declaration or behavioral companion is required. The original packet remains preserved.

## Source result

A3 is repaired within the stated scope. BackgroundTrackingApi.kt:322–340 checks coherent cached settings/policy and matching available current source-authority revision before interpreting operational control. A valid first cold callback therefore asks for the existing RETRY while these collectors are still loading; it cannot be terminalized solely by the API's initial control cache.

The handler then consumes the source-owned current publication directly, bypassing a lagging API availability collector. Existing retryable registration/provider/retention-projection classifications remain retryable. The committed CurrentTrackingPurposeAvailability projection converts a stale Ready whose full authority vector no longer matches into CONTROL_RETENTION_POLICY_UNAVAILABLE; that remains retryable here. Explicit current permission/transition/authority failures retain their existing terminal classification.

Ready still needs the existing exact suspend isCurrent check. Current policy and control consent must match that Ready identity; effect consent and automation epoch must match current authority. A construction/read exception returns RETRY, cancellation propagates, and an exact authoritative false does not enqueue. No cached Ready, consent, unconditional Ready or recorded Activity is substituted.

The captured settings snapshot feeds the existing supported capture/foreground plan. The Steps-only fixture disables Location and Activity capture while CONTROL remains independently authorized. The existing downstream current action/service validations remain; this slice adds no registration, persistence permission, provider, schema, lock, timer or framework.

## Test-source result and limits

The eleven methods compose the actual outbox dispatcher, actual BackgroundTrackingActivityAutomationEffectConsumer and selected Background API. Fixture signatures and invoked branches are present in the committed dependencies. The action mock is only carried through Reserved/Authorized/Valid wrappers on the positive branch; this branch does not require unstubbed entity property reads.

Cases distinguish cold startup retry, matching source Ready despite API collector lag, unavailable/newer revision, pending classifications, known terminal failures, reader failure/cancellation, failed exact current check, consent/automation changes, original callback expiry and mismatched settings/policy arrival. The original callback context derives from its unchanged observed/received deadline; retry does not manufacture a new foreground-start exemption.

The positive assertion observes only controlled Android enqueue and correctly leaves the outbox pending. Source authority, validator, action storage, hardware and enqueue are mocked; no real policy/Room reservation, lifecycle acceptance, physical Steps collection or saved product is proven. Singleton shutdown and object-mock cleanup delimit each fixture. All eleven cases remain **NOT_RUN**.

## Remaining boundary

No concrete correctness, scope or unnecessary-complexity blocker remains in this two-path A3 slice after source inspection. This is narrow source acceptance only.

A1 fresh-callback reservation mutex conflict and A2 service/Room acceptance mutex conflict remain unresolved and explicitly excluded; their design decisions are unanswered. Held manual acceptance drafts are excluded. No automatic full-chain completion, assembly gate, integrated dev/v10 readiness or device reliability is claimed. All 25 full-chain prerequisites and deferred convergence validation remain pending.


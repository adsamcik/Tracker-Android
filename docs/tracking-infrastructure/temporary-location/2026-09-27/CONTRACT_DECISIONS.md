# Temporary Location: concrete decisions awaiting confirmation

2026-09-27. The shared-owner, independently scoped, nonpersisting feature goal is settled.
`RUNTIME_GAP_CLOSURE.md` section 6 records that goal and proposed freshness floors;
`WHOLE_PRODUCT_POLISH_PLAN.md` and `IMPLEMENTATION_PLAN.md` require the concrete pre-edit
contract to be confirmed. User AGENTS.md requires approval of new architecture/behavior/data
decisions. Two focused question-tool prompts were sent. No answer has been received or inferred.

## Question 1: shared owner and freshness

Keep `UiLocationProvider` as the feature-facing interface, with temporary typed leases carrying
consumer, actual lifecycle/host identity, request and observed-time proof. Map authenticates its
foreground lifecycle; games authenticate their existing location FGS. Combine their requests
on the same Location owner as durable capture/background demand. Feature-only fixes remain
in memory; writes require independent current durable capture/background authority.

Proposed map request: existing 2-second cadence, maximum observed age 10 seconds.
Proposed game requests: retain existing cadences and accuracy limits, maximum observed age
`min(2 * interval, 5 seconds)`. Current cadences are 1 second for Outrun/FuseRun, 2 seconds for
Territory/Switchback, and 5 seconds for ZenWalk; existing accuracy limits are 30m except
Switchback at 25m. Require current-boot observed monotonic age rather than receipt time.

Cancellation or host loss releases only that lease. Failed physical removal blocks replacement.
No new permissions, provider/writer activation, persistent feature observation store, game score
redesign or tracker user-stop acknowledgment is implied.

Question options: approve this contract; or approve shared owner and specify different freshness
limits. Dependent contract implementation remains unapplied until confirmation.

## Question 2: map and sunrise lifetime

Current map acquisition happens on opening a visible map. Current sunrise/sunset initializes a
separate permanent passive provider and retains coordinates in preferences.

Recommendation: acquire the map lease only after explicit My location/follow use, and let
sunrise/sunset reuse an in-memory shared fix without creating Location demand. This prevents
history browsing and sunrise calculation from silently activating another Location provider.
The final implementation must keep feature-only observations out of durable tracking and radio
enrichment; any sunrise persistence change must be covered by the approved cache-only behavior.

Question options: explicit map use plus cache-only sunrise; preserve visible-map acquisition plus
cache-only sunrise; or preserve both current behaviors for now. Dependent behavior is unapplied.

## Already-authorized bounded work

Valid `(0,0)`, unknown accuracy and actual game timestamp corrections implement approved honest
data requirements without selecting either pending contract. They are source-reviewed only;
full temporary Location unification and the assembly gate remain open.

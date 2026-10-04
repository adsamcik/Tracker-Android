# Prepared manual Start: current all-day owner repair

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** No compilation, tests, gates,
provider activation, device work or integration. All full-chain prerequisites remain open.

Base `8bbb29490c0488d26b25ffec04a7977d1482bd9b`. Two clean-at-start source paths only.
`FILES.json` pins the actual before/after bytes; the patch is a source-review aid.

The already approved original manual Start may return after Android grants motion
permission without another foreground lifecycle event. Its cached policy can be complete
while the independently selected all-day Steps owner remains inactive. After a successful
exact manual Prepare, reuse the current-settings reconciler and existing retry scheduler.
The reconciler still reads current choices, actual permission and user-stop authority;
the old Start selection does not itself authorize all-day collection or resume Off.
Automatic/recovery/rejected requests gain no new manual refresh opportunity.

Existing independent-owner failures remain retryable and do not reject a prepared session.
Cancellation during the new refresh/retry suspension compensates the exact prepared token
through the existing public coordinator operation and bounded cancellation cleanup helper.
Original cancellation escapes even when compensation fails; existing startup recovery
retains responsibility for an unsettled exact PREPARED state. If this Start resumed an
earlier Off, reuse the existing cancellation restoration (also used before Prepare)
to await the Turn off control with this Start's expected generation, including after
compensation failure. A timeout before its durable reservation could leave the resume
active, so no new outer timeout truncates that reservation. The control cannot overwrite
a newer user command. Its existing physical-stop cleanup reserve still applies; the
reservation wait itself has no claimed global 250 ms bound.
No new timer, storage,
interface, permission UI or provider owner is introduced.

Eleven new unexecuted test methods cover thirteen scenarios: original Start without Off,
later Off, explicit owner debt, owner storage failure, cancellation in refresh and retry,
compensation failure, cancellation after resume with successful/failed compensation,
awaited Off reservation beyond the prepared-state cleanup timeout,
nonmanual, recovery and rejected requests. Their controlled startup
lease supplies successful preparation rather than executing Room Prepare. The cancellation
fixtures execute the actual public compensation branch with mocked storage; the resumed
cases mock the existing Turn off endpoint and check its expected generation. Existing
real lifecycle-authority test source separately covers refusal after a newer command.
They do not
prove Room, Hilt, Android permission delivery or provider execution. Independent static
review is required before selection; whole original-Start/device behavior remains open.

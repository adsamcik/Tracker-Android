# Pending background Steps scheduling decision

2026-09-27. **PROPOSED_NOT_IMPLEMENTED — confirmation pending.**

User AGENTS.md requires confirming new/changed design decisions. The approved requirement is
supported system-provider background import with bounded recovery and honest coverage. Its
specific scheduling cadence and bounds are not yet settled.

The question tool presented:

> AGENTS.md requires confirmation of the new background scheduling behavior. Approve one Steps import WorkManager chain: an inexact 15-minute opportunity plus an immediate pass after enable/startup, each pass capped at four cursor operations and 30 seconds, with bounded retries and no new sensor/foreground service? Current consent, supported provider, background-read permission and suppression gates would control each execution; deduplication proof requirements remain strict.

Options: "Approve 15-minute bounded scheduling"; "Use hourly scheduling to reduce wakeups".
No response has been received or inferred. Dependent implementation remains unapplied.

## Concrete meaning and trade-off

Use existing WorkManager/Hilt infrastructure and one serialized Steps execution owner, with unique
periodic and immediate work opportunities. WorkManager periodic work cannot be appended to a
one-time chain; the phrase "one chain" describes exclusive execution ownership, not that API.
Existing registration ownership, authority and cursor compare-and-set remain authoritative.

Fifteen minutes offers earlier import opportunities at greater wakeup cost; hourly reduces
opportunities/wakeups and delays visible aggregate catch-up. Neither cadence guarantees delivery
under Doze, quotas, background restrictions or user stop. These are provider imports rather than
new active tracking requests.

Each execution must resolve current support and permissions, respect BACKGROUND_ALLOWED versus
FOREGROUND_ONLY, and revalidate immutable registration, purpose consent, retention, deletion and
startup suppression. Do not borrow read access from an unrelated active session, switch provider
on denial, reset retry allowances through reconfiguration, fabricate missing counts, or create a
second canonical writer. Foreground-only capability needs a lawful foreground opportunity; a
periodic worker itself supplies none.

Recovery must serialize bounded old-provider drain before acknowledged retirement; use existing
durable state/retry ownership rather than a new recovery framework. Exact retry count/backoff and
provider operation sub-deadlines must be concrete and reviewable before their implementation, not
implicitly chosen from the phrase "bounded retries". The proposed 30-second pass bound cannot
justify abandoning an unacknowledged platform registration operation.

No new scheduler, worker, numerical deadline, permission contract, schema, activation or dependency
has been applied in this checkpoint. Existing unrelated pending decisions remain pending.

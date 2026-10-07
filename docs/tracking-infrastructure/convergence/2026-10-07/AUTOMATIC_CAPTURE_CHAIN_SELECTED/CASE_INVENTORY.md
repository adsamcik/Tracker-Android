# Authored cases (NOT_RUN)

1. `first delivered pre End transition cannot restart even with the current callback generation`
   The factual transition predates End, but its first delivery captures the current post-End command
   generation. Actual Activity admission remains durable and CONTROL-only. The actual action reaches
   the real public Start API and durable observed-time reservation fence, becomes terminal, and creates
   no prepared session/Android enqueue/Steps product. This would not pass merely by comparing an old
   callback generation: the selected generation is intentionally current.
2. `post End transition reaches real prepared accepted Steps capture and End retires that owner`
   A genuinely newer transition must reserve a new command, persist its prepared envelope and action,
   stage only Steps capture plus nonrecording Activity continuation, then pass real claim/foreground
   Room acknowledgement/command acceptance/apply. Two controlled counter callbacks must produce a
   saved native delta of five attributed to the exact run. End must finalize and remove that real
   Steps listener, ignore a late callback and preserve both its saved product and independent automatic
   CONTROL demand. The outbox replay cannot enqueue another Start.
3. `Off fences a late transition and preserves the independently saved Steps product`
   The same successful production start/product path precedes actual Off reservation/Room fencing
   and Steps teardown. A later Activity callback must be rejected before further WAL/enqueue mutation,
   the Off latch must remain durable and the previously saved native five-step product remains.

No test is run, compiled or claimed passing. These are composition fixtures, not physical-device proof.

# Session bundle import caller and publication foundation

**IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY**, 2026-09-30. Static source
inspection and authored tests only. No compilation, tests, schema generation,
quality gates, provider activation or integration ran.

## Selected behavior

- The existing worker accepts `.trackersession`, copies it once into bounded
  owned staging, and uses that copy for both its existing job hash and dispatch.
- Children keep their existing source transactions and durable receipts. Metadata
  preparation retains at most 4,096 exact identity claims outside its Room
  transaction; publication resolves source receipts, checks the original data
  epoch and saves one annotation with its existing generic success receipt.
- Completed metadata skips preparation on retry, preserving later user edits or
  removal. Missing source membership fails publication. Named malformed-format
  errors are permanent; ordinary I/O remains retryable with saved children intact.
- Existing job hashing accepts the original source extension for staged Location
  bytes, handles a zero-length read and checks cooperative cancellation before
  and after reads. Its stream closes when cancellation propagates.
- Wi-Fi and Cell registry rows select only their committed import adapters in
  this foundation. Their approved export implementations are still required and
  retained as separate drafts. Location uses its existing committed importer and
  exporter. The three existing source labels are selected in English and Czech.

The selection patch excludes Location conflict-review UI/worker drafts, radio
exporter factories, backup changes and unrelated transfer resources. Those
working files remain intact. Existing source codecs, adapters, typed receipt-key
helpers, original-epoch handling and ZIP limits are dependencies already selected
in earlier source commits.

## Authored evidence and independent review

`SessionBundleMetadataRoomTest` contains actual Room/staged Location scenarios
for duplicate exact membership, partial failure/retry, metadata atomicity,
original epoch, missing receipts, format mismatch, cancellation, expiration and
transient preparation I/O. Its Wi-Fi success fixture inserts the source receipt
at a test seam; it is not a real radio-adapter success chain. The missing-receipt
matrix proves refusal in source, not successful full membership for every source.

`ImportWorkerFailureTest` adds the wrapper size, original staged Location hash,
zero-length read and cancellation/stream-close regression sources. No authored
scenario has been compiled or executed. The fresh review receipt preserves prior
findings and records static closure of preparation I/O and registry dependencies.

## Still required

- Exact file association for an identical native Steps product, and overlapping
  imported background radio facts/gaps; their proposed storage decisions await
  the user's answers. No date fallback or copied native product is introduced.
- Nested Location conflict review and imported annotation label clearing.
- Bundle export caller, source choices and product/history integration.
- Complete source success chains, convergence gates and device evidence.

This commit is a coherent source foundation, not full bundle completion, an
assembly freeze, a test pass or readiness to integrate into `dev/v10`.

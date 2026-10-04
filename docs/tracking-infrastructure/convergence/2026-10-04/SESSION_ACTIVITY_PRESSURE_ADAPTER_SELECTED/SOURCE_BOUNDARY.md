# Exact Activity/Pressure file adapters — source freeze

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Production/test sources and this
selection packet are authored for fresh independent static review. No compiler,
tests, lint, schema/drift, diff-check, device/provider activation, CI, Git or
integration operation was performed by the author. No passing/runtime claim is made.

## Selection and authority

Root confirmed the four exact assigned paths clean at HEAD
`1c127f215a0e0d1bf9468a21c2f19dd30b2e9fee`. Original actual bytes are preserved as
`.before`; selected working bytes are `.after`. `FILES.json` pins their SHA-256
values and the LF review patch. Snapshots preserve physical original bytes rather
than claiming Git object byte identity across checkout line-ending conversion.

Original user requirements and corrections are inherited. Approved behavior is
one shared exact-session preview/write entry point; session detail defaults all
actually available saved sources On with exclusions, name/activity in one bundle;
existing typed files, original epoch, partial-failure/retry receipts; stop when
selected data/organization changes, no tracking acquisition. Exact source-reader
and file adapter extensions are prerequisites for that approved behavior.

Only `PortableActivityExporter.kt`, `PortablePressureExporter.kt` and their two
existing tests are selected. No schema, dependency, format, Hilt binding, source
reader, registry, constructor, UI or new public framework is added. Existing Keep
annotations, reflected no-argument construction and Pressure warning remain intact.

## Production trace

1. Each internal `exportSession` accepts the committed source-owned exact request:
   native segment seeds, imported opaque entry identities and the original data
   epoch. Caller lists are copied once. Every read uses the same fixed request;
   neither a date fallback nor adoption of the current epoch is possible.
2. Existing exact Room readers remain authoritative for native complete original
   replacement groups, exact imported identities, retained payload, corrections,
   original closure/bounds and epoch. These overloads are already read-only and
   recheck selected typed contents around emission. No qualification is copied.
3. The existing codec writes canonical selected data into its existing bounded
   private spool. The same codec closure re-encodes selected data to a discard/hash
   stream immediately before and after copying. Successful count and canonical
   SHA-256 must match; unrelated global/source revisions are not inputs. No new
   proof, signature, origin permission or durable identity is created.
4. Failed pre-copy selection leaves the caller destination empty. A post-copy
   failure requires the enclosing caller to discard its destination. Existing
   Activity StorageUnavailable/Unverifiable and Pressure RetryableFailure outcomes
   are reused; no new result vocabulary/copy is added. Codec failure handling keeps
   each adapter's previous behavior. Cancellation escapes after existing spool
   `use` cleanup. Destination streams remain owned by the caller.
5. Ordinary date exports keep direct codec writing and a single source read. Only
   existing result mapping/Activity exception handling is shared privately with
   exact export. Ordinary date-selection semantics, call sites and metadata remain.

## Caller and remaining boundary

These methods are prerequisites with no exposed partial session-bundle action.
The shared facade must run on the injected I/O dispatcher inside existing
`TrackingStartupGate.withReadyGenerationTransfer`, own staging/output closure and
cleanup, re-read exact organization/current selected children after composing and
copying the complete bundle, and use the short `withRetainedDataGeneration` final
handoff. Activity/Pressure child rechecks do not cover a later full-bundle copy.
Collection Off leaves retained-history export usable; Clear cancels and physically
drains the transfer. Control locks must not surround I/O.

Radio sparse representation/overlapping membership, duplicate Steps bundle receipt
membership, background Steps exact-day selection, retrospective measured subsets,
shared port/facade/UI, held History/radio/backup drafts, manual Start and runtime
activation are excluded. The complete assembly and all convergence gates remain open.

## Authored, unexecuted scenarios

Twelve Activity and thirteen Pressure methods use exact API fakes plus the actual
codecs, existing private spool and destination copying. They cover native/imported
ID/epoch propagation, preserved gaps/runs/bounds, caller-list immutability,
pre-copy correction, copy-time edit, unrelated writes, stale epoch/copy-time Clear,
empty/unavailable/storage outcomes, source cancellation, actual child-job copy
cancellation, post-emission refusal and destination I/O failure. Pressure also
uses its existing lowered codec-size test seam. `FILES.json` enumerates the cases.

All six previous date/format methods remain. No helper fixture, module or dependency
was changed. API-fake adapter cases are not Room qualification or physical-device
evidence; the separately committed source-reader Room cases remain unexecuted too.

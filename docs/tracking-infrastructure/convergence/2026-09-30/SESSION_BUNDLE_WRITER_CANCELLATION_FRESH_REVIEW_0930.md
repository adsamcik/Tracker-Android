# Independent bundle writer cancellation review — 2026-09-30

Status: `IMPLEMENTED_UNVALIDATED`. Independent static source inspection only. No production
changes, Git operations, compilation, tests, provider/device activity or integration.

## Exact reviewed input

Compared current source with the root-saved HEAD baselines and exact diff in
`bundle-writer-cancellation-selection/`; this reviewer did not use Git. The saved diff contains
only the writer's cancellation/declared-size companion and two focused test additions.

The root saved these helper inputs from `321dc35db0` before selecting the native
candidate foundation; the writer/test Git blobs are unchanged by `5baa8f2060`.
Helper snapshots remain preserved local audit inputs, outside the selected source
commit. The two production/test files and this receipt are the selected slice.

SHA-256 pins:

- Current `SessionBundleArchiveWriter.kt`:
  `b2732966e2618af156e9a54b4ca9e77bad8d0f1d04ac02e89a20e9787a81141c`.
- Current `SessionBundleArchiveWriterTest.kt`:
  `db99dd0a1cd78dbaed840a8f6a81cd7324cc9cc572cbf3055d807a42bfc07b70`.
- Saved `WRITER_BASE.kt`:
  `839bdfb21bd0b54fa99f06373cc1d748d596f0f37bdf2ede9d42e7f3c27a5cb5`.
- Saved `TEST_BASE.kt`:
  `a33ce518cb9d92d216b59a4b7ff66e8f4a95e8991bb5aededd9c40aff62aa40e`.
- Saved `REVIEW.patch`:
  `70dc1952ea705697564a4f18f2df824225cb92ea7ad028e88210a81b0040d298`.

## Static findings

No concrete production defect was found in this narrow writer/test companion.

The caller context is captured and checked before metadata work, at each child preflight,
before/after every checksum or ZIP input read, after each ZIP data write, at manifest/ZIP
completion, and before normal return. The private checksum helper now takes the declared
byte bound and rejects excess before digest/CRC processing; zero-length reads are skipped.
Given the existing manifest child bounds, `count += read` cannot reach integer overflow before
that check. Saved file sizes, double checksum verification, declared member set, STORED ZIP
method, format, spool policy and destination ownership are unchanged.

Cancellation is not caught as a format error. The source/ZIP/private spool `use` scopes close
their owned streams while unwinding; a close-time ZIP error is suppressed onto the primary
cancellation rather than replacing it. The completed-stage/copy boundary relies on the existing
spool cancellation companion already reviewed separately. A cancelled staging operation never
copies its incomplete ZIP to the chosen output. No new public API, storage, UX or framework is
introduced; the helper signature change is private.

## Authored evidence and boundaries

- `SessionBundleArchiveWriterTest.kt:94-117` cancels the actual child job before writer entry
  and requires no child inspection, no output bytes and no spool directory creation.
- `:121-155` uses the existing `File` path seam: the first open successfully prehashes the
  original child; the second cancels at ZIP source opening and returns an empty source. It
  requires cancellation rather than the later size error, exactly two opens, no normal return,
  no chosen-output bytes and removal of the staged private file.
- Existing exact-member/bytes, changed-child rejection and repetitive-source STORED ZIP cases
  remain unchanged. The new checksum-growth early-stop guard is statically inspected; these
  tests do not claim a directly measured read budget for a file growing during hashing.

All tests were inspected, not compiled or executed. The narrow source selection is coherent
for an implementation-only local commit. This closes the missing cooperative bundle prehash
and ZIP-loop checkpoints recorded in `EXPORT_SPOOL_CANCELLATION_FRESH_REVIEW_0930.md` by
static trace only. It does not prove interruption of a blocking filesystem/destination call.
Shared transfer admission, lifecycle/epoch fencing, cancellation and physical draining before
cleanup remain open, as do full bundle/export product behavior, convergence and integration.

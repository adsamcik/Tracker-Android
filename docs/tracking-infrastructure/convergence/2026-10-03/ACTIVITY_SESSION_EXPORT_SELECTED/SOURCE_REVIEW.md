# Exact Activity export — independent source review, 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Review by
`/root/fresh_bundle_context_review_0930`. Original user requirements/approved
answers, candidate `AGENTS.md`, all five selected deltas and their actual
production/fixture dependencies were read. Packet provenance is the parent-supplied
base `2d01ed0ee411bf2bc48d4c45fe070de6b945f102`. No Git, compiler, test, schema,
device, provider, CI or integration operation was performed.

## Initial finding A1 — selected payload accumulation lacks whole-file counts

**Open in the initial freeze.** `ActivityCapturedPortableTransfer.kt:1071–1090`
collects every exact imported evaluation before combining it. The existing
combiner at lines 1191–1194 checks total entries/runs but not total zone epochs,
windows or fragments; the envelope constructor also checks only its entry count.
Those other existing whole-file caps are enforced later by
`PortableActivityJsonV1Codec.validateEnvelopeLimits`, after materialization and
ownership/checksum construction.

For example, five separately valid imported entries with 16,384 current windows
each exceed `MAX_WINDOWS = 65,536`. The source can retain and emit all five before
the codec refuses them; larger exact requests can accumulate millions of objects.
This violates the intended bounded source prerequisite even though the downstream
codec rejects the artifact. Reuse the existing whole-file caps while collecting
selected contents, before accumulating another oversized result or constructing
its ownership/checksum. Preserve exact content deduplication and identity-conflict
refusal. No new store, framework or public API is required. Bounding unique native
logical entries before their audits is the corresponding early entry-count check.

## Remaining source trace

- The new request uses original native segment seeds, imported opaque entry IDs
  and the original collected-data epoch. The exporter pins the requested lists;
  there is no date neighbor or implicit native counterpart fallback.
- Native seeds require reciprocal segment/run/logical identity. Only those logical
  IDs expand through the existing bounded replacement-run keyset. Every expanded
  segment/run binding is checked. Existing scoped `auditCapturedActivityFacts`
  queries include either side of a selected tuple, then require exact membership,
  writer version, correction lineage, fragment/evidence footprint and cursor head.
  Its unselected global payload checks are bypassed only for this explicitly
  scoped audit. Unrelated malformed facts cannot grant or revoke selected authority.
- Existing manifests, logical revision union, terminal projection settlement,
  provider retirement/closing authorization, collected-data epoch, retention and
  deletion checks remain source-owned. Not-captured replacement members and real
  saved gaps remain part of the original logical group; no measured interval is
  invented. Cumulative native audit budgets remain explicit.
- Imported IDs use `ImportedActivityProductReader.selectIdentityInTransaction`
  only. Missing rows fail; latest corrections, deletion and retention checks use
  existing independent imported authority. The merger deduplicates identical
  explicitly selected copies and refuses conflicting ownership/content.
- The exact Room snapshot completes before external sink IO. A second read of the
  same selected typed contents checks epoch and content after the sink; unrelated
  evidence revision updates are not dependencies. Non-success requires the future
  transfer caller to discard staged bytes. Cancellation propagates. No provider
  demand or durable state is created.
- The existing Hilt provider already constructs the production implementation.
  The only explicit interface fake supplies the new overload and rejects accidental
  exact selection from standalone date export. The ordinary range path retains its
  prior selection semantics; extracting the existing merger adds no fallback.

Read **16 new unexecuted methods**: one request contract case, nine native Room
cases and six imported Room cases. They cover original replacements from a
factless sibling seed, correction/gap preservation, reciprocal/legacy/missing
seeds, read bounds, unrelated invalid facts/revisions, selected cursor loss,
cancellation outside Room, exact imported selection, deletion, retention,
correction during copy and original epoch. One existing standalone fake is
updated. Authored source is not evidence of compilation or passing tests.

The shared preview/write caller, UI, organization metadata, final destination
handoff and pending overlapping-file membership remain excluded. This review
closes no full assembly chain. Selection readiness is withheld until A1 is repaired
and the exact changed inputs are independently rechecked.

## Initial input pins

All raw actual hashes matched the initial manifest. The four test files differ
from its selected hashes only in CRLF/LF encoding; their LF-normalized hashes
matched the selected values.

| Input | Selected SHA-256 | Actual SHA-256 |
| --- | --- | --- |
| `ActivityCapturedPortableTransfer.kt` | `1c55e23b38cd629d36ad436d16b9e4e14e330f39a8295bd6b80d1e8cb9824604` | same |
| `ActivityCapturedPortableFormatV1Test.kt` | `c395266506fa5835c5f012aaffb66acad3012cf9d8c0e5179c6f380920a05a55` | `e4a5f5a2d01b1511bf85f019bcc6791ec5154aeae4afc836031fab7f79ff5ecf` |
| `ActivityCapturedFactMaintenanceTest.kt` | `7bc418b863587fc4000e32613dfbfe2b99acdbe06eec3b9900914db2275884f0` | `3425043ab589da185ba20c82e23124c56a03f557f9f077efbcee268a565736a7` |
| `RoomImportPortableCapturedActivityTest.kt` | `5a198b15c32e8ce442e5130d0b71e2be5cfe645cc3befa1f048e30c26b3ae86a` | `34ab0b8c6c2a566734ad2bc91cb3bca21e4ec2a237417ea8e4091d0a798784fe` |
| `PortableActivityExporterTest.kt` | `9df623fcb1a42a6d0908b3429c613497b646b694e60ffe0917d575701bb735a6` | `84b25a4b969d2decad6af50bf290fc3a9ed465e2441c2bbfe41678e0a229025f` |

The manifest supplies full paths and immutable base hashes.
Manifest: `8bebd42c902f52353cee84d53ed298222688adcf3c626c49106bec75576edd77`.
Patch: `2a5d8886c877e3eb0625e4bbe2ae71c03e8bc7565b97b4a3df2f9a06a32dc392`.
Boundary: `bd5b6039769eb95d48c83df6523269199cc6f7d886f75efd9a3cda0e87b45a82`.

## A1 repair recheck — 2026-10-04

**A1 CLOSED by static source review.** The exact reader now checks distinct native logical entry
count before scoped native audit (`ActivityCapturedPortableTransfer.kt:1050–1052`), constructs
one private `EntrySelection`, and qualifies/includes imported identities one at a time
(1072–1090). The accumulator counts only new, nonconflicting typed entries. It checks existing
entry, run, window, fragment and zone-epoch caps using Long counts before expanding that entry's
opaque ownership or retaining its hierarchy (1198–1222). Exact same-content copies count once;
shared identity with different contents refuses. The final checksum is constructed only from the
bounded, qualified selected entries. The ordinary range merger reuses this same small collector;
its source/date discovery semantics are unchanged. No new store, schema, framework or public
format was introduced.

Read the three additional actual Room method sources and their real importer/fixture seams:
`RoomImportPortableCapturedActivityTest.kt:2591` constructs/imports four legal entries with
16,384 windows each, accepts exactly 65,536 windows, then requires dependency overflow without
sink output when one further window precedes a missing identity. The distinct missing-identity
failure would differ if collection reached it. `ActivityCapturedFactMaintenanceTest.kt:451`
requires identical explicitly selected native/imported contents to fit the existing one-entry,
one-run limits; line 467 requires a divergent shared ID to remain a conflict. Existing scope,
epoch, content-reread, correction, cancellation and date-routing cases remain intact. The final
packet contains **19 authored, unexecuted cases** (one contract, eleven native Room, seven
imported Room) and one explicit existing fake companion. No performance or passing-test claim
is made from those sources.

Independently rehashed all five current actual files. All match the repaired manifest; the four
test files' LF-normalized contents also match its exact selected hashes. The final pins are:

| Input | Selected SHA-256 | Actual SHA-256 |
| --- | --- | --- |
| `ActivityCapturedPortableTransfer.kt` | `98deb5f3b6c5cf3e296b4174b26766e8b4450b1aa96714b02a5cd02562a9be49` | same |
| `ActivityCapturedPortableFormatV1Test.kt` | `c395266506fa5835c5f012aaffb66acad3012cf9d8c0e5179c6f380920a05a55` | `e4a5f5a2d01b1511bf85f019bcc6791ec5154aeae4afc836031fab7f79ff5ecf` |
| `ActivityCapturedFactMaintenanceTest.kt` | `86b2ec573c0dfc96494ff2e1b6c15479cbbafbcfd50b7ed798789a1866130f0c` | `ed0fadc42b1902e7a63bee98803f9e481e11bfc5b35557de437f0975c33230da` |
| `RoomImportPortableCapturedActivityTest.kt` | `53b481a41429246cd58bd7c1d8880ee70388d0ba2151727160d2c96cafb5ae4c` | `2832259713edfd35d23fb635e2467afb4e9581ebc5796983a2c59fe8ebd40a4c` |
| `PortableActivityExporterTest.kt` | `9df623fcb1a42a6d0908b3429c613497b646b694e60ffe0917d575701bb735a6` | `84b25a4b969d2decad6af50bf290fc3a9ed465e2441c2bbfe41678e0a229025f` |

- Manifest: `5a354a53ecf9398718a13021c4aad4df9e2c7fb6b590afbe753b5140b6fee78c`
- Selection patch: `7567b9f4e901971d70ef9c9a51a8a25e2e595e5f481b58f5ec177958a3b287a0`
- Boundary: `87fd2626a141c71aa453fddcf98dc3dfee015d1b505d6cc927ffc505ffa77ef7`

No remaining actionable defect was found in this exact frozen prerequisite. It is coherent for
an **IMPLEMENTED_UNVALIDATED source commit**, with root owning selection. Actual shared-session
caller, retrospective/date selection, bundle membership, destination cleanup and all assembly
validation remain excluded; this closes no full-chain or device-readiness claim.

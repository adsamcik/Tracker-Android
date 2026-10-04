# Exact Activity / Pressure file adapters — independent source review

Date: 2026-10-04. Reviewer: `/root/fresh_bundle_context_review_0930`.
Status: **IMPLEMENTED_UNVALIDATED**. Phase: **IMPLEMENTATION_ONLY**.

## Result and selection

No concrete defect remains in the frozen four-path adapter selection. It is coherent
for a local unvalidated prerequisite commit, subject to the source-reader dependencies
already selected by the coordinator. This is not completion of session bundles,
the assembly gate, runtime verification or integration readiness.

Reviewed the actual four files, their exact before/after snapshots and patch, inherited
original user requests and decisions, existing exact Activity/Pressure requests and
Room export implementations, actual codecs, the existing private spool and unchanged
test fixtures. The supplied baseline is `1c127f215a0e0d1bf9468a21c2f19dd30b2e9fee`;
no Git command or validation gate was executed by this reviewer.

## Source evidence

- `PortableActivityExporter.kt:67–114` and `PortablePressureExporter.kt:73–117`
  copy the requested native segment IDs and imported entry identities once, preserving
  the original collected-data epoch. Exact export calls only the existing exact
  overload. It has no date fallback, new format, global revision dependency, tracking
  demand or implicit source inclusion.
- Both stage using the existing bounded `FileBackedExportSpool` and existing typed
  codec. They compare both the source entry count and SHA-256 of the same canonical
  codec bytes before and after destination copying. Corrections, changed bounds,
  membership or epoch cannot be reported as successful unchanged output; unrelated
  source writes are not checksum inputs. Existing storage/retry failure contracts
  remain failures.
- `ActivityCapturedPortableTransfer.kt:891–923` and
  `RoomExportPortablePressure.kt:53–83` preserve source-owned reads and rechecks.
  The exact Activity reader at `ActivityCapturedPortableTransfer.kt:1030–1095`
  resolves native seeds to original logical groups, requires exact entry coverage,
  selects imported identities explicitly, checks original epoch and retention
  settlement, and uses existing bounded selection. These adapters do not bypass
  those source contracts or reconstruct authority from dates.
- Existing codec file/hierarchy limits also apply to checksum rereads. The source
  fixtures retain Activity gaps and original bounds, and Pressure runs, windows and
  original values. Staging and copying use existing cooperative cancellation;
  source/destination cancellation and I/O exceptions escape only after the owned
  spool's synchronous `use` cleanup. The caller's destination remains open.
- Original date-export codec/source calls and result mappings remain unchanged.
  Public no-argument construction and the existing `@Keep` annotations remain
  available for registered exporter reflection. No dependency, schema or Hilt
  composition change was selected.

## Authored source coverage and limits

All six original test method bodies are unchanged: two Activity and four Pressure.
Twelve new Activity and thirteen new Pressure methods use exact API doubles with
the actual codecs, spool and output streams. They exercise immutable membership,
ID/epoch forwarding, preserved data, pre-copy and copy-time changes, unrelated
writes, stale/Clear epochs, source refusals, source/copy cancellation, destination
I/O cleanup and Pressure's existing lowered codec-size seam.

These **31 methods are authored and unexecuted**. They are not Room, Hilt,
device, UI or battery evidence; the previously authored source-reader Room cases
also remain unexecuted. Static fixture checks do not establish a passing test.

The future shared bundle caller still owns the I/O dispatcher, existing retained-data
transfer admission, organization/source selection, output closure and discard on
failure, rechecks of all selected children after whole-bundle output, and guarded
final handoff. The adapter can return failure after bytes have reached a slow
destination; the caller must discard that artifact. Retrospective subset formats,
background Steps selection, radio membership and all other held drafts are excluded.
All 25 assembly chains and final convergence gates remain open.

## Exact reviewed SHA-256 pins

| Input | SHA-256 |
| --- | --- |
| `FILES.json` | `5cd766235e38e1e591f68d3a30b279d363f6f89406162fc2e14e5c365b6c6f2a` |
| `SELECTION.patch` | `4d732e1434e7bb427af9310e6f003d1372da4c45b7defe9fb38f645371b313a6` |
| `SOURCE_BOUNDARY.md` | `cdf9f8c55d2608ea2b49629980ef7c2d977a2552a54556e52ec81dff76e4b575` |
| `PortableActivityExporter.kt` actual / after | `a6359a0ceec3d4f11737dc12815bb56c4268040b3bf470de11381f35ab4bf600` |
| `PortableActivityExporter.kt` before | `700d7e792012784cf0bbd569e68e8c0bee72d635284a81e1841e2a5ed2232454` |
| `PortablePressureExporter.kt` actual / after | `4c34f867067d82f0598f863a6014615ff7693bb90259e26e95b3eb2400aa01a5` |
| `PortablePressureExporter.kt` before | `1303dbb4adf45a0e51b1bee5dc6e04c02442af715e841fe7f4b4c6ca5ce8b66c` |
| `PortableActivityExporterTest.kt` actual / after | `4478a24aeb421c0c9690ba1e2da9dfbb5c747299cf9edb8100ed06f11d77085b` |
| `PortableActivityExporterTest.kt` before | `84b25a4b969d2decad6af50bf290fc3a9ed465e2441c2bbfe41678e0a229025f` |
| `PortablePressureExporterTest.kt` actual / after | `c61bd3f88fd0170bc1e1e7cfd1ce7ce97b45ca791b9e57ce853fbfdc8a06f262` |
| `PortablePressureExporterTest.kt` before | `46a005ba7b1557b541bc70fee2c55d9fe19da84fe7d6c2bac0eea8abd542cf99` |

Every actual selected file is byte-identical to its after snapshot. No held path
is covered by this result.

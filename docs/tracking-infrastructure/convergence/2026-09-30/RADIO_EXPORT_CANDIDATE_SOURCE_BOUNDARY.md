# Radio export candidate isolation

Phase: **IMPLEMENTED_UNVALIDATED**. Only source/test authorship and static inspection occurred.
No compilation, Room query execution, tests, validation gates, provider activation or Git mutation.

## Authored boundary

- Wi-Fi and Cell export now obtain at most 100 completed logical IDs per keyset page from their
  source-owned DAOs before calling the existing portable source qualification reader.
- Candidate source interest uses the exact logical ID's capture manifest membership, source facts
  bound through its service-run/logical/segment keys, or that source's completeness metadata.
- Exact run/segment ownership supplies conservative interval overlap, including a zero-length
  event at the inclusive beginning of the requested dates. Missing or malformed owner timing
  remains a candidate instead of silently becoming an outside-date recording.
- Fact clocks use the saved coverage span and uncertainty, with conservative partial-millisecond
  rounding. Invalid/overflowing clocks remain candidates, including a rounded span larger than
  observed wall time minus uncertainty (a rejected negative earliest bound). No scalar data is loaded as export
  payload or qualified by this discovery query.
- The existing qualified smart selector remains the final authority for whole-session inclusion,
  gap touch, the On/Off switch, outside-date counts and original identities. It is unchanged.
- Both exporters retain their enclosing Room snapshot, 100-ID ascending order and cancellation.

The exact path/hash snapshot and mixed-file hunk boundaries are in
`RADIO_EXPORT_CANDIDATE_FILES.json`. A hash of a mixed file is not approval to select its unrelated
drafts. Requirements-agent local/background child changes are separate.

## Authored test source

Four shared real Room query scenarios cover source/date isolation, active-session exclusion,
gap-only candidate runs, zero-length and exclusive-end boundaries, missing/reversed/malformed
ownership, and 205-key paging. Three tests in each existing Wi-Fi/Cell Room fixture cover unrelated
corrupt facts, uncertainty beyond headers, elapsed coverage and invalid clocks. A fourth test in
each source fixture now covers the fresh review's negative-earliest omission with an outside-date
owner: exact zero stays outside, while fractional rounding below zero and a whole-span negative
bound remain candidates for source refusal. Four real
exporter-to-Room routing tests use explicit typed source doubles for out-of-range exclusion,
relevant refusal and cancellation. They do not claim live provider or full source-reader evidence.

## Remaining boundary

SQL output and application qualification work are bounded; query cost is unvalidated. Existing
owner/scope indexes are reused, without a schema or index change. A conservative candidate can
still fail if it is unreadable and its time cannot safely rule it out.

Imported parity is not part of this slice. Its existing export candidate calls share a history
reader contract; a separately scoped export query route can use relational observation/gap bounds.
The existing Wi-Fi imported page continuation also passes header start/identity to a query ordered
by newest-member start/identity. That cursor mismatch remains pending. Neither issue is hidden by
the native candidate change.

## Selected foundation boundary

The coordinator selects only the two DAO discovery methods, four existing Wi-Fi
fixture cases, four existing Cell fixture cases and the new four-case core Room
query fixture. Their current diffs contain only this reviewed slice. The four
feature routing cases and both exporter call sites remain authored but held with
the unfinished exporter definitions. Thus 12 cases travel with this foundation;
the 16 authored cases are not all dependency-complete selected tests yet.

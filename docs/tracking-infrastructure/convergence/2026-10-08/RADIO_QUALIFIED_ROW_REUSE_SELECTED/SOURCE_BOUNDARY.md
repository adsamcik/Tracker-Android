# Qualified radio row reuse

Phase: IMPLEMENTED_UNVALIDATED. Tests NOT_RUN. No gates or provider activation.

The approved shared source-owned read now returns its already-qualified typed native rows alongside its existing composed result and named gaps. The optional map reader consumes those exact rows rather than loading the same raw window a second time. It still requires the composed Snapshot, and joins each native row to the already validated API fact by unchanged identity and semantic revision.

This is private factoring of existing read results. No schema, public API, provider demand, authorization rule, row limit, export format, map display, fallback or new persistence changes. All ordinary strict-result rules and child integrity checks remain in the same existing source repository.

Two real Room test sources cover complete Wi-Fi/Cell parent/child reads through the shared result and optional context, plus an unaccepted Wi-Fi row that cannot leak into context while Cell remains usable. The existing missing-detail sibling case also checks that optional context rejects the failed Wi-Fi detail composition while Cell survives. Other detail/retention/deletion cases remain intact. Test bodies are authored, not execution evidence. Query-count/performance, Room execution, cancellation and device behavior remain unvalidated.

No overall convergence prerequisite is closed by this source slice. Preserved working drafts are not silently selected.

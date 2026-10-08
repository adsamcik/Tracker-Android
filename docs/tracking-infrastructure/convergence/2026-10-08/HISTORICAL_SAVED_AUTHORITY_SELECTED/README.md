# Historical saved authority + held Location V2 convergence slice

Status: IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Every test is NOT_RUN.
Base: a8aea53bdf996100d8c388f9ddb499f8444814ac. Final dependency composition: fe03d75a74014adc1453c2c449d5770df3c1dd08. Original V1 input and metadata are preserved under REVIEW_V1.

The human explicitly approved “Keep valid saved readings after changes.” Saved readings use their original settings/permission and immutable recording bindings. Later quality changes, End and retry operation generations do not invalidate them. Off keeps saved history; erase, retention, original-record mismatch and late-callback fences remain.

43 selected paths: 27 production and 16 test/support paths. The combined input carries the existing 12-path SAVED_LOCATION_SUMMARY_SELECTED V2, including 23 already-authored methods; this repair adds 26 methods. All 49 methods newly authored relative to committed base remain NOT_RUN. Existing test methods are preserved, with explicit immutable Stop fixture setup added where the production reader now uses the original Stop record.

BASE is exact committed source normalized to LF. SELECTED is UTF-8 LF. WORKING_BEFORE is raw. LOCATION_REVIEW_V2 preserves the original overlay manifest/patch and exact SELECTED bytes before repair; a few carried source files had CRLF and are source-equivalent after normalization, not byte-identical. FILES.json gives exact final selected hashes; TEST_INVENTORY.json separates selected/new methods from base.

Root owns fresh review, exact selection, Git and final convergence. No live/shared source or protected paths were edited. No provider was activated. No build, compilation, tests, lint, schema, diff-check, device, battery, CI or integration was run. This is not assembly completion or T25 acceptance.

## Dependency composition refresh

The original dependency inventory explicitly flagged three nonmatching hashes; they are now pinned to the accepted committed Steps continuity input. SourceBrokerDao, AuthoritativeSessionCoordinator and DurableSourceIngress changed only through that accepted Steps slice. Initial Start/End Stop construction and checksum semantics used by the new coordinator fixture are unchanged. Three BASE files have exact LF-normalized equality with committed CRLF inputs; CURRENT_COMPOSITION.json records both hashes. All43 selected source bytes and FILES/SELECTION.patch remain unchanged. No zero-drift claim is made for the old pins. The later fe03 background radio hooks are outside this packet. All tests remain NOT_RUN.

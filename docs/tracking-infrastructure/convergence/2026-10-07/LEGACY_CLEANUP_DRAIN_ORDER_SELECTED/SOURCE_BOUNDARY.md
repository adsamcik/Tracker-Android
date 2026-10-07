# Legacy cleanup drain ordering

2026-10-07. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All cases NOT_RUN.**

## Routine repair to an existing contract

`SourceRunRetirementEntity` defines cleanup-only completion as retirement of the exact provisional provider generation with no product-completeness acknowledgement. `buildSourceProductDrainPlan` already rejects completeness owned by a cleanup-only generation, mismatched completeness/retirement sets, terminal Steps evidence that contradicts cleanup, and unverifiable orphan Steps facts. Its legacy/unattributed writer early-continue currently precedes those checks.

Select only the existing eleven-line target-resolution block moved below those existing validations. No new validation, authority, provider, storage, legacy writer, locking or public API contract is introduced. Clean legacy writers retain their empty/no-op target outcome. Existing raw malformed-row precedence remains before the moved block. Candidate source dependencies are pinned at `eebb97b0a82894f6f378ec9c25fff08f335d3af4`.

## Focused source authorship

Four new methods in the existing Robolectric/Room coordinator test reuse its original fake Steps runtime and real Room startup/cleanup receipt fixture. They invoke the actual `buildSourceProductDrainPlan` directly with fixture authority constructed from the exact saved legacy binding and retirement receipt. This intentionally reaches the reordered function rather than only the outer coordinator's earlier completeness checks.

Cases cover clean legacy no-op, valid-shaped completeness wrongly belonging to a cleanup provider, malformed raw completeness precedence, and an orphan Steps fact. Assert saved session/run/retirement/completeness data remain unchanged and no source product router is invoked. This is actual Room function test source with fixture authority; it is not device/provider retirement, full production authority construction, Hilt, or complete End proof.

## Preservation and exclusions

The selected production file is committed HEAD plus only the existing ordering move. The selected test is clean committed HEAD plus the four cases and two small private fixture helpers. Complete original mixed working bytes are archived under WORKING_BEFORE; WORKING_AFTER records only the new focused additions over the mixed test. All prior manual-Start, dispatcher/fixture, and other coordinator drafts remain excluded from the selected test. Live production bytes were unchanged because their only semantic draft already was this move.

No production expansion, separate test infrastructure, dependency, schema or migration change. Existing test runner/Room fixtures and module test dependencies are reused. No Gradle, compilation, test execution, schema generation/drift, lint, diff --check, device/provider activation, CI or Git mutation ran. Independent review is pending; all twenty-five full chains and convergence remain open.

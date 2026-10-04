# Independent live thermal policy source review

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Review-only by `/root/adversary_product_0930`; no production changes, Git operations, compilation, tests, lint, schema/device/provider/CI validation or integration.

## Exact selection

Base: `d8d5b0db44897e88fbda12c03a1caab99b9eb1e5`.

- `FILES.json`: `2d710a4337be196df70cc4ef6cc1ec02634cdc002a672a494f4769d36d38e5d1`
- `SELECTION.patch`: `f5fdc565eea9bfe2cc85b2e05d4e8170732bb6fa72d10b697ef54ae2572493f4`
- `SOURCE_BOUNDARY.md`: `500c118715fd30bdfe643459b558554940d45a7cf1f5b97a7d6874d3d79e5525`
- Selected service: `c6bd386fea877a9ec42c5e574e5a8771ccd84b0ae4c785023084f0731485be00`
- Selected test: `8dc919ac66903d1bc6c97df7102284cf8729da54389b8def8eb88d60731bfaca`

All eight BASE/SELECTED/WORKING_BEFORE/WORKING_AFTER snapshot pins match the supplied manifest. The selected patch contains only the existing tick call replacement, its unused private wrapper removal, and two new cases/shared fixture helper. Held manual Start/Off acceptance edits remain excluded from the selection.

## Findings

No concrete blocker found within this two-path selection.

The service's existing tick (`TrackerService.kt:1282–1292`, selected) samples current settings, demands and the existing `sourcePlanInputs` thermal predicate (`:2339`) only for an active, non-stopping session, then calls the same source-session reconfiguration used by other policy changes. It retains the existing rejection-to-graceful-stop path and adds no timer or provider owner.

`TrackerServiceSourceSession.reconfigureUnderReadyGeneration:551–565` includes the requested thermal context in the semantic input fingerprint, and returns Unchanged only when the exact applied/desired identities agree and no catalog debt remains. Its `:609–658` builds the current desired plan, replaces the desired identity and persists/retries the same current request on catalog availability debt; `:695–740` records applied state and clears/updates debt. Consequently an ordinary unchanged tick does not allocate a Room plan revision or restart a provider, while debt remains actionable and an older cached thermal plan is not substituted. This relies on the existing coordinator and plan-store semantics; no device result is inferred.

The two authored cases use the actual semantic plan factory, resolver, Room source-plan store, source-session fingerprints and descriptor compare/replace path. The lifecycle coordinator and descriptor store are fixtures. The debt case intentionally returns Retryable twice during the first reconfiguration (including its immediate retry), applies the current hot plan on the next call, clears debt and then restores baseline quality. Both cases assert that only chosen Steps/Pressure remain enabled, Steps continuity parameters stay unchanged, Pressure uses the existing useful thermal floor, and repeated hot/cooled inputs are no-ops. Helpers and imports are present in the selected test source.

## Evidence limits

Both new cases are **NOT_RUN**. This is static acceptance of the narrow callsite/planning slice, not a service lifecycle, provider, battery, thermal-device or full source-chain proof. Automatic callback-to-service acceptance and the separately pending manual Start coordination remain outside this review; all 25 full-chain prerequisites remain open.

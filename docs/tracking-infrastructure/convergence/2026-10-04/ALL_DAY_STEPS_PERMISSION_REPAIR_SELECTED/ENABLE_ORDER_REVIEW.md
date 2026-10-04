# Independent addendum: provider-only first enable ordering

**IMPLEMENTATION_ONLY / static source only / NOT_RUN.** This addendum preserves the accepted `SOURCE_REVIEW.md` and narrows its enable-time evidence. No source changes, execution or Git mutation occurred.

## Result

The repair button is connected and accepted. **An immediate Android motion prompt on first enable is not guaranteed** when the phone has no direct step counter and its current all-day provider status is still Disabled/Waiting. The selected tests establish the conditional launch predicate when the LocalRecording permission reason is already available; they do not prove first-enable flow ordering.

This is not a confirmed loss of permission repair. Once the existing publication flow exposes LocalRecording's missing permission, the selected basic/advanced repair action becomes visible and clicking it uses the current selected source/provider and real Android grant. The approved “preserve the choice with a clear repair” requirement is supported by that path. No extra prompt mechanism or automatic replay is implied by source acceptance.

## Exact additional committed inputs

All source objects below are at `d2bff19a88ba5224c4dd2e883fd313163c4a17dc`, read as Git objects rather than mixed working files.

| Repository path | Git blob |
| --- | --- |
| data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/AuthoritativeTrackingParamsRepository.kt | a03bfcdd3aa8691b000b1a3016744b7cf6d90e43 |
| app/src/main/java/com/adsamcik/tracker/app/di/RepositoryModule.kt | 2072a6ee1d17d37e4addf06e50a9e681e1a3ac46 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/CurrentTrackingPurposeAvailabilityProjection.kt | e632ca5c990808df42a372727eb73b6817cf81d9 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/TrackingPurposePublicationRuntime.kt | fb2dd683a3ca8c018428a38c9c66172b0d67ddcf |

The selected permission packet and its 18 dependency pins remain unchanged.

## Actual ordering

1. `AuthoritativeTrackingParamsRepository.setStepsEnabled:251–254` delegates to the authoritative update. `update:182–209` commits policy, awaits policy/purpose reconciliation, mirrors settings and awaits the new settings revision.
2. `reconcilePolicyRevisionAtReadyGeneration:538–543` invokes the existing purpose reconciler through `RepositoryModule:148–151`. The runtime reconciler synchronously awaits the source owner and accepts its report at `TrackingPurposePublicationRuntime:1438–1451,1493–1517`.
3. Source owner permission-required results become the correct LocalRecording reason through the frozen `AmbientStepsPurposeOwner:140–148`. The current availability projection is nevertheless an asynchronous `combine(...).stateIn(applicationScope,...)` at `CurrentTrackingPurposeAvailabilityProjection:92–101`; reconciliation does not await that projection's consumer.
4. The unchanged Settings ViewModel has another asynchronous `combine` collector at `215–229`, which updates `latestPurposeAvailability`. `refreshPermissionState:251–258` reads that cached value. `saveSettingsEdit:800–809` and the mutation callback at `349–353` refresh and invoke `onSaved` without awaiting a matching availability revision.
5. With no direct counter, selected `shouldRequestStepsMotionPermission:889–897` needs the LocalRecording reason. The callback can therefore observe the new selected Steps setting alongside the old Disabled/Waiting provider status and decline the immediate prompt.
6. Both ongoing availability collectors remain active. When the permission reason arrives, the shared selected repair control at `849–886`, used at `799` and `992`, offers the real motion action. It does not depend on first-enable callback replay, a new sensor probe, additional storage or a changed collection intent.

No Kotlin/coroutine execution was used to demonstrate a particular schedule. The absence of an explicit await is the static finding; arbitrary scheduling assumptions cannot be presented as a guarantee. A deterministic first-enable dialog would need an explicit additional source-order decision or a narrowly approved existing-state synchronization repair, and is not part of this accepted click-time repair slice.

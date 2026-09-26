# Whole-product polish review context

Date: 2026-09-26. Pass 2 is planning and static source inspection only, within IMPLEMENTATION_ONLY.

## Original user follow-up, verbatim

I want you to run one more pass that also considers highly polished, UX where every element and piece of text must earn its place. The goal is to make sure things don't make sense just at the tracker level but also at how we store and present the data to the user and export. All pieces of the tracker need to be considered during this work not just my request in isolation of everything else.

## Context and authority

Read TASK_CONTEXT.md for the complete original task and approval history, the root AGENTS.md for the whole current phase boundary, and IMPLEMENTATION_PLAN.md plus the four first-pass reports. The first-pass planning checkpoint is `d6a813e41e` on `codex/source-native-map-context-root`. The main source baseline remains clean `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`; source refs/drafts are recorded in CURRENT_CODE_INVENTORY.json and OTHER_RELEVANT_REFS.json. Recheck source state and qualify every branch-specific assertion. Previous recommended designs remain pending approval.

No Gradle, compilation, tests, lint, Detekt, Room generation/drift, diff-check, device/emulator/UI evaluator, battery, CI, release, activation, push or implementation integration. No production/test edits. Existing source/test work remains IMPLEMENTED_UNVALIDATED. Agents write only their assigned planning report, without commits or recursive delegation.

## Review standard

Follow real journeys from selection and consent to acquisition, storage, presentation, history, actions, export/import and recovery. Consider the existing whole application, including Activity and Pressure, statistics, goals/awards/game, setup, maps/offline resources, notification preferences, widgets/shortcuts, privacy/retention, backups, debugging and accessibility/localization. Broad consideration is not authorization to invent features or redesign unrelated working screens.

For each proposed element/text, identify the user decision or understanding it supports, its source of truth, and the next action where needed. Recommend keep, combine, move, replace, or remove with a concrete reason. Keep internal source authority and diagnostics out of normal flows unless they help a meaningful decision. Preserve complete provenance and uncertainty in stored/exported data even when summary UI is concise. Do not confuse less text with hidden failures or silently omitted export content.

Inspect actual route/component/resource/call-site and serializer/reader contracts, not just filenames or a desired screenshot. Mark visual judgments as source-derived hypotheses; visual/device validation is deferred. Record inspected surfaces and explicit remaining context gaps. No universal completeness or device-reliability claim from static review or agent agreement.

## Focused roles and coordination

1. **Product/copy** — capture controls, settings/setup, dashboard/live/ambient/history/detail/statistics/maps, element hierarchy and labels, accessibility/state/action placement. Write UX_PRODUCT_REVIEW.md.
2. **Stored/exported meaning** — every current source's captured/ambient/imported/control semantics, retention/deletion, formats, backup/restore, export/import routes, completeness and round-trip truth. Write DATA_EXPORT_REVIEW.md.
3. **Whole-application consequences** — navigation, goals/awards/game, summaries/charts, secondary surfaces, privacy/resource settings, diagnostics, legacy compatibility and all module/user-surface coverage. Write WHOLE_TRACKER_REVIEW.md.
4. **Reconciliation** — read the three reports, challenge contradictions with exact code and peer input, revise the consolidated implementation plan and write WHOLE_PRODUCT_POLISH_PLAN.md. Only this role edits IMPLEMENTATION_PLAN.md after the three findings exist.

Exchange focused findings directly between peers. Each report should separate concrete code defects, proposed polish, existing contracts to preserve, approval-dependent choices, and deferred evidence. Prefer the smallest coherent changes to existing shells. Include a whole-product coverage table, concise examples of final user-facing copy/layout, storage-to-display-to-export consistency cases, and a bounded acceptance matrix. Every new design decision must be presented for approval before implementation; settled decisions are not reopened.

# Active Session launcher widget removal: source acceptance

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Independent source review only. One newly authored packaged-resource case NOT_RUN; no compilation/tests/Hilt/manifest packaging/lint/schema/device/provider/CI/Git mutations. T25 and all 25 full-chain prerequisites remain open.

## Exact accepted input

Base/reviewed committed HEAD `3a3476e63e5c3bc40e2697f5e503a94cb18592ed`; packet `ACTIVE_SESSION_WIDGET_REMOVAL_SELECTED`.

- FILES `c9e5ace219df62f2a825a8079de15f44288b1b6f5a6c9bb131f9ae69c44ee8e5`
- patch `55b19de35d7ccd9d046bf6a98fd99c4d96513c2ee55671a3cc4e158b7664486f`
- dependencies `d89c577966fb5d0b4bd847b87a961c113c06a3135f6f192e2b3f617c47f21df5`

All 41 operations match: 35 selected LF files and six exact deletions. All 40 original BASE/raw WORKING_BEFORE files and 19 saved/committed/current dependency pins match. The added test has no previous source. This is static source identity checking, not executable validation.

## Scope and callers

The human asked whether the discussed widget belonged to Android launchers and then explicitly instructed “Let's remove it”. The approved clarification in `APPROVED_CONTINUATION_DECISIONS.md` identifies Active Session. This supersedes its pending thirty-second refresh proposal; it does not authorize removing the separate Quick Start or Today Summary widgets.

The exact ActiveSessionWidget implementation/receiver, both provider XML variants, exclusive preview drawable and two source-only widget test files are removed. The manifest deletes only that receiver stanza. Shared WidgetUpdateScheduler retains its existing WorkManager name, policies and worker, and calls only the two surviving widgets. QuickStart/TodaySummary receivers, resources and controls remain untouched.

TrackerWidgetEntryPoint removes only the History interface needed by the deleted widget. Its state, preferences, daily summary and goal providers still satisfy the exact remaining callers. The distinct TrackingInfrastructureIntegrationEntryPoint History method used by device source tests is untouched. No other Hilt binding or shared tracking controller is removed.

Only exclusive speed/path formatter helpers and Active Session previews are removed. Shared duration/distance/Steps/goal formatter functions and remaining formatter test sources stay. All 28 modified locale/default strings files remove only the declared 23 exclusive keys where present (19 in older locales,23 default/Czech); there are no other added, removed or semantically changed resource entries. Applying the exact private source overlay to committed source references leaves no references to the removed class/XML/drawable/keys/formatter helpers. No data, provider, session organization or tracking command behavior is deleted.

The new JUnit4 Robolectric source uses Application rather than Tracker startup and inspects the packaged app's actual receiver metadata and XML resources for the two surviving receivers. It is a meaningful manifest/resource contract source; it does not run here, prove Android launcher update/migration behavior, Hilt generation or existing widget reliability. Retired widget-specific test sources are deliberately retired, not represented as passing.

## Disposition

Accepted for this requested removal's bounded source scope. No concrete source blocker or unapproved extra behavior was found. Runtime/device/gate evidence remains deferred; no whole-app readiness claim.

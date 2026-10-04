# Live thermal policy reconciliation

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** No compilation/tests/lint/schema/device/provider/CI/diff--check/Git integration executed.

The approved existing central adaptive policy samples severe thermal pressure, but the service's existing 30-second policy tick retries only stale provider debt during an otherwise healthy live session. This selection calls the same current-input reconfiguration used by settings/motion/battery changes at that existing tick. Source-session fingerprints retain unchanged-plan no-ops; the same method persists/retries catalog debt with the current requested plan. The now-unused private service retry wrapper is removed; the underlying public recovery operation remains.

No new timer, threshold, listener, sensor, database, policy, source participation, battery abstraction or coroutine context. The existing live-session/not-stopping guard and rejection stop path remain. Pressure stays at its existing useful floor and chosen Steps remain continuous. All-day Location already refreshes on this tick.

Two new actual Room/source-session planning cases exercise thermal-only reduction/restoration and unchanged tick idempotence, with and without persisted provider debt. They use the real semantic factory, resolver, Room applied-plan store and source-session fingerprints, with a fixture lifecycle coordinator and memory descriptor store. These are NOT_RUN and are not service/UI/device/battery evidence. The service callsite is statically traced. The selected service/test snapshots exclude all held manual Start/Off acceptance changes; actual working drafts retain them. Entire source chains and all 25 prerequisites remain open.

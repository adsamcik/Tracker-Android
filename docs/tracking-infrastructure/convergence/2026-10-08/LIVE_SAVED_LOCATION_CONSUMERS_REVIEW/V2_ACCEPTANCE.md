# Saved Location consumer V2 bounded source acceptance

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Static source review only. All 23 new methods remain NOT_RUN. No complete-chain, integration-ready or physical-provider claim. T25 and all 25 prerequisites remain open.

The original human requirements, corrections and approved shared saved-total choices were retained. No additional UX, attribution, cadence, store or provider decision is approved by this review.

## Exact input

INPUTS.json records the exact 72-path packet and hashes. FILES `16e0e7476270797af2f140d35eda5496700d7f6bc565c86b2d9cb53f30bb6487`, patch `d7f9c720cec80baac4f8b1f2d93f7b86973dc3ec9d1748c9f890bd26b70a11ef` and dependency manifest `e4e0562c96cde2bdddee4d868e6b621683c9b0e31ebbe1b8aeddaac0167aa1fc` match. All 72 selected sources are UTF-8 LF. All 103 committed pins match candidate `3b85ee34ebfb8bebc42356a4b8cd6e3bc97f5742`. All six previously private prerequisites now match those exact committed inputs; there is no unresolved private prerequisite in this packet.

The 37 resource changes each add only the existing-locale translation of `tracker_measurement_partial`; no original resource value was removed. The selection comprises 29 production Kotlin, six test Kotlin and 37 resource paths.

## Actual caller trace

TrackerRoute collects the existing controller's saved summary with lifecycle-aware collection, then passes it through Dashboard into exact-segment live cards and detail formatting. Location scalar values come from that saved fix/Double total. A missing accuracy does not erase coordinates; actual zero speed/accuracy/distance and finite negative original altitude remain visible. The generic altitude copy does not relabel the original datum as MSL. The optional details update time comes from the original measured fix time. Location tabs/details and other existing cards use their current source choices. Duration remains session metadata.

TrackerComponentFactory registers the notification collector in its existing orchestrator component scope. The actual component combines the same saved summary, current session/info/host and source choices. Both the new observer and existing onNewData pass the same exact-segment saved input through configured component IDs/orders; no second Room observer, numerical cache or Location request is introduced. The existing notification gate publishes changed payloads immediately and suppresses only unchanged payloads before its refresh interval. Original teardown/rollback paths cancel the registered job. The component generation and manager checks prevent a delayed preference load or late saved publication from recreating a torn-down manager. Old raw collection data is associated with its exact segment; Location numbers never fall back to it.

ActiveSessionWidget collects the controller and choice flows inside its existing provideContent composition. Its current active segment gates saved distance/speed and selected rows; old Steps presentation cannot be lent to a replacement segment. The existing preview remains visual rather than a numeric distance producer. The widget does not add a worker, timer, sensor or storage copy. Its Double formatter avoids narrowing the shared accumulated distance to Float; actual zero speed is usable.

The small presentation helper is read-only and reuses the existing saved models. Original source authority, epoch, retention and deletion are implemented by the previously reviewed source reader/controller, not reconstructed by these consumers. Signature updates cover existing notification subclasses and call sites. Module/dependency/manifest boundaries are unchanged.

## Test-source boundaries

Four helper tests exercise exact selection and missing/partial/zero formatting; four Tracker tests use explicit saved models, including two real Compose card interactions; four widget tests exercise the actual formatter/stat-builder with explicit models; five notification generator tests exercise real generators; six notification update tests use the actual component/manager with explicit fake-controller, settings, EntryPoint, preferences and launcher seams. The latter have a bounded five-second fixture scope and cleanup, including segment replacement, settings-Off, clear/failure, End/teardown and delayed preferences.

The original additional card fixture keeps its original points/assertions and supplies an explicit saved summary. Existing test source remains preserved. These fixtures do not prove actual Room admission, Android Hilt graph/notifications, installed Glance delivery, physical providers, battery or device lifecycle. No methods were executed or compiled.

## Remaining work is still open

- Average moving speed currently has an Unavailable display and awaits its separate behavior decision/implementation.
- The selected widget collectors update an already-running Glance composition. There is still no timely installed-widget publication hook outside that composition; the proposed bounded refresh hook/cadence is not implemented here.
- On-foot/vehicle fields lack a native qualified attribution producer and display Unavailable. Existing ski/other-source/generic LastUpdate generators retain their prior readers; this packet is not truthful all-source numerical completion.
- All-day Location, released/imported numeric adaptation, logical multi-segment totals, TodaySummaryWidget/TodayProgressCard and effective correction/restore hooks remain companion work.
- Numeric core invalidation/admission and full provider→storage→UI/export behavior need the final coherent assembly and deferred convergence batch.

No remaining concrete blocker was found within this exact V2 consumer selection. This bounded acceptance must not close the disclosed gaps. No Gradle, compile, tests, lint, schema, diff-check, UI evaluator, device/provider, battery, CI, Git mutation, integration or push was performed.

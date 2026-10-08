# Separate accepted Wi-Fi WAL backlog finding

Static exact source at f3b265d943af7973ee9aa781a8cac33ebb53460c. No adapter production or test change is selected here. NOT_RUN.

`WifiWalQualificationAdapter.kt:105–108` describes a read-only retained-WAL bridge. `qualify` opens a Room transaction and reads the exact bounded immutable event at :131–135; Android/provider callback admission has already occurred. Its production callers are `WifiCapturedFactWriter.write` :89 and `WifiSessionFactProjectionLane`'s actual durable drain :248. Thus calling it a live callback permission check was inaccurate.

After the coordinator accepts a quality change or End and updates the current owning run's operation fields, a pending earlier WAL row still carries its original accepted acquisition generation/boot. The adapter rejects it at :289 by comparing those original values with the mutable current run and at :300 by checking the manifest timeline against the mutable run boot. This can prevent an already-admitted backlog item becoming a saved source product. It is not just a portable export limitation.

The narrow follow-up should use the same already-approved original receipt/manifest/provider/authorization/demand/action authority and original Stop cutoff as historical saved readers, while preserving current-owner checks. `hasExactCurrentReplacementBundle` and its action checks at :922/:1056–1057 validate the actual current replacement and should not be relaxed just because they mention current generation/boot. Physical admission in `RoomDurableSourceIngress` must remain independently strict.

Focused follow-up source coverage should admit through the real ingress, accept a real or explicitly constructed later operation, then drain the original event through the actual adapter/writer/projection. Assert saved original readings and exact omission at/after the original capture cutoff, consent/erase/epoch and current-owner boundaries. Fixture source must distinguish constructed Android/provider authority from real Room processing. This is a source-grounded pending repair under root assignment, not proof that the current adapter passes those cases.

This packet closes only the reached native retained-read/export chain. It does not declare all Wi-Fi backlog materialization, tracking architecture or all 25 prerequisites complete.

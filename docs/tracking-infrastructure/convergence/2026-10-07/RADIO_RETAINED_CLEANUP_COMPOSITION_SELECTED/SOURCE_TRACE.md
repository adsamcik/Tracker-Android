# T11 static production trace

Committed pins are 06dbc3a3aa; the approved routine child-maintenance overlay is separate and not committed by this agent.

- Existing WifiDurableSourceIngressTest:680-725 and CellDurableSourceIngressTest:197-263 compose real per-reading WAL admission, historical demand groups, projector facts and exact network/tower children. Projection is atomic and replay does not duplicate those rows.
- AmbientWifiFactProjector:153-172 and AmbientCellFactProjector historical-member selection qualify individual readings and persist every qualified purpose group. Wifi/Cell payload metadata contains network/tower details, not SIM/subscriber IDs.
- RoomAmbientWifi/CellRepository use RetainedAmbientRadioReader for the saved historical grant, current fact/cursor lineage, retention boundaries and exact parent/child details. The optional RoomRadioLocationContextRepository snapshot uses the same retained source results; missing Location does not discard radios or start sensors.
- SourceBrokerAuthorizationTransactions:60-81 plus SourceBrokerDao.authorizationDemands apply the actual capture-purpose Room fence: retiring capture leaves active ambient in the current authorization of the shared physical registration. Tests check the actual new mask and unchanged old retained history/context. This is not a public End/physical teardown test.
- AmbientWifiMaintenance:303-419 and AmbientCellMaintenance:303-420 authenticate source consent reset and retain replay footprints/markers before erasing their own payload. The base lacked child deletion; RADIO_CHILD_MAINTENANCE_SELECTED places source-owned child deletes before parent deletion in the same transaction.
- Their prune paths select exact logical fact IDs and retain the newer groups. The overlay reuses existing fact-scoped child deletion before parent deletion. Wrong epoch returns before mutating children.
- Their preserving-product clear hooks are tested separately from actual outer full clear. AppDatabase:1114-1128 already removes both native/imported background child sets before invoking its synchronous helpers. The case calls the existing actual test-accessible clear entry point, not a copied clear algorithm.

All evidence is authored source and static trace: NOT_RUN. No Gradle/build/tests/lint/schema/diff-check/device/provider/CI/integration was run.

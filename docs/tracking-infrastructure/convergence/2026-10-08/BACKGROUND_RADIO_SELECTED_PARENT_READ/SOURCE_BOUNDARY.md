# Selected parent reads for measured background Wi-Fi and Cell

Phase: IMPLEMENTATION_ONLY. Tests: NOT_RUN. No assembly/gate/provider/integration readiness claim.

Four production paths add current native/imported parent and saved gap ID queries and an internal readSelectedInTransaction hook. The caller owns the existing shared Room snapshot. It selects at most 100 actual parent identities; unrelated observations elsewhere in the day do not consume that budget. Qualification, original authority/lineage, epoch, retention, replay and whole-child-set integrity remain in the existing source-owned qualifier. No new database fields/tables, writer, provider demand, network egress or public contract.

Two actual Room source tests cover complete verified child lists across midnight while original parent/identity is unchanged, and missing mandatory Wi-Fi child integrity while independent Cell remains usable. Existing fixtures use Room with authored authority records; they are not hardware evidence.

This is a read hook only. The larger measured-key selector, capture/imported-session exact leaf reader, independent parent failure handling and unified History caller routing remain unassembled. This slice alone does not implement History or remove its whole-entry limits. All 25 full-chain prerequisites/T25 remain open.

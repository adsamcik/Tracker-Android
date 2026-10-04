# Independent recheck: automatic CONTROL wiring

Result: **accepted for the narrow source selection, IMPLEMENTED_UNVALIDATED**. Review only; no compilation, test execution, schema/CI/device/provider checks, Git mutation or recursive delegation. Original requirements and the first rejected review still apply. All 25 full-chain prerequisites remain open.

## Exact revised input

- FILES.json: `0a8bd58175466263d10a87fd5633a5fe7e7b5448b455414b9588d4bc38eb8e8e`.
- SELECTION.patch: `c0ccc088f6d711a0eb5bb1d19f8214b67cb88748de9692bb4fe71f22b4596a48`.
- SOURCE_BOUNDARY.md: `8d96561c51aa8a4b85e48d5497d07c31bd5e1b937f4d3c025d1d01a326c3e320`.
- CASE_INVENTORY.json: unchanged `eb31a3ed1ecc9d0d42f89b2147c60b62a6c868572c910b1969bb1ac65dc3793d`.

All 50 selected snapshots match the revised manifest. Comparison with AUTOMATIC_CONTROL_WIRING_SELECTED confirms that the only changed selected file is BackgroundTrackingApi.kt (`64cc949eb93acb416eb0749685b4e3e9a3b62bc88a4b72604a1f83e1b94cad5e`), and its only content change restores the required `ActivityTransitionData` import. All 49 other selected snapshots and the 24 NOT_RUN authored cases are unchanged.

R1 is resolved: existing transition handling, construction and helper signatures now resolve their explicit imported type. The earlier source assessment of A1–A4, exact in-flight activation, nonpersistent CONTROL, persisted suppression, prior/cold cleanup, APPLIED-only retirement and successor fencing remains applicable. No additional source blocker was found in this slice.

The original rejected packet and receipt remain intact. This receipt does not establish automatic callback-to-accepted-session/storage/UI behavior, Android provider or battery behavior, compilation/Hilt success, complete assembly or dev/v10 integration readiness.

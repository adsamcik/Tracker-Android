# Storage and compilation verification input

The user's 2026-10-10 instruction explicitly requests schema generation, released-app
upgrade tests, compilation/dependency wiring, host unit tests and quality checks. This
authorizes those checks for this committed snapshot; it does not declare the full
tracking implementation assembly complete or authorize provider activation, release,
push or integration into `dev/v10`.

## Exact input

- Candidate: `7e2ae029eae04655b499bdd7cf4f2c5f09c38b6a`.
- Clean verification branch: `codex/tracking-verification-1010`.
- Released `main`: `59fda665c7099d10b16aa60f505de88bd05d3131`.
- Released observation database: version 12; its checked-in schema blob
  `f50bd97e622f54511f6ebb0c5a34f4ce74669992` is identical in the candidate.
- Candidate observation database: version 29. Before generation, checked-in
  schema JSON ends at version 28.
- JDK: JetBrains 21.0.9. Android SDK platform 37.0 and build tools 37.0.0 are installed.
- No connected Android device or configured emulator was discovered.

The original candidate's tracked and untracked drafts are excluded from this input
and preserved in their original checkout. The two proposed Location gap repairs
are not in this snapshot and remain pending approval and implementation.

## Required evidence

1. Generate Room schemas with the repository's KSP configuration and review the
   generated changes before the committed-schema drift gate.
2. Compile the app and instrumented migration test sources. Application KSP/Hilt
   compilation provides dependency wiring evidence.
3. Execute `ciUnitTest`, including the real Room released-version-12 import fixture
   and the separate released Points database fixture.
4. Execute `ciCheck --continue`, retaining schema, architecture, lint, Detekt,
   SQLite runtime and release metadata checks.
5. Record exact commands, exit codes, relevant reports, concrete repairs and the
   final reviewed revision. A failed or blocked gate remains failed or blocked.

Host fixtures do not prove Android instrumented migration execution, real sensor
behavior, process death, phone reboot, battery behavior or final product readiness.
All 25 full-chain prerequisites and the complete assembly gate remain open.

Command logs and generated reports are retained under the verification checkout's
build directories. Final outcomes will be recorded in a separate results document.

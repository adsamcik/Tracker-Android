# Repository guidance

## Investigate before editing

- Work from the repository root. Inspect `settings.gradle.kts`, the relevant module build file,
  `build-logic`, call sites, resources, manifests, and existing tests before changing behavior.
- Use repository-relative paths and the search/read tools available in the current environment. Do
  not assume a checkout location, tool name, model name, or agent-dispatch syntax.
- Keep changes scoped and preserve unrelated work.

## Build and module facts

- Gradle runs on JDK 21. Application bytecode targets Java 17. Android compile SDK and target SDK
  are 37; the minimum SDK is 26.
- `settings.gradle.kts` is authoritative for the module graph: 31 application modules plus the
  `:tools:ski-data-generator` tooling module. `build-logic` is an included build, not an
  application module.
- Current layers are `:app`, `:core:*`, `:data:*`, `:domain:*`, `:sensor:*`, `:stats:*`,
  `:tracker:*`, and `:feature:*`. Depend on API/contract modules instead of another feature's
  implementation.
- `gradle/libs.versions.toml` is authoritative for dependency versions, including Tracebox. Do not
  duplicate or inline dependency versions elsewhere.

## Architecture, privacy, and network boundaries

- Tracker is local-first. Tracked data stays on-device unless the user explicitly exports or shares
  it. Do not add telemetry, analytics, remote sync, advertising identifiers, or automatic crash or
  diagnostic upload.
- Tracebox is the local crash and diagnostic recorder. Keep release diagnostics payload-free and
  never log precise coordinates or other tracked data.
- Network egress is intentionally limited to user-enabled online MapLibre map resources. Production
  traffic must go through the controlled `:core:network` `NetworkGateway`, its policy aggregation,
  HTTPS enforcement, and host allowlisting. Do not construct independent production HTTP clients,
  bypass the gateway, or add another egress purpose without explicit approval and privacy review.
- Keep blocking disk and database work off the main thread. Use structured concurrency and the
  injected dispatcher abstractions. Preserve module boundaries, Hilt composition, Flow-based state,
  and migration coverage for schema changes.

## Verification and QC

- Add or update focused tests for changed behavior. `./gradlew.bat ciUnitTest` is the authoritative
  repository-owned JVM/host test aggregate; `./gradlew.bat ciCheck --continue` is the authoritative
  full repository quality gate.
- For emulator QC, use one representative emulator/API configuration for the run and record it. Do
  not create an API matrix unless the task explicitly requests one.
- Discover the device-control and evaluator capabilities that are actually available. One evaluator
  is sufficient; additional independent evaluators are optional.
- Treat evaluator observations as hypotheses. Confirm findings only with supporting code,
  screenshots or element evidence, logs, or interactive reproduction. Model preference or model
  agreement alone is not evidence.

## Git and delivery

- Start worktrees from a clean local `dev/v10`, never `origin/dev/v10`. Commit each coherent
  completed step.
- Before integration, rebase the worktree branch onto the latest local `dev/v10`, rerun relevant
  verification, then merge the completed branch into local `dev/v10` from the clean main worktree.
  Remove the merged worktree and delete the merged local branch.
- Do not push, deploy, modify repository hosting settings, or overwrite unrelated work unless the
  user explicitly requests it. Never deploy to ChatGPT Sites unless explicitly instructed.

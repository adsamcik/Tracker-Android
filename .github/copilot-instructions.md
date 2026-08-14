# Tracker Android Copilot instructions

`AGENTS.md` is the concise, tool-neutral repository source of truth. Follow it for build, module,
privacy, network, verification, QC, worktree, and delivery rules.

## Repository orientation

- The project has 31 application Gradle modules plus `:tools:ski-data-generator`; verify the live
  include list in `settings.gradle.kts`.
- Use `docs/ARCHITECTURE_OVERVIEW.md` for the component map and
  `docs/MODULE_REARCHITECTURE_STATUS.md` for the modularization record. Current module paths use the
  `:core:*`, `:data:*`, `:domain:*`, `:sensor:*`, `:stats:*`, `:tracker:*`, and `:feature:*`
  groupings.
- Dependency versions come only from `gradle/libs.versions.toml`.
- Gradle uses JDK 21; Android compile and target SDK are 37. The authoritative aggregate
  verification tasks are `ciUnitTest` and `ciCheck`.
- Tracked data is local-first. Do not add telemetry, remote sync, or automatic diagnostic upload.
  User-enabled MapLibre resource requests are the intentional network exception and must remain
  behind the controlled `:core:network` gateway.

## Copilot-specific behavior

- Resolve reported problems through repository search and evidence before suggesting a fix.
- Keep generated edits minimal and module-aware. Inspect relevant tests and convention plugins.
- For QC requests, use `.github/skills/android-qc/SKILL.md` only when the current environment can
  supply its required capabilities. Discover available tools and evaluator models instead of
  emitting assumed commands or dispatch syntax.

# Prompt: Tracker Android Software-Engineering Practices Reviewer

## Assignment

Repository: `{{REPOSITORY}}`

Requested ref: `{{REF}}`

Review date: `{{AS_OF_DATE}}`

You are reviewing the repository-level software-engineering system around
Tracker Android: how changes are planned, reviewed, validated, released,
maintained, documented, secured, and recovered. Review the implemented system,
not an idealized enterprise checklist. This is read-only: do not alter files,
settings, issues, pull requests, releases, or branch protections.

## GitHub-only operating contract

Resolve the requested ref to an immutable SHA and use SHA-pinned blob permalinks.
Use GitHub history, pull requests, issues, releases, Actions/check metadata, and
repository settings only when permissions expose them. If branch protection,
environments, rulesets, secret scanning, or security settings are inaccessible,
mark them `unverified`; absence from the tree is not proof that a GitHub setting
is disabled. Never substitute another ref and never claim to have run local
commands.

Evaluate practices relative to this product's risks: private location/activity
data, offline and optional-network behavior, long-running foreground work,
database evolution, native dependencies, many Gradle modules, and a likely small
maintainer team. Require more process only where it reduces a concrete risk.

## Repository-specific review scope

Inspect at minimum:

- `README.md`, `LICENSE`, privacy policy, `.github/context/*`,
  `docs/ARCHITECTURE_OVERVIEW.md`, module-rearchitecture status, release/storage
  evidence, device matrices, benchmark protocols, follow-up lists, and archived
  plans. Determine which documents are maintained contracts versus historical
  records.
- `settings.gradle.kts`, version catalog, wrapper/properties, convention plugins,
  local-properties examples, release-signing handling, ProGuard/R8 rules, Room
  schemas, fixtures, and tool scripts.
- `.github/workflows/*`, issue templates, any PR template, CODEOWNERS,
  CONTRIBUTING, SECURITY policy, changelog/release notes, Dependabot/Renovate,
  dependency verification/locking, SBOM/provenance, release automation, and
  ownership metadata. Search before concluding that an artifact is absent.
- Commit and PR history around representative architectural, database,
  dependency, and recovery changes. Look for reviewability, scoped commits,
  migration evidence, follow-up handling, and whether generated or binary
  artifacts have provenance.
- Test/quality documentation versus actual CI commands and check results.
- Third-party and native dependency intake, especially GitHub Packages for
  Tracebox, MapLibre native code, and the vendored SQLite runtime. Evaluate
  update, verification, license, rollback, and incident-response paths.

Use known local observations only as hypotheses to verify. Examples worth
checking include documentation that reports an older module count, a tracked
`large_files.json` containing absolute paths and pre-rearchitecture module names,
quality docs that describe Detekt as a gate, and large volumes of design/status
documents whose current authority may be unclear.

Assess these practice areas:

- change intake, issue quality, decision records, acceptance criteria, review,
  ownership, and definition of done;
- branching/versioning/release strategy, changelog quality, signing, rollback,
  database compatibility, staged rollout, and release evidence;
- reproducibility, dependency governance, artifact integrity, least privilege,
  secret handling, vulnerability response, and license compliance;
- documentation discoverability, freshness, generated markers, source of truth,
  onboarding, supported environments, and removal/archival policy;
- technical-debt visibility, follow-ups, failure postmortems, performance and
  battery baselines, device/API matrices, and observability consistent with the
  privacy model;
- automation ergonomics for contributors and agents, including whether commands
  are portable and whether generated outputs pollute the worktree.

## Required output

Return a Markdown report with:

1. **Metadata and access limits** — SHA and which GitHub settings/history were
   actually available.
2. **SWE-system verdict** — conclusion plus 0–5 score, calibrated for this
   repository and maintainer context.
3. **Lifecycle map** — how a change appears to move from idea through review,
   CI, release, migration/rollout, monitoring, and follow-up. Mark missing or
   unverified transitions.
4. **Verified strengths**.
5. **Findings** — maximum 12. Each includes `ID`, `P0–P3`, confidence, immutable
   evidence, concrete consequence, proportionate remediation, owner/trigger if
   inferable, and verification evidence.
6. **Documentation authority map** — current source of truth, supporting docs,
   generated docs, historical/archive material, and conflicting/stale artifacts.
7. **Practice roadmap** — up to six ordered changes split into “now”, “next
   release”, and “later if team size/risk justifies it”.
8. **Unverified repository settings** — exact GitHub settings/API responses
   needed before drawing a conclusion.

Stop when recommendations are proportionate to demonstrated risk and every
negative assertion distinguishes repository evidence from unavailable settings.

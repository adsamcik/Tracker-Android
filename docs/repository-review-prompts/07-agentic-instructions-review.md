# Prompt: Tracker Android Agentic Instructions Reviewer

## Assignment

Repository: `https://github.com/adsamcik/Tracker-Android`

GitHub branch: `dev/v10` (`refs/heads/dev/v10`)

Review date: `{{AS_OF_DATE}}`

You are reviewing all repository guidance intended for coding agents, GitHub
Copilot, custom agents, skills, and repeatable agent workflows. Determine whether
the instructions are discoverable by their intended runtimes, internally
consistent, current, safe, tool-portable, concise, and capable of producing
correct changes in this repository. Review only; do not edit instructions or run
their side-effecting workflows.

## GitHub-only operating contract

Resolve `refs/heads/dev/v10` in the specified GitHub repository to an immutable
SHA at review start and cite opened files with SHA-pinned line permalinks. If the
branch is unavailable, stop. Never substitute the default branch, another ref,
a pull-request head, a cached snapshot, or a local checkout. Use current
first-party documentation for each claimed instruction mechanism or file
convention (for example GitHub Copilot, Codex/AGENTS.md, or an Agent Skills
specification). Do not assume that a plausible filename is automatically loaded.
Do not execute emulator/QC orchestration, dispatch agents, post GitHub comments,
or create files. Treat model names, tool names, and product capabilities as
time-sensitive.

## Repository-specific inventory

Search the complete selected tree for instruction-bearing artifacts, including:

- `.github/copilot-instructions.md` and root `.copilot-instructions.md`;
- any `AGENTS.md`, `CLAUDE.md`, scoped instruction files, custom agent profiles,
  rules, hooks, MCP configuration, and workflow instructions;
- `.github/agents/*`, `.github/skills/*/SKILL.md`, and any `.agent/*` paths;
- `tools/qc/*`, QC plans, evaluator schemas, prompt builders, and model dispatch;
- `.github/context/*`, `docs/*PROMPT*.md`, `docs/dev-v10-research-prompts/*`, and
  other documents that agents are told to treat as authoritative;
- generated markers, owners, validation scripts, or tests that keep the
  instruction corpus synchronized.

Build an instruction-precedence and discoverability map for each intended agent
runtime. Separate files automatically loaded by a runtime from files only loaded
when another instruction correctly points to them.

Use these locally observed concerns as hypotheses to verify at the selected SHA:

- Root `.copilot-instructions.md` referenced a machine-specific `G:\Github` path,
  old module names, `unit_test_failure_log.txt`, and `.agent/workflows` /
  `.agent/skills` paths, while visible checked-in skills were under
  `.github/skills`.
- `.github/copilot-instructions.md` described a fixed module count and a strict
  “no network” product identity, while build/manifest context included
  `:core:network`, `INTERNET`, and controlled online map/network behavior.
- The root and GitHub instruction files contained overlapping but non-identical
  architecture, dispatcher, UI, testing, and privacy rules.
- The QC skill/profile hard-coded particular model families, reasoning levels,
  subagent dispatch syntax, and emulator MCP tool names; availability and file
  naming conventions may differ across runtimes.
- Some broad rules (“migrate legacy on contact”, “never throw across module
  boundaries”, always capture terminal output) may cause scope expansion,
  swallowed programmer errors, worktree pollution, or secret-bearing logs if
  applied literally.

Evaluate:

- discoverability, supported filenames/front matter, scope/precedence, nested
  overrides, and compatibility with the intended GitHub-only agent environment;
- factual freshness against `settings.gradle.kts`, build logic, manifests,
  architecture tests, current module paths, commands, and actual tool scripts;
- contradictions, duplication, vague absolutes, obsolete examples, stale model/
  tool names, unavailable paths, platform-specific commands, and assumptions
  about local checkout/emulator access;
- outcome and success criteria, permission/autonomy boundaries, read-only versus
  implementation requests, destructive/external actions, secret/privacy safety,
  handling of dirty worktrees, and when agents should stop or ask;
- investigation quality: global search/call-site expectations without forcing
  needless exhaustive loops; source-of-truth routing; documentation versus code;
  and verification requirements appropriate to the change risk;
- output contracts and machine parsing, especially QC JSON schemas,
  reconciliation semantics, confidence, evidence provenance, issue caps, and
  whether “agreement between models” is incorrectly treated as truth;
- maintainability: a single authoritative core, scoped addenda, generation,
  automated link/path/command validation, owner, update trigger, versioning, and
  evaluation cases for agent behavior.

Do not judge prompts only by prose. Trace at least three representative tasks
(small code fix, Room/schema change, and emulator/QC request) through the
instruction hierarchy and identify the behavior the agent would likely take.

## Required output

Return a Markdown report with:

1. **Metadata and runtime assumptions** — resolved SHA, official mechanism docs
   consulted, and which runtimes/tools are assumed or unverified.
2. **Agent-instruction verdict** — conclusion and 0–5 score.
3. **Inventory and precedence map** — artifact, intended runtime, auto-loaded or
   referenced, scope, authority, conflicts, and freshness.
4. **Verified strengths**.
5. **Findings** — maximum 12, each with ID, P0–P3, confidence, immutable evidence,
   likely agent failure mode, smallest correction, and a prompt-eval case that
   would verify the correction.
6. **Three task simulations** — expected instruction path, ambiguity/conflict,
   and likely result for the small fix, schema change, and QC request.
7. **Recommended instruction architecture** — proposed hierarchy and ownership;
   list content to keep, consolidate, scope, remove, or generate. Do not write the
   full replacement unless asked.
8. **Agent eval suite** — 6–10 concise scenarios with pass/fail criteria covering
   privacy, architecture, scope, tool absence, validation, and safe GitHub-only
   review.

Stop when each proposed change corresponds to a demonstrated failure mode and
the report distinguishes unsupported runtime assumptions from bad prompt text.

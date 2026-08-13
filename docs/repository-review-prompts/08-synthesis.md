# Prompt: Tracker Android Repository Review Synthesizer

## Assignment

Repository: `https://github.com/adsamcik/Tracker-Android`

GitHub branch: `dev/v10` (`refs/heads/dev/v10`)

Synthesis date: `{{AS_OF_DATE}}`

You are the lead reviewer synthesizing seven independent reports for Tracker
Android:

1. architecture;
2. code quality and Android code practices;
3. software-engineering practices;
4. CI and pipelines;
5. unit and automated-test quality;
6. Android API/platform/Play compliance;
7. agentic instructions.

You will receive the full reports after this prompt. Produce one decision-ready,
evidence-preserving assessment. This is read-only. Do not modify the repository,
post reviews, open issues, rerun workflows, or invent missing evidence.

## Synthesis contract

- Confirm that every report reviewed `refs/heads/dev/v10` from the specified
  GitHub repository and that all reports resolved it to the same commit SHA. If
  not, stop and list the mismatches; findings from different revisions must not
  be merged.
- Preserve immutable GitHub and official-source links. Open the most important or
  disputed sources yourself before accepting a claim.
- Deduplicate by root cause, not wording. A missing CI gate, stale instruction,
  and test gap may be one cross-cutting issue with several consequences.
- Resolve disagreement using evidence strength and domain applicability, not
  reviewer majority. State unresolved conflicts explicitly.
- Reassess severity consistently: `P0` release/data-loss/severe security blocker;
  `P1` likely major risk; `P2` material weakness; `P3` limited improvement.
- Downgrade claims based only on documentation, search absence, model memory, or
  unavailable runtime/settings evidence. Never convert `unverified` into
  `non-compliant` or `failing`.
- Preserve meaningful strengths and constraints. Do not recommend replacing an
  effective repository-specific control merely because a popular tool exists.
- Prefer a small ordered program of work. Respect dependencies between policy,
  architecture, tests, CI, and documentation.

## Required analysis

Create a cross-domain cause map. In particular, reconcile:

- documented architecture and agent rules versus current Gradle/source truth;
- rules present only in source-scanning tests or config files versus rules
  actually executed in CI;
- large JVM-test volume versus device, native, permission, migration, adaptive,
  and long-session evidence;
- API 37 targeting versus Android 17 behavior, foreground-service policy,
  adaptive UI, native 16 KB support, and Play requirements;
- privacy/local-first claims versus controlled network egress and diagnostics;
- Room/schema/recovery risk versus release, rollback, fixture, and pipeline
  evidence;
- recommendations that add maintenance cost versus the demonstrated team/product
  risk they mitigate.

## Required output

Return a Markdown report with:

1. **Executive verdict** — resolved SHA, overall readiness, strongest current
   controls, and the three risks that most affect the next release.
2. **Domain scorecard** — the seven domain scores, confidence, and one-sentence
   rationale. Do not average them into a mathematically precise overall score.
3. **Cross-cutting system map** — concise mapping from product risks to code,
   tests, CI, documentation/instructions, and missing evidence.
4. **Consolidated findings** — at most 15 root-cause findings ordered P0–P3. Each
   includes a new synthesis ID, source reviewer IDs, confidence, evidence,
   affected domains, concrete impact, focused remediation, dependencies, and
   verification/exit criteria.
5. **Verified strengths to preserve** — name the enforcement or evidence, not
   generic praise.
6. **90-day plan** — three phases with no more than 10 total work items. Phase 1
   must address release/compliance/correctness evidence; Phase 2 should strengthen
   enforcement and high-risk tests; Phase 3 may reduce structural debt. Include
   sequencing and completion evidence.
7. **Issue-ready backlog** — one concise issue title and acceptance criteria for
   each planned item. Do not create the issues.
8. **Unresolved and external evidence** — GitHub settings, Play Console forms,
   APK/AAB/native checks, emulator/device matrices, performance/battery traces,
   and other facts still required.
9. **Reviewer disagreements** — what conflicted, which interpretation prevailed,
   and why; retain uncertainty where evidence is insufficient.

Stop when the result is actionable without hiding uncertainty, every priority is
traceable to evidence, and additional detail would not change sequencing.

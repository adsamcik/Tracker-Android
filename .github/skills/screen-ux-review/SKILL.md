---
name: screen-ux-review
description: >
  Evidence-driven screenshot UX review for visual defects and product feedback.
  Uses one or more available evaluators without assuming model names or dispatch APIs.
---

# Screen UX review skill

Review one or more supplied Android screenshots. Discover the image-inspection and evaluator
capabilities available in the current environment; do not assume fixed model identifiers, task
syntax, or parallel execution support. One evaluator is sufficient. A second evaluator is optional
and may broaden coverage.

## Pass 1: visual defects

Inspect in this order:

1. Full-screen layout: content/background continuity, viewport fill, system bars, and bottom-nav
   interaction.
2. Element scan: visibility, clipping, overlap, truncation, and alignment from top to bottom.
3. Interactive controls: complete visibility, edge clearance, affordance, and approximate 48 dp
   touch targets.
4. Spacing and density: internal padding, gaps, margins, and repeated component consistency.
5. Accessibility evidence visible in the screenshot or supplied element tree, including contrast,
   font-scaling risk, labels, and roles.

Report literal observations, expected behavior, user impact, confidence, and exact evidence
references. Do not invent code causes from a screenshot.

## Pass 2: UX review

Assess purpose and primary action, information architecture, label clarity, interaction affordance,
Material 3 consistency, accessibility risks, and up to three high-impact improvements. Separate
defects from optional design ideas.

## Reconciliation and evidence

- An evaluator-only observation is a hypothesis. Evaluator agreement raises priority but is not
  proof.
- Confirm defects only with inspectable screenshot/element evidence, supporting code or logs, or an
  interactive reproduction.
- Preserve unique evaluator observations as suspected findings rather than discarding them.
- Deduplicate by target and symptom, note genuine disagreements, and state coverage gaps.

When this review is part of emulator QC, follow `.github/skills/android-qc/SKILL.md` and keep the
entire run on its single recorded emulator/API configuration.

`tools/qc/orchestrator.py` can build visual or state-focused evaluator prompts. The caller remains
responsible for choosing available evaluators and using the environment's actual invocation
mechanism.

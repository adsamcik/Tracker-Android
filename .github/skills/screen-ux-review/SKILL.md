---
name: screen-ux-review
description: >
  Dual-model screenshot UX review with visual defect detection and creative
  UX feedback. Dispatches GPT 5.4 and Opus 4.6 in parallel for defect hunting,
  then runs a creative UX analysis pass. Use when reviewing screenshots for
  visual bugs, layout issues, or UX improvements.
---

# Screen UX Review Skill

Screenshot-based UX review that combines **visual defect detection** with
**creative UX analysis**. Dispatches two models in parallel, reconciles
findings, then generates improvement ideas beyond bug-spotting.

## Architecture

```
You (orchestrator — Opus 4.6 1M)
  │
  ├── PHASE 1: Dual-model Visual Defect Detection (parallel)
  │   ├── GPT 5.4 — defect hunter (layout-first protocol)
  │   └── Opus 4.6 — defect hunter (layout-first protocol)
  │   └── Reconcile → defect_findings[]
  │
  ├── PHASE 2: Dual-model Creative UX Review (parallel)
  │   ├── GPT 5.4 — UX critic (design patterns, IA, M3 compliance)
  │   └── Opus 4.6 — UX critic (accessibility, flow, user psychology)
  │   └── Reconcile → ux_insights[]
  │
  ├── PHASE 3: Synthesis
  │   └── Merge defects + insights, deduplicate, prioritize
  │
  └── PHASE 4: Skill Self-Improvement Feedback
      └── What did the prompts miss? How can we improve?
```

## How to Run

### Input

The skill takes one or more screenshot paths:

```
screenshot_paths = ["path/to/screenshot1.png", "path/to/screenshot2.png"]
screen_name = "Settings"  # human-readable name for the screen
```

### Phase 1: Visual Defect Detection (parallel dispatch)

Launch two background agents with the **layout-first defect protocol**:

```python
DEFECT_PROMPT = """You are a mobile QA tester performing pixel-level visual
defect detection. You will be penalized for every defect you miss.

View the screenshot at: {screenshot_path}

Work through these steps IN ORDER. Do not skip any step.

---

## STEP 1 — FULL SCREEN LAYOUT CHECK

Before looking at individual elements, examine the OVERALL screen layout:

a) Describe the background color/tone of the MAIN CONTENT AREA (the scrollable area).
b) Describe the background color/tone at the VERY BOTTOM of the screen.
c) Are they the same? If different, describe where the transition happens.
d) Does the main content area extend all the way from the top bar to the bottom
   edge (or bottom nav bar)? Or does it stop short, leaving empty/different-colored space?
e) A properly implemented full-screen page should have its content container filling
   the entire viewport. Does this screen achieve that?

---

## STEP 2 — ELEMENT SCAN

For each UI element top-to-bottom, write one line:
- Name | Fully visible (yes/no) | Clipped (yes/no)

---

## STEP 3 — INTERACTIVE CONTROL CHECK

For every button, chip, toggle, tab, or tappable element:
a) Is the ENTIRE control visible with no clipping on any side?
b) Is there adequate padding between the control and its container boundary?
c) Does the control touch or overflow any edge?

---

## STEP 4 — SPACING & DENSITY

a) Look at vertical gaps BETWEEN items/cards. Consistent or uneven?
b) Do items feel squished together, or is there breathing room?
c) Is there enough padding inside cards to make content comfortable?
d) Are horizontal margins consistent across all cards/sections?

---

## STEP 5 — ALIGNMENT & CONSISTENCY

a) Are all cards the same width with consistent horizontal margins?
b) Are text elements aligned consistently?
c) Do similar elements share consistent styling?
d) Are icons aligned with their text labels?

---

## STEP 6 — DEFECT REPORT

For EVERY defect:
- **Severity**: P0 / P1 / P2
- **What you see**: The literal visual problem
- **Expected**: What it should look like

RULES:
- Report what you SEE, not what you think the code intended
- Do NOT provide design suggestions or UX opinions — defects only
- Do NOT rationalize defects — just report them
- If you find fewer than 2 defects, look harder"""
```

Dispatch:
```
gpt_defect_agent = task(agent_type="general-purpose", model="gpt-5.4",
                        mode="background", prompt=DEFECT_PROMPT)
opus_defect_agent = task(agent_type="general-purpose", model="claude-opus-4.6",
                         mode="background", prompt=DEFECT_PROMPT)
```

### Phase 2: Creative UX Review (parallel dispatch)

Launch two background agents with the **UX creativity protocol**:

```python
UX_REVIEW_PROMPT = """You are a senior UX designer and product thinker reviewing
an Android screen. Go beyond bug-spotting — think about the user experience
holistically.

View the screenshot at: {screenshot_path}

---

## PART A — First Impressions (5-second test)

Imagine a user seeing this screen for the first time:
a) What is the screen's purpose? Is it immediately obvious?
b) What would the user do first? Is the primary action clear?
c) What feels confusing, overwhelming, or underwhelming?
d) Rate visual polish from 1-10 with justification.

---

## PART B — Information Architecture

a) Is the content organized in a way that matches user mental models?
b) Are labels clear and unambiguous? Would a non-technical user understand them?
c) Is anything important buried or hard to find?
d) Is anything unnecessary taking up prime real estate?

---

## PART C — Interaction Design

a) For each tappable element: is it clear what tapping will do?
b) Are affordances (chevrons, switches, dropdown indicators) used correctly?
c) Are there any "mystery meat" elements where the action is unclear?
d) Is the interaction model consistent across similar elements?

---

## PART D — Accessibility & Inclusivity

a) Would this work well at 200% font scaling?
b) Is contrast sufficient for all text against its background?
c) Are touch targets adequate (>= 48dp)?
d) Would a screen reader user have a good experience?

---

## PART E — Material 3 Compliance

a) Does this follow M3 patterns for this type of screen?
b) Are the right components used? (ListItem vs custom Row, proper chips, etc.)
c) Are M3 color tokens used correctly (surface, surfaceContainer, etc.)?

---

## PART F — Creative Improvements (IMPORTANT — think boldly)

Go beyond fixing problems. Propose UP TO 3 ideas that would meaningfully
elevate the user experience. Think about:
- Could the information hierarchy be restructured for clarity?
- Are there opportunities for delight, animation, or progressive disclosure?
- Would a different layout pattern serve the user better?
- Is there a way to reduce cognitive load?

For each idea:
- **Idea**: What to change
- **Why**: User benefit
- **Effort**: Low / Medium / High
- **Impact**: Low / Medium / High

---

## PART G — Summary

Provide a structured summary:
1. Top 3 most impactful issues (from parts A-E)
2. Top 2 creative improvements (from part F)
3. Overall UX grade (A through F) with 1-sentence justification"""
```

Dispatch:
```
gpt_ux_agent = task(agent_type="general-purpose", model="gpt-5.4",
                    mode="background", prompt=UX_REVIEW_PROMPT)
opus_ux_agent = task(agent_type="general-purpose", model="claude-opus-4.6",
                     mode="background", prompt=UX_REVIEW_PROMPT)
```

### Phase 3: Synthesis

After all 4 agents complete, synthesize:

1. **Defect reconciliation**: Cross-reference GPT and Opus defect reports.
   - Both found same issue → **Confirmed** (use higher severity)
   - Only one found it → **Suspected** (include but flag)
   - Contradictory assessments → **Disagreement** (report both views)

2. **UX insight merge**: Combine creative feedback from both models.
   - Deduplicate overlapping ideas
   - Note where models agree (strong signal)
   - Preserve unique insights from each model

3. **Final report structure**:
   ```
   ## Visual Defects (from Phase 1)
   [confirmed] [suspected] [disagreements]

   ## UX Issues (from Phase 2, Parts A-E)
   [agreed] [unique-gpt] [unique-opus]

   ## Creative Improvements (from Phase 2, Part F)
   [agreed ideas] [unique ideas]

   ## Model Comparison
   [what each model was better at]
   ```

### Phase 4: Skill Self-Improvement Feedback

After synthesis, ask one agent (Opus 4.6 1M, since it has the full context):

```
SELF_IMPROVE_PROMPT = """You just completed a dual-model UX review. Reflect on
the process and provide feedback on how to improve the skill itself.

Review the findings from both models and answer:

1. **Blind spots**: What types of issues did BOTH models miss or underweight?
   Think about what a human QA tester or UX designer would catch that was absent.

2. **Prompt gaps**: Which prompt instructions were too vague, leading to
   inconsistent results between models? Which were too prescriptive, preventing
   creative observations?

3. **New steps needed**: Should we add any new analysis steps? (e.g., dark mode
   check, landscape check, empty state analysis, error state analysis)

4. **Reconciliation improvements**: Was the dual-model approach valuable here?
   Were there meaningful disagreements? How could we make disagreements more
   productive?

5. **Concrete prompt edits**: Suggest 2-3 specific wording changes to the
   defect or UX prompts that would improve detection quality.

Be specific and actionable. Reference actual findings (or missing findings)
from this run."""
```

## Reconciliation Rules

| GPT says | Opus says | Resolution |
|----------|-----------|------------|
| Defect | Defect | **Confirmed** — use higher severity |
| Defect | No defect | **Suspected** — include with note |
| No defect | Defect | **Suspected** — include with note |
| Improvement idea | Same idea | **Strong signal** — prioritize |
| Improvement idea | Different idea | **Both included** — diversity is valuable |

## Key Design Decisions

### Why layout-first in defect detection?
Models default to element-level analysis and miss container/viewport issues.
Forcing a macro layout check as Step 1 catches background mismatches, dead space,
and content containers that don't fill the screen. This was validated across
5 prompt iterations where the layout-first approach was the only version that
achieved 100% detection on both models.

### Why separate defect and UX passes?
When combined in a single prompt, models optimize for conceptual UX critique
and rationalize away visual bugs. Separating the passes with different
instructions prevents "component archetype recognition replacing visual
verification" (a failure mode observed in testing).

### Why creative UX review?
QC is necessary but not sufficient. Bug-free screens can still have poor UX.
The creative pass generates improvement ideas that go beyond fixing what's
broken — restructuring IA, improving affordances, adding delight. This
transforms the skill from a bug-finder into a UX partner.

### Why self-improvement feedback?
Each run generates data about what the prompts caught and missed. The
self-improvement pass captures this signal and feeds it back into prompt
refinement, creating a learning loop.

## Integration with android-qc

This skill is used within the `android-qc` workflow at the **Screen QC** phase.
When the orchestrator captures a screenshot of a new screen, it can dispatch
this skill instead of (or in addition to) the standard `qc-evaluator` agents.

```python
from tools.qc.orchestrator import build_screen_ux_review_dispatch

# During Screen QC phase
defect_prompt = build_screen_ux_review_dispatch("defect", screenshot_path)
ux_prompt = build_screen_ux_review_dispatch("ux_review", screenshot_path)
```

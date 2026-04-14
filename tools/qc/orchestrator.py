"""
android_qc — Orchestrator helpers for sub-agent dispatch.

Generates prompts for the QC evaluator sub-agents (GPT 5.4 xhigh + Opus 4.6 1M high)
and the top-level orchestrator agent.

Usage:
    from tools.qc.orchestrator import build_evaluator_dispatch, generate_orchestrator_prompt

    # Build a prompt for one evaluator sub-agent
    prompt = build_evaluator_dispatch("screen", "gpt", screenshot_desc, elements, context,
                                      screen_id="home", route="dashboard", activity="pkg/.Main")

    # Generate the full orchestrator launch prompt
    prompt = generate_orchestrator_prompt(package_name="com.adsamcik.tracker.debug")
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

AGENT_DIR = Path(__file__).parent.parent.parent / ".agent" / "agents"

GPT_FOCUS = """FOCUS: Prioritize screenshot truth and element-tree anomalies:
clipping, overlap, truncation, misalignment, duplicate/missing controls,
bad spacing, disabled/enabled mismatch, wrong selection state, off-screen CTA,
bottom-bar overlap, tiny tap targets (<48dp), incorrect element classification.
Keep hyp empty unless directly implied by evidence."""

OPUS_FOCUS = """FOCUS: Prioritize cross-screen/state reasoning: incorrect
persistence, stale data, wrong confirmation state, contradictory UI, missing
feedback, unexpected route/content, hidden logic issues visible through
current evidence. Use hyp for likely root cause only when grounded in evidence."""

SCHEMA_HINTS = {
    "screen": '{"prompt":"screen","status":"...","summary":"...","route":"...","checks":[...],"issues":[...],"positives":[...],"gaps":[...]}',
    "transition": '{"prompt":"transition","status":"...","summary":"...","from_route":"...","to_route":"...","transition":"...","checks":[...],"observed_deltas":[...],"issues":[...],"positives":[...]}',
    "checkpoint": '{"prompt":"checkpoint","status":"...","summary":"...","flow_state":"...","checks":[...],"entities":[...],"issues":[...],"risks":[...]}',
    "synthesis": '{"prompt":"synthesis","status":"...","summary":"...","verdict":"...","issues":[...],"clusters":[...],"coverage_gaps":[...],"positives":[...],"stats":{...}}',
}

ISSUE_SCHEMA = '{"id":"str","sev":"blocker|critical|major|minor|nit","type":"visual|layout|copy|accessibility|interaction|navigation|state|data|validation|performance|resilience|permissions|system_dialog|logic|consistency","title":"<=80","exp":"expected","act":"actual","why":"impact<=120","hyp":"root cause<=120","conf":0.0,"dk":"route|type|target|symptom","ev":{"img":[],"el":[],"txt":[],"act":[]}}'


def build_evaluator_dispatch(
    mode: str,
    model: str,
    screenshot_description: str,
    elements_compressed: dict,
    context: dict,
    **extra: Any,
) -> str:
    """
    Build a complete dispatch prompt for a QC evaluator sub-agent.

    Args:
        mode: "screen", "transition", "checkpoint", or "synthesis"
        model: "gpt" or "opus" — determines focus directive
        screenshot_description: Human-readable description of what's on screen
        elements_compressed: Compressed element tree from compress_elements()
        context: State context dict (from state.prompt_context())
        **extra: Additional fields (screen_id, route, activity, etc.)
    """
    focus = GPT_FOCUS if model == "gpt" else OPUS_FOCUS

    eval_input: dict[str, Any] = {
        "mode": mode,
        "screen": {
            "id": extra.get("screen_id", "unknown"),
            "route": extra.get("route", "unknown"),
            "activity": extra.get("activity", ""),
            "orientation": extra.get("orientation", "portrait"),
        },
        "elements": elements_compressed,
        "context": {
            "goal": extra.get("goal", f"Evaluate this {mode}"),
            "state_ledger": context.get("state_ledger", {}),
            "coverage_gaps": context.get("coverage_gaps", []),
        },
        "recent_actions": context.get("recent_actions", []),
        "prior_findings": context.get("prior_findings", []),
    }

    if mode == "transition":
        eval_input["action"] = extra.get("action", {})
        eval_input["before"] = extra.get("before", {})
        eval_input["after"] = extra.get("after", {})
        eval_input["expectation"] = extra.get("expectation", {})

    if mode == "checkpoint":
        eval_input["checkpoint"] = extra.get("checkpoint", {})
        eval_input["flow_summary"] = extra.get("flow_summary", {})

    if mode == "synthesis":
        eval_input["all_findings"] = extra.get("all_findings", [])
        eval_input["screen_inventory"] = extra.get("screen_inventory", [])
        eval_input["action_trace"] = extra.get("action_trace", [])

    return f"""You are a QC Evaluator for Android app testing.

{focus}

MODE: {mode}

EVALUATE:
{json.dumps(eval_input, indent=2, default=str)}

SCREENSHOT DESCRIPTION:
{screenshot_description}

Return JSON only. No markdown. No code fences. No prose.
Output shape: {SCHEMA_HINTS.get(mode, '{}')}
Issue shape: {ISSUE_SCHEMA}"""


# ── Screen UX Review Dispatch ────────────────────────────────────────────────

DEFECT_PROMPT_TEMPLATE = """You are a mobile QA tester performing pixel-level visual defect detection. You will be penalized for every defect you miss.

View the screenshot at: {screenshot_path}

Work through these steps IN ORDER. Do not skip any step.

---

## STEP 1 — FULL SCREEN LAYOUT CHECK

Before looking at individual elements, examine the OVERALL screen layout:

a) Describe the background color/tone of the MAIN CONTENT AREA (the scrollable area).
b) Describe the background color/tone at the VERY BOTTOM of the screen.
c) Are they the same? If different, describe where the transition happens.
d) Does the main content area extend all the way from the top bar to the bottom edge (or bottom nav bar)? Or does it stop short, leaving empty/different-colored space?
e) A properly implemented full-screen page should have its content container filling the entire viewport. Does this screen achieve that?

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


UX_REVIEW_PROMPT_TEMPLATE = """You are a senior UX designer and product thinker reviewing an Android screen. Go beyond bug-spotting — think about the user experience holistically.

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

Go beyond fixing problems. Propose UP TO 3 ideas that would meaningfully elevate the user experience. Think about:
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


SELF_IMPROVE_PROMPT_TEMPLATE = """You just completed a dual-model UX review of an Android screen. Reflect on the process and provide feedback on how to improve the skill itself.

The following findings were produced:

## Defect Findings
{defect_findings}

## UX Review Findings
{ux_findings}

---

Answer these questions:

1. **Blind spots**: What types of issues did BOTH models miss or underweight? Think about what a human QA tester or UX designer would catch that was absent.

2. **Prompt gaps**: Which prompt instructions were too vague, leading to inconsistent results? Which were too prescriptive, preventing creative observations?

3. **New steps needed**: Should we add any new analysis steps? (e.g., dark mode check, landscape check, empty state analysis, error state analysis, animation/motion review)

4. **Reconciliation improvements**: Was the dual-model approach valuable here? Were there meaningful disagreements? How could we make disagreements more productive?

5. **Concrete prompt edits**: Suggest 2-3 specific wording changes to the defect or UX prompts that would improve detection quality. Give exact text to add, remove, or modify.

Be specific and actionable. Reference actual findings (or missing findings) from this run."""


def build_screen_ux_review_dispatch(
    mode: str,
    screenshot_path: str,
    **kwargs: Any,
) -> str:
    """
    Build a dispatch prompt for the screen-ux-review skill.

    Args:
        mode: "defect", "ux_review", or "self_improve"
        screenshot_path: Path to the screenshot file
        **kwargs: For self_improve mode: defect_findings, ux_findings
    """
    if mode == "defect":
        return DEFECT_PROMPT_TEMPLATE.format(screenshot_path=screenshot_path)
    elif mode == "ux_review":
        return UX_REVIEW_PROMPT_TEMPLATE.format(screenshot_path=screenshot_path)
    elif mode == "self_improve":
        return SELF_IMPROVE_PROMPT_TEMPLATE.format(
            defect_findings=kwargs.get("defect_findings", "None"),
            ux_findings=kwargs.get("ux_findings", "None"),
        )
    else:
        raise ValueError(f"Unknown screen UX review mode: {mode}")


def generate_orchestrator_prompt(
    package_name: str,
    depth: str = "standard",
    time_budget_minutes: int = 30,
    mock_locations: list[dict] | None = None,
    apk_path: str | None = None,
    risk_hints: dict | None = None,
) -> str:
    """Generate the launch prompt for the QC orchestrator agent."""
    orchestrator_md = AGENT_DIR / "qc_orchestrator.md"
    agent_instructions = ""
    if orchestrator_md.exists():
        agent_instructions = orchestrator_md.read_text(encoding="utf-8")

    params = {
        "package": package_name,
        "depth": depth,
        "time_budget_minutes": time_budget_minutes,
        "mock_locations": mock_locations or [],
        "apk_path": apk_path,
        "risk_hints": risk_hints or {},
    }

    return f"""Execute the android_qc skill with these parameters:

{json.dumps(params, indent=2)}

Read and follow the orchestrator instructions below.

---

{agent_instructions}"""

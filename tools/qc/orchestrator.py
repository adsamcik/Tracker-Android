"""
android_qc — Orchestrator helpers for sub-agent dispatch.

Generates prompts for the QC evaluator sub-agents (GPT 5.4 + Opus 4.6)
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

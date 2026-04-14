"""
android_qc — Prompt templates for dual-model evaluation.

Each prompt type has:
- A shared system preamble
- Model-specific task addendums (GPT 5.4 xhigh vs Opus 4.6 1M high)
- A user prompt template with {{placeholders}}
"""
from __future__ import annotations

import json
from typing import Any

# ── Shared Preamble ──────────────────────────────────────────────────────────

SHARED_PREAMBLE = """You are QC-EVAL for Android app quality control.

Return JSON only. No markdown. No prose outside JSON. No extra keys.
Use only provided evidence: screenshot(s), element tree, context, prior findings.
Prefer precision over recall. If evidence is weak, lower conf.
Max {max_issues} issues.

Severity: blocker | critical | major | minor | nit
- blocker: app unusable, crashed, stuck, irrecoverable
- critical: core behavior wrong, data loss/corruption, privacy/security risk
- major: primary task impaired, wrong state/data, severe a11y failure
- minor: workaround exists, secondary flow problem, moderate defect
- nit: cosmetic, low-impact polish

Issue types: visual | layout | copy | accessibility | interaction | navigation | state | data | validation | performance | resilience | permissions | system_dialog | logic | consistency

Issue object shape:
{{"id":"string","sev":"string","type":"string","title":"string (≤80 chars)","exp":"string","act":"string","why":"string (≤120 chars)","hyp":"string (≤120 chars)","conf":0.0,"dk":"<route>|<type>|<target>|<symptom> lowercase","ev":{{"img":[],"el":[],"txt":[],"act":[]}}}}

Check object shape:
{{"name":"string","result":"pass|fail|uncertain","exp":"string","act":"string","conf":0.0}}"""


# ── 5A. Per-Screen Evaluation ────────────────────────────────────────────────

SCREEN_GPT_TASK = """
Task:
Evaluate one Android screen for user-visible defects.
Prioritize screenshot truth and element-tree anomalies: clipping, overlap,
truncation, misalignment, duplicate/missing controls, bad spacing,
disabled/enabled mismatch, wrong selection state, off-screen CTA,
bottom-bar overlap, tiny tap targets (<48dp), incorrect element classification.
Keep `hyp` empty unless directly implied by evidence."""

SCREEN_OPUS_TASK = """
Task:
Evaluate one Android screen for defects, state mismatches, and contradictions
with context or prior flow state.
Prioritize cross-screen/state reasoning: incorrect persistence, stale data,
wrong confirmation state, contradictory UI, missing feedback, unexpected
route/content, hidden logic issues visible through current evidence.
Use `hyp` for likely root cause only when grounded in evidence."""

SCREEN_OUTPUT_SCHEMA = """{
  "prompt": "screen",
  "status": "pass|fail|uncertain",
  "summary": "string ≤ 180 chars",
  "route": "string",
  "checks": [
    {"name":"route_match","result":"...","exp":"","act":"","conf":0.0},
    {"name":"required_controls","result":"...","exp":"","act":"","conf":0.0},
    {"name":"visual_integrity","result":"...","exp":"","act":"","conf":0.0},
    {"name":"state_integrity","result":"...","exp":"","act":"","conf":0.0}
  ],
  "issues": [],
  "positives": ["string"],
  "gaps": ["string"]
}"""

SCREEN_USER_TEMPLATE = """Evaluate this Android screen.

INPUT
{{
  "screen": {{
    "id": "{screen_id}",
    "route": "{route}",
    "activity": "{activity}",
    "orientation": "{orientation}"
  }},
  "elements": {elements_json},
  "context": {{
    "goal": "{goal}",
    "expected_route": "{expected_route}",
    "state_ledger": {state_ledger_json},
    "coverage_gaps": {coverage_gaps_json}
  }},
  "recent_actions": {recent_actions_json},
  "prior_findings": {prior_findings_json}
}}

Screenshot is the attached image."""


# ── 5B. Per-Transition Evaluation ────────────────────────────────────────────

TRANSITION_GPT_TASK = """
Task:
Evaluate whether the action caused the correct visible change.
Focus on immediate visible delta: correct route, change/no-change, loading/
confirmation feedback, control state changes, appearance/disappearance of content,
duplicate submission signs, stuck spinner, wrong destination, unexpected overlays."""

TRANSITION_OPUS_TASK = """
Task:
Evaluate whether the action produced the intended route, state change, and data change.
Reason about action semantics, expected flow progression, persistence, idempotency,
async behavior, and navigation correctness. Detect no-op, wrong destination, stale
state, duplicate submission, missing confirmation, unexpected data mutation.
Use `hyp` for grounded root-cause hints only."""

TRANSITION_OUTPUT_SCHEMA = """{
  "prompt": "transition",
  "status": "pass|fail|uncertain",
  "summary": "string",
  "from_route": "string",
  "to_route": "string",
  "transition": "success|partial|no_change|wrong_target|unexpected|uncertain",
  "checks": [
    {"name":"action_applied","result":"...","exp":"","act":"","conf":0.0},
    {"name":"route_change","result":"...","exp":"","act":"","conf":0.0},
    {"name":"state_change","result":"...","exp":"","act":"","conf":0.0},
    {"name":"data_change","result":"...","exp":"","act":"","conf":0.0},
    {"name":"unexpected_side_effects","result":"...","exp":"none","act":"","conf":0.0}
  ],
  "observed_deltas": ["string"],
  "issues": [],
  "positives": ["string"]
}"""

TRANSITION_USER_TEMPLATE = """Evaluate this Android transition.

INPUT
{{
  "action": {action_json},
  "before": {{
    "route": "{before_route}",
    "activity": "{before_activity}",
    "image_ref": "attached:image0",
    "elements": {before_elements_json}
  }},
  "after": {{
    "route": "{after_route}",
    "activity": "{after_activity}",
    "image_ref": "attached:image1",
    "elements": {after_elements_json}
  }},
  "expectation": {{
    "expected_route": "{expected_route}",
    "expected_state_change": "{expected_state_change}"
  }},
  "prior_findings": {prior_findings_json}
}}

image0 = before, image1 = after."""


# ── 5C. Flow Checkpoint Evaluation ───────────────────────────────────────────

CHECKPOINT_GPT_TASK = """
Task:
Evaluate whether the flow is visibly progressing correctly at this checkpoint.
Focus on current-screen evidence, carried data visible on screen, missing
required controls, wrong progress indicators, incorrect enabled/disabled states."""

CHECKPOINT_OPUS_TASK = """
Task:
Evaluate whether the multi-step flow is progressing correctly and data/state
remain coherent across steps. Reason about state persistence, carried entities,
route continuity, missing confirmations, unintended resets, lost edits.
Use `hyp` for grounded root-cause hints only."""

CHECKPOINT_OUTPUT_SCHEMA = """{
  "prompt": "checkpoint",
  "status": "pass|fail|uncertain",
  "summary": "string",
  "flow_state": "on_track|degraded|blocked|uncertain",
  "checks": [
    {"name":"goal_progress","result":"...","exp":"","act":"","conf":0.0},
    {"name":"data_integrity","result":"...","exp":"","act":"","conf":0.0},
    {"name":"state_persistence","result":"...","exp":"","act":"","conf":0.0},
    {"name":"flow_continuity","result":"...","exp":"","act":"","conf":0.0}
  ],
  "entities": [{"key":"string","exp":"string","act":"string","result":"...","conf":0.0}],
  "issues": [],
  "risks": ["string"]
}"""

CHECKPOINT_USER_TEMPLATE = """Evaluate this flow checkpoint.

INPUT
{{
  "checkpoint": {{
    "id": "{checkpoint_id}",
    "name": "{checkpoint_name}",
    "goal": "{checkpoint_goal}",
    "expected_route": "{expected_route}"
  }},
  "current_screen": {{
    "id": "{screen_id}",
    "route": "{route}",
    "activity": "{activity}",
    "image_ref": "attached:image0",
    "elements": {elements_json}
  }},
  "flow_summary": {{
    "flow_id": "{flow_id}",
    "goal": "{flow_goal}",
    "completed_steps": {completed_steps_json},
    "recent_actions": {recent_actions_json},
    "state_ledger": {state_ledger_json},
    "open_findings": {prior_findings_json}
  }}
}}"""


# ── 5D. Synthesis Evaluation ─────────────────────────────────────────────────

SYNTHESIS_GPT_TASK = """
Task:
Cluster, dedupe, and compress all findings into a final report.
Merge duplicate findings with same or near-same `dk`. Be conservative on severity
escalation. Preserve evidence refs. Max 12 deduped issues."""

SYNTHESIS_OPUS_TASK = """
Task:
Cluster, dedupe, and severity-finalize all findings. Resolve conflicts by evidence
quality and user impact, not vote count. Favor findings supported across screens.
Use `hyp` for grounded root-cause hints. Be decisive on final verdict. Max 12 issues."""

SYNTHESIS_OUTPUT_SCHEMA = """{
  "prompt": "synthesis",
  "status": "pass|fail|uncertain",
  "summary": "string",
  "verdict": "ship|fix_before_ship|blocked|needs_manual_review",
  "issues": [],
  "clusters": [{"id":"string","title":"string","member_ids":[],"root_cause":"string"}],
  "coverage_gaps": ["string"],
  "positives": ["string"],
  "stats": {"screens":0,"transitions":0,"checkpoints":0,"raw_findings":0,"deduped_findings":0}
}"""

SYNTHESIS_USER_TEMPLATE = """Synthesize the final QC report.

INPUT
{{
  "session": {{
    "package": "{package}",
    "depth": "{depth}",
    "duration_s": {duration_s}
  }},
  "screen_inventory": {screen_inventory_json},
  "all_findings": {all_findings_json},
  "action_trace": {action_trace_json},
  "coverage": {{
    "screens_discovered": {screens_discovered},
    "screens_tested": {screens_tested},
    "flows_executed": {flows_executed},
    "gaps": {coverage_gaps_json}
  }}
}}

Deduplicate by dk first, then by same symptom/target if dk differs slightly.
Severity reflects final user impact, not raw count."""


# ── 5E. Next-Action Decision ─────────────────────────────────────────────────

NEXT_ACTION_GPT_TASK = """
Task:
Pick the best next action to maximize coverage gain and issue detection.
Favor deterministic actions: dismiss dialog, tap primary CTA, open uncovered branch,
validate disabled/enabled state, back out of dead ends.
Up to 5 candidates. Be fast and pragmatic.

Action kinds: tap | type | scroll | swipe | back | wait | long_press | finish_flow | stop"""

NEXT_ACTION_OPUS_TASK = """
Task:
Pick the best next action to maximize coverage gain and issue detection.
Also account for unresolved hypotheses and branch value over next 2-3 steps.
If two actions are close, prefer the one that falsifies the highest-risk hypothesis.
Up to 5 candidates.

Action kinds: tap | type | scroll | swipe | back | wait | long_press | finish_flow | stop"""

NEXT_ACTION_OUTPUT_SCHEMA = """{
  "prompt": "next_action",
  "status": "ready|blocked|done",
  "summary": "string",
  "pick": "na1",
  "candidates": [
    {"id":"na1","kind":"tap","target":{"el":"","text":"","dir":"none"},"expect":"string","reason":"string","coverage":"navigation","risk":"low","conf":0.0,"fp":"tap|route|target|none|none"}
  ],
  "stop_reason": ""
}"""

NEXT_ACTION_USER_TEMPLATE = """Choose the next QC action.

INPUT
{{
  "current_screen": {{
    "id": "{screen_id}",
    "route": "{route}",
    "activity": "{activity}",
    "image_ref": "attached:image0",
    "elements": {elements_json}
  }},
  "goal": "{goal}",
  "coverage_gaps": {coverage_gaps_json},
  "recent_actions": {recent_actions_json},
  "open_findings": {prior_findings_json},
  "constraints": {{
    "allow_destructive": {allow_destructive},
    "allow_back": true,
    "time_budget_sec": {time_budget_sec}
  }}
}}"""


# ── Prompt Builder ───────────────────────────────────────────────────────────

PROMPT_CONFIGS = {
    "screen": {
        "gpt_task": SCREEN_GPT_TASK,
        "opus_task": SCREEN_OPUS_TASK,
        "output_schema": SCREEN_OUTPUT_SCHEMA,
        "user_template": SCREEN_USER_TEMPLATE,
        "max_issues": 8,
    },
    "transition": {
        "gpt_task": TRANSITION_GPT_TASK,
        "opus_task": TRANSITION_OPUS_TASK,
        "output_schema": TRANSITION_OUTPUT_SCHEMA,
        "user_template": TRANSITION_USER_TEMPLATE,
        "max_issues": 8,
    },
    "checkpoint": {
        "gpt_task": CHECKPOINT_GPT_TASK,
        "opus_task": CHECKPOINT_OPUS_TASK,
        "output_schema": CHECKPOINT_OUTPUT_SCHEMA,
        "user_template": CHECKPOINT_USER_TEMPLATE,
        "max_issues": 8,
    },
    "synthesis": {
        "gpt_task": SYNTHESIS_GPT_TASK,
        "opus_task": SYNTHESIS_OPUS_TASK,
        "output_schema": SYNTHESIS_OUTPUT_SCHEMA,
        "user_template": SYNTHESIS_USER_TEMPLATE,
        "max_issues": 12,
    },
    "next_action": {
        "gpt_task": NEXT_ACTION_GPT_TASK,
        "opus_task": NEXT_ACTION_OPUS_TASK,
        "output_schema": NEXT_ACTION_OUTPUT_SCHEMA,
        "user_template": NEXT_ACTION_USER_TEMPLATE,
        "max_issues": 0,
    },
}


def build_system_prompt(prompt_type: str, model: str) -> str:
    """Build the full system prompt for a given prompt type and model."""
    config = PROMPT_CONFIGS[prompt_type]
    task = config["gpt_task"] if model == "gpt" else config["opus_task"]
    preamble = SHARED_PREAMBLE.format(max_issues=config["max_issues"])
    schema = config["output_schema"]
    return f"{preamble}\n{task}\n\nReturn this exact top-level shape:\n{schema}"


def build_user_prompt(prompt_type: str, **kwargs: Any) -> str:
    """Build the user prompt by filling in template placeholders."""
    config = PROMPT_CONFIGS[prompt_type]
    template = config["user_template"]
    # Convert any dict/list values to JSON strings
    formatted = {}
    for k, v in kwargs.items():
        if isinstance(v, (dict, list)):
            formatted[k] = json.dumps(v, default=str)
        elif isinstance(v, bool):
            formatted[k] = "true" if v else "false"
        else:
            formatted[k] = str(v)
    return template.format(**formatted)


# ── Context Compression ──────────────────────────────────────────────────────

def compress_findings(findings: list[dict], max_items: int = 10) -> list[dict]:
    """Compress findings list to compact format for prompt context."""
    # Sort by severity (most severe first)
    sev_order = {"blocker": 0, "critical": 1, "major": 2, "minor": 3, "nit": 4}
    sorted_f = sorted(findings, key=lambda f: sev_order.get(f.get("sev", "nit"), 4))
    return [
        {"dk": f.get("dk", ""), "sev": f.get("sev", ""), "title": f.get("title", "")[:60]}
        for f in sorted_f[:max_items]
    ]


def compress_elements(elements: list[dict]) -> dict:
    """Reduce element tree to essentials for prompt context."""
    clickable = []
    text_visible = []
    scrollable = False
    for el in elements:
        text = el.get("text", "") or el.get("label", "")
        if text:
            text_visible.append(text)
        if el.get("clickable"):
            bounds = el.get("bounds", [])
            clickable.append({"text": text[:40], "bounds": bounds})
        if el.get("scrollable"):
            scrollable = True
    return {
        "clickable": clickable[:20],
        "text_visible": text_visible[:30],
        "scrollable": scrollable,
        "total_elements": len(elements),
    }


def compress_actions(actions: list[dict], max_items: int = 8) -> list[dict]:
    """Keep only recent actions in compact form."""
    return [
        {"id": a.get("id", ""), "kind": a.get("kind", ""), "target": a.get("target", ""), "result": a.get("result", "")}
        for a in actions[-max_items:]
    ]

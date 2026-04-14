---
name: android-qc
description: >
  Comprehensive Android app QC testing using dual-model parallel evaluation.
  Use this skill when asked to test the app, run QC, find bugs on the emulator,
  or perform quality checks on the Android app. Dispatches GPT 5.4 (xhigh reasoning)
  and Opus 4.6 1M (high reasoning) evaluator agents in parallel on every screen
  and transition, reconciling their findings into a structured report.
---

# Android QC Skill

Systematic quality control of the Android app running on an emulator.
Dispatches two evaluator sub-agents (GPT 5.4 xhigh + Opus 4.6 1M high) in
parallel on every screen and transition. Disagreement between models is itself
a high-value signal.

## Architecture

```
You (orchestrator)
  │
  ├── MCP emulator tools (device control)
  ├── tools/qc/ Python module (state + reconciliation)
  │
  └── For each screen/transition, dispatch two background task agents:
      ├── GPT 5.4 evaluator  (xhigh reasoning · fast visual triage)
      └── Opus 4.6 1M evaluator (high reasoning · deep state/logic reasoning)
          │
          └── Reconcile → findings[]
```

## How to Run

### Step 1: Initialize state

```python
import sys; sys.path.insert(0, '.')
from tools.qc.models import (
    Finding, Severity, IssueType, Phase, EvalResult,
    Evidence, FindingStatus, ScreenSignature
)
from tools.qc.reconcile import reconcile_findings, merge_next_actions, add_tool_finding
from tools.qc.prompts import compress_elements, compress_findings, compress_actions
from tools.qc.state import QCState
from tools.qc.orchestrator import build_evaluator_dispatch

state = QCState(
    package_name="com.adsamcik.tracker.debug",
    depth="standard",
    time_budget_seconds=1800,
)
```

### Step 2: Bootstrap

```
android_claim_device(device="emulator-5554", agentId="qc", workload="extensive")
android_terminate_app(device, "com.adsamcik.tracker.debug")
android_set_orientation(device, "portrait")
android_launch_app(device, "com.adsamcik.tracker.debug")
android_wait_for_element(device, timeoutMs=15000)
android_detect_crash(device)
```

Capture baseline:
```
screenshot = android_take_screenshot(device)
elements = android_list_elements(device)
activity = android_get_current_activity(device)
```

### Step 3: Evaluate (repeat for every screen)

Build dispatch prompts using the Python helper:

```python
elements_compressed = compress_elements(raw_elements_list)
context = state.prompt_context()

gpt_prompt = build_evaluator_dispatch(
    "screen", "gpt",
    screenshot_description="<describe what you see>",
    elements_compressed=elements_compressed,
    context=context,
    screen_id="dashboard", route="home", activity=activity_name
)

opus_prompt = build_evaluator_dispatch(
    "screen", "opus",
    screenshot_description="<describe what you see>",
    elements_compressed=elements_compressed,
    context=context,
    screen_id="dashboard", route="home", activity=activity_name
)
```

Dispatch both in parallel:
```
gpt_agent = task(agent_type="qc-evaluator", model="gpt-5.4",
                 mode="background", description="QC eval: dashboard",
                 prompt=gpt_prompt)

opus_agent = task(agent_type="qc-evaluator", model="claude-opus-4.6-1m",
                  mode="background", description="QC eval: dashboard",
                  prompt=opus_prompt)
```

Wait for both, parse JSON, reconcile:
```python
gpt_eval = EvalResult.from_json("gpt", "screen", gpt_json)
opus_eval = EvalResult.from_json("opus", "screen", opus_json)
findings, disagreements = reconcile_findings(gpt_eval, opus_eval, "dashboard", Phase.SCREEN_QC)
state.add_findings(findings)
state.add_disagreements(disagreements)
```

### Step 4: Repeat across phases

1. **Bootstrap** — launch, detect crashes
2. **Discover** — BFS screen enumeration via nav affordances
3. **Screen QC** — per-screen dual evaluation + control interaction
4. **Flow validation** — cross-screen user journeys (tracking, settings, export)
5. **Resilience** — orientation, lifecycle, stress
6. **Synthesis** — deduplicate, cluster, finalize report

### Step 5: Generate report

```python
report = state.build_report("qc-run-001", device="emulator-5554", android_version="16")
print(report.to_json())
```

## Reconciliation Rules

| GPT says | Opus says | Resolution |
|----------|-----------|------------|
| Issue | Issue | **Auto-confirm** at higher severity |
| Issue | No issue | **Suspected** |
| No issue | Issue | **Confirmed** (Opus caught deeper issue) |
| No issue | No issue | **Clean** |

## Severity Scale

- `blocker` — app crashes, stuck, unusable
- `critical` — core behavior wrong, data loss
- `major` — primary task impaired, wrong data
- `minor` — workaround exists, secondary issue
- `nit` — cosmetic

## Files

| Path | Purpose |
|------|---------|
| `tools/qc/models.py` | Data models, taxonomy, enums |
| `tools/qc/prompts.py` | Prompt templates + compression |
| `tools/qc/reconcile.py` | Finding reconciliation logic |
| `tools/qc/state.py` | State accumulator + report generation |
| `tools/qc/orchestrator.py` | `build_evaluator_dispatch()` prompt builder |
| `tools/qc/run.py` | CLI: `python -m tools.qc.run --help` |

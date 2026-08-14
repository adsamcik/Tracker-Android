---
name: android-qc
description: >
  Evidence-driven Android app QC on one representative emulator configuration.
  Use when asked to test the app, run QC, find emulator-visible bugs, or perform
  interactive quality checks. Adapts to available device and evaluator tools.
---

# Android QC skill

Run systematic QC against a single representative emulator/API configuration. Record the device,
API level, orientation, app build, and tested commit in the report. Do not expand the run into an
API/device matrix unless the user explicitly requests one.

## Capability discovery

1. Inventory the device-control, screenshot, element-inspection, log/crash-detection, and evaluator
   capabilities available in the current environment.
2. Use the environment's real tool and dispatch interfaces. Do not assume MCP command names,
   evaluator model identifiers, background-task syntax, or a particular agent type.
3. One evaluator is sufficient. If a second independent evaluator is available, it may be used to
   broaden review, but lack of a second evaluator must not block QC.
4. If required device-control capabilities are unavailable, report the missing capability instead
   of inventing a command.

## Evidence standard

Evaluator output is a hypothesis, regardless of evaluator identity or confidence. Confirm a finding
only when it is supported by at least one inspectable source: code, a screenshot/element reference,
logs or crash output, or interactive reproduction. Agreement between evaluators can prioritize a
hypothesis but does not replace evidence. Findings created from direct tool facts, such as a captured
crash, may be confirmed immediately.

## Workflow

1. Build/install the requested app variant and claim one representative emulator.
2. Establish a clean baseline: stop and launch the app, set the chosen orientation, detect launch
   failures, and capture the first screenshot, element tree, activity, and relevant logs.
3. Discover screens breadth-first and record actions, routes, state changes, and evidence references.
4. Evaluate screens and transitions using the available evaluator(s). Use the visual focus for
   screenshot/layout inspection and the state focus for cross-screen/state reasoning when useful.
5. Exercise representative core flows, then resilience checks that fit the time budget on the same
   emulator configuration.
6. Reconcile hypotheses, reproduce material findings interactively where possible, deduplicate by
   `dk`, and report coverage gaps and the recorded device configuration.

The Python package under `tools/qc` provides data models, prompt builders, reconciliation, state,
and report generation. It does not dispatch agents or control devices.

```python
from tools.qc.models import EvalResult, Phase
from tools.qc.orchestrator import build_evaluator_dispatch
from tools.qc.reconcile import reconcile_findings

visual_prompt = build_evaluator_dispatch(
    "screen",
    "visual",
    screenshot_description="<describe only what is visible>",
    elements_compressed=elements,
    context=context,
    screen_id="dashboard",
    route="home/dashboard",
    activity=activity,
)

primary = EvalResult.from_json("available-evaluator", "screen", primary_json)
secondary = (
    EvalResult.from_json("second-available-evaluator", "screen", secondary_json)
    if secondary_json is not None
    else None
)
findings, disagreements = reconcile_findings(
    primary,
    secondary,
    "dashboard",
    Phase.SCREEN_QC,
)
```

Reconciliation behavior:

| Inputs | Result |
| --- | --- |
| One evaluator reports a finding | Suspected hypothesis |
| Two evaluators match without cited evidence | Suspected hypothesis |
| Two evaluators match with screenshot/element/text/action evidence | Confirmed |
| Direct code, log, crash, or interactive reproduction evidence | Confirmed |
| No evaluator reports a problem | No model finding; report coverage limits separately |

Run focused helper tests with `python -m unittest tools.qc.test_reconcile`.

"""
android_qc — Data models and shared taxonomy for the unified QC skill.
"""
from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Any


# ── Shared Taxonomy ──────────────────────────────────────────────────────────

class Severity(str, Enum):
    BLOCKER = "blocker"
    CRITICAL = "critical"
    MAJOR = "major"
    MINOR = "minor"
    NIT = "nit"

    @property
    def rank(self) -> int:
        return {
            "blocker": 0, "critical": 1, "major": 2, "minor": 3, "nit": 4
        }[self.value]

    def __lt__(self, other: Severity) -> bool:
        return self.rank < other.rank  # lower rank = higher severity

    @staticmethod
    def higher(a: Severity, b: Severity) -> Severity:
        return a if a.rank <= b.rank else b


class IssueType(str, Enum):
    VISUAL = "visual"
    LAYOUT = "layout"
    COPY = "copy"
    ACCESSIBILITY = "accessibility"
    INTERACTION = "interaction"
    NAVIGATION = "navigation"
    STATE = "state"
    DATA = "data"
    VALIDATION = "validation"
    PERFORMANCE = "performance"
    RESILIENCE = "resilience"
    PERMISSIONS = "permissions"
    SYSTEM_DIALOG = "system_dialog"
    LOGIC = "logic"
    CONSISTENCY = "consistency"


class FindingStatus(str, Enum):
    CANDIDATE = "candidate"
    SUSPECTED = "suspected"
    CONFIRMED = "confirmed"
    DISMISSED = "dismissed"


class FlowState(str, Enum):
    ON_TRACK = "on_track"
    DEGRADED = "degraded"
    BLOCKED = "blocked"
    UNCERTAIN = "uncertain"


class TransitionResult(str, Enum):
    SUCCESS = "success"
    PARTIAL = "partial"
    NO_CHANGE = "no_change"
    WRONG_TARGET = "wrong_target"
    UNEXPECTED = "unexpected"
    UNCERTAIN = "uncertain"


class Verdict(str, Enum):
    SHIP = "ship"
    FIX_BEFORE_SHIP = "fix_before_ship"
    BLOCKED = "blocked"
    NEEDS_MANUAL_REVIEW = "needs_manual_review"


class Phase(str, Enum):
    BOOTSTRAP = "bootstrap"
    DISCOVER = "discover"
    SCREEN_QC = "screen_qc"
    FLOW_VALIDATION = "flow_validation"
    RESILIENCE = "resilience"
    SYNTHESIS = "synthesis"


class ActionKind(str, Enum):
    TAP = "tap"
    TYPE = "type"
    SCROLL = "scroll"
    SWIPE = "swipe"
    BACK = "back"
    WAIT = "wait"
    LONG_PRESS = "long_press"
    FINISH_FLOW = "finish_flow"
    STOP = "stop"


# ── Dedupe Key ───────────────────────────────────────────────────────────────

def make_dk(route: str, issue_type: str, target: str, symptom: str) -> str:
    """Generate a normalized dedupe key: <route>|<type>|<target>|<symptom>"""
    parts = [
        route.lower().replace(" ", "-"),
        issue_type.lower(),
        target.lower().replace(" ", "-"),
        symptom.lower().replace(" ", "-"),
    ]
    return "|".join(parts)


def dk_similarity(dk1: str, dk2: str) -> float:
    """Compute similarity between two dedupe keys (0.0 - 1.0)."""
    parts1 = dk1.split("|")
    parts2 = dk2.split("|")
    if len(parts1) != 4 or len(parts2) != 4:
        return 0.0
    matches = sum(1 for a, b in zip(parts1, parts2) if a == b)
    return matches / 4.0


# ── Evidence ─────────────────────────────────────────────────────────────────

@dataclass
class Evidence:
    img: list[str] = field(default_factory=list)
    el: list[str] = field(default_factory=list)
    txt: list[str] = field(default_factory=list)
    act: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)


# ── Check ────────────────────────────────────────────────────────────────────

@dataclass
class Check:
    name: str
    result: str  # pass|fail|uncertain
    exp: str = ""
    act: str = ""
    conf: float = 0.0

    def to_dict(self) -> dict:
        return asdict(self)


# ── Issue / Finding ──────────────────────────────────────────────────────────

@dataclass
class Finding:
    id: str
    sev: Severity
    type: IssueType
    title: str
    exp: str = ""
    act: str = ""
    why: str = ""
    hyp: str = ""
    conf: float = 0.0
    dk: str = ""
    ev: Evidence = field(default_factory=Evidence)
    status: FindingStatus = FindingStatus.CANDIDATE
    source: str = ""  # evaluator identifier(s), or "tool"
    screen_id: str = ""
    phase: Phase = Phase.SCREEN_QC
    model_agreement: dict = field(default_factory=dict)
    repro: list[str] = field(default_factory=list)
    cluster_id: str | None = None

    def to_dict(self) -> dict:
        d = asdict(self)
        d["sev"] = self.sev.value
        d["type"] = self.type.value
        d["status"] = self.status.value
        d["phase"] = self.phase.value
        d["ev"] = self.ev.to_dict()
        return d


# ── Screen ───────────────────────────────────────────────────────────────────

@dataclass
class ScreenSignature:
    screen_id: str
    activity: str
    path: list[str]
    dominant_text: list[str] = field(default_factory=list)
    interactive_count: int = 0
    scrollable: bool = False
    risk_score: float = 0.5
    visited: bool = False
    tested: bool = False
    blocked: bool = False
    block_reason: str = ""

    @property
    def signature_hash(self) -> str:
        content = f"{self.activity}|{'|'.join(sorted(self.dominant_text[:5]))}"
        return hashlib.md5(content.encode()).hexdigest()[:8]

    def to_dict(self) -> dict:
        d = asdict(self)
        d["signature_hash"] = self.signature_hash
        return d


# ── Action Trace ─────────────────────────────────────────────────────────────

@dataclass
class ActionRecord:
    id: str
    kind: str
    target: str = ""
    input_text: str = ""
    result: str = ""
    screen_before: str = ""
    screen_after: str = ""
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return asdict(self)

    def compact(self) -> dict:
        return {"id": self.id, "kind": self.kind, "target": self.target, "result": self.result}


# ── Navigation Graph ─────────────────────────────────────────────────────────

@dataclass
class NavEdge:
    from_screen: str
    to_screen: str
    action: str  # e.g. "tap:Settings"
    works: bool = True

    def to_dict(self) -> dict:
        return asdict(self)


# ── Model Evaluation Results ─────────────────────────────────────────────────

@dataclass
class EvalResult:
    """Output from a single model evaluation (screen, transition, checkpoint, etc.)."""
    model: str  # caller-supplied evaluator identifier
    prompt_type: str  # "screen", "transition", "checkpoint", "synthesis", "next_action"
    status: str = "uncertain"  # pass|fail|uncertain
    summary: str = ""
    checks: list[Check] = field(default_factory=list)
    issues: list[dict] = field(default_factory=list)  # raw issue dicts from model
    positives: list[str] = field(default_factory=list)
    gaps: list[str] = field(default_factory=list)
    raw_json: dict = field(default_factory=dict)

    # Transition-specific
    transition: str = ""
    from_route: str = ""
    to_route: str = ""
    observed_deltas: list[str] = field(default_factory=list)

    # Checkpoint-specific
    flow_state: str = ""
    entities: list[dict] = field(default_factory=list)
    risks: list[str] = field(default_factory=list)

    # Next-action specific
    pick: str = ""
    candidates: list[dict] = field(default_factory=list)
    stop_reason: str = ""

    # Synthesis-specific
    verdict: str = ""
    clusters: list[dict] = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    @staticmethod
    def from_json(model: str, prompt_type: str, data: dict) -> EvalResult:
        """Parse a model's JSON output into an EvalResult."""
        checks = [Check(**c) for c in data.get("checks", [])]
        result = EvalResult(
            model=model,
            prompt_type=prompt_type,
            status=data.get("status", "uncertain"),
            summary=data.get("summary", ""),
            checks=checks,
            issues=data.get("issues", []),
            positives=data.get("positives", []),
            gaps=data.get("gaps", []),
            raw_json=data,
        )
        # Type-specific fields
        if prompt_type == "transition":
            result.transition = data.get("transition", "")
            result.from_route = data.get("from_route", "")
            result.to_route = data.get("to_route", "")
            result.observed_deltas = data.get("observed_deltas", [])
        elif prompt_type == "checkpoint":
            result.flow_state = data.get("flow_state", "")
            result.entities = data.get("entities", [])
            result.risks = data.get("risks", [])
        elif prompt_type == "next_action":
            result.pick = data.get("pick", "")
            result.candidates = data.get("candidates", [])
            result.stop_reason = data.get("stop_reason", "")
        elif prompt_type == "synthesis":
            result.verdict = data.get("verdict", "")
            result.clusters = data.get("clusters", [])
            result.stats = data.get("stats", {})
        return result


# ── Cluster ──────────────────────────────────────────────────────────────────

@dataclass
class IssueCluster:
    id: str
    title: str
    member_ids: list[str] = field(default_factory=list)
    root_cause: str = ""

    def to_dict(self) -> dict:
        return asdict(self)


# ── Disagreement Record ──────────────────────────────────────────────────────

@dataclass
class Disagreement:
    screen_id: str
    evaluator_a: str
    evaluator_a_finding: dict
    evaluator_b: str
    evaluator_b_finding: dict
    resolution: str
    reasoning: str = ""

    def to_dict(self) -> dict:
        return asdict(self)


# ── Coverage Summary ─────────────────────────────────────────────────────────

@dataclass
class CoverageSummary:
    screens_discovered: int = 0
    screens_tested: int = 0
    flows_executed: int = 0
    orientations_checked: list[str] = field(default_factory=list)
    gaps: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)


# ── QC Report ────────────────────────────────────────────────────────────────

@dataclass
class QCReport:
    run_id: str
    package: str
    depth: str
    device: str = ""
    android_version: str = ""
    duration_seconds: float = 0.0
    verdict: Verdict = Verdict.NEEDS_MANUAL_REVIEW
    coverage: CoverageSummary = field(default_factory=CoverageSummary)
    issues: list[Finding] = field(default_factory=list)
    clusters: list[IssueCluster] = field(default_factory=list)
    disagreements: list[Disagreement] = field(default_factory=list)
    recommendations: list[str] = field(default_factory=list)
    evidence_index: list[dict] = field(default_factory=list)

    @property
    def severity_counts(self) -> dict[str, int]:
        counts = {s.value: 0 for s in Severity}
        for issue in self.issues:
            if issue.status == FindingStatus.CONFIRMED:
                counts[issue.sev.value] += 1
        return counts

    def to_dict(self) -> dict:
        return {
            "metadata": {
                "run_id": self.run_id,
                "package": self.package,
                "depth": self.depth,
                "device": self.device,
                "android_version": self.android_version,
                "duration_seconds": self.duration_seconds,
            },
            "coverage": self.coverage.to_dict(),
            "summary": {
                "verdict": self.verdict.value,
                **self.severity_counts,
                "top_risks": self.recommendations[:3],
            },
            "issues": [i.to_dict() for i in self.issues if i.status == FindingStatus.CONFIRMED],
            "suspected": [i.to_dict() for i in self.issues if i.status == FindingStatus.SUSPECTED],
            "clusters": [c.to_dict() for c in self.clusters],
            "model_disagreements": [d.to_dict() for d in self.disagreements],
            "recommendations": self.recommendations,
            "evidence_index": self.evidence_index,
        }

    def to_json(self, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), indent=indent, default=str)

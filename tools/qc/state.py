"""
android_qc — State accumulator.

Manages the full QC session state: screen inventory, navigation graph,
action trace, findings, state ledger, coverage tracking.
Provides context compression for prompt payloads.
"""
from __future__ import annotations

import time
import json
from dataclasses import dataclass, field
from typing import Any

from .models import (
    ScreenSignature, NavEdge, ActionRecord, Finding, FindingStatus,
    Severity, IssueCluster, Disagreement, CoverageSummary,
    QCReport, Verdict, Phase, Evidence,
)
from .prompts import compress_findings, compress_actions


# ── QC State ─────────────────────────────────────────────────────────────────

@dataclass
class QCState:
    """Full state for a QC run."""
    # Config
    package_name: str
    depth: str = "standard"
    time_budget_seconds: float = 1800  # 30 min default

    # Timing
    start_time: float = field(default_factory=time.time)

    # Screen inventory
    screens: dict[str, ScreenSignature] = field(default_factory=dict)
    nav_edges: list[NavEdge] = field(default_factory=list)

    # Action trace
    actions: list[ActionRecord] = field(default_factory=list)
    _action_counter: int = 0

    # Findings
    findings: list[Finding] = field(default_factory=list)
    disagreements: list[Disagreement] = field(default_factory=list)

    # State ledger (persistent key-value facts)
    state_ledger: dict[str, Any] = field(default_factory=dict)

    # Coverage
    flows_executed: int = 0
    orientations_checked: list[str] = field(default_factory=list)

    # Evidence
    evidence_index: list[dict] = field(default_factory=list)
    _screenshot_counter: int = 0

    # Current phase
    current_phase: Phase = Phase.BOOTSTRAP

    # ── Screen Management ────────────────────────────────────────────────

    def add_screen(self, screen: ScreenSignature) -> bool:
        """Add a screen. Returns True if it's new."""
        sig_hash = screen.signature_hash
        if sig_hash in self.screens:
            return False
        self.screens[sig_hash] = screen
        return True

    def get_screen(self, sig_hash: str) -> ScreenSignature | None:
        return self.screens.get(sig_hash)

    def mark_screen_tested(self, sig_hash: str):
        if sig_hash in self.screens:
            self.screens[sig_hash].tested = True

    def mark_screen_blocked(self, sig_hash: str, reason: str):
        if sig_hash in self.screens:
            self.screens[sig_hash].blocked = True
            self.screens[sig_hash].block_reason = reason

    def screens_by_risk(self) -> list[ScreenSignature]:
        """Return screens sorted by risk score (highest first), untested first."""
        return sorted(
            self.screens.values(),
            key=lambda s: (s.tested, s.blocked, -s.risk_score),
        )

    def is_known_screen(self, activity: str, dominant_text: list[str]) -> str | None:
        """Check if a screen signature already exists. Returns hash if found."""
        test = ScreenSignature(screen_id="test", activity=activity, path=[], dominant_text=dominant_text)
        sig_hash = test.signature_hash
        return sig_hash if sig_hash in self.screens else None

    # ── Navigation Graph ─────────────────────────────────────────────────

    def add_nav_edge(self, from_screen: str, to_screen: str, action: str, works: bool = True):
        self.nav_edges.append(NavEdge(from_screen, to_screen, action, works))

    # ── Action Trace ─────────────────────────────────────────────────────

    def record_action(self, kind: str, target: str = "", input_text: str = "",
                      result: str = "", screen_before: str = "", screen_after: str = "") -> str:
        """Record an action. Returns the action ID."""
        self._action_counter += 1
        action_id = f"a{self._action_counter}"
        self.actions.append(ActionRecord(
            id=action_id, kind=kind, target=target, input_text=input_text,
            result=result, screen_before=screen_before, screen_after=screen_after,
        ))
        return action_id

    # ── Evidence ─────────────────────────────────────────────────────────

    def record_evidence(self, ref: str, phase: str, screen: str, evidence_type: str = "screenshot"):
        self.evidence_index.append({
            "ref": ref, "phase": phase, "screen": screen, "type": evidence_type,
        })

    def next_screenshot_id(self) -> str:
        self._screenshot_counter += 1
        return f"shot_{self._screenshot_counter:03d}"

    # ── Findings ─────────────────────────────────────────────────────────

    def add_findings(self, new_findings: list[Finding]):
        """Add findings, deduplicating by dk against existing findings."""
        existing_dks = {f.dk for f in self.findings if f.dk}
        for f in new_findings:
            if f.dk and f.dk in existing_dks:
                # Update existing if new finding has higher severity
                for i, existing in enumerate(self.findings):
                    if existing.dk == f.dk:
                        if f.sev.rank < existing.sev.rank:
                            self.findings[i] = f
                        break
            else:
                self.findings.append(f)
                if f.dk:
                    existing_dks.add(f.dk)

    def add_disagreements(self, new_disagreements: list[Disagreement]):
        self.disagreements.extend(new_disagreements)

    @property
    def confirmed_findings(self) -> list[Finding]:
        return [f for f in self.findings if f.status == FindingStatus.CONFIRMED]

    @property
    def suspected_findings(self) -> list[Finding]:
        return [f for f in self.findings if f.status == FindingStatus.SUSPECTED]

    @property
    def severity_counts(self) -> dict[str, int]:
        counts = {s.value: 0 for s in Severity}
        for f in self.confirmed_findings:
            counts[f.sev.value] += 1
        return counts

    # ── Time Budget ──────────────────────────────────────────────────────

    @property
    def elapsed_seconds(self) -> float:
        return time.time() - self.start_time

    @property
    def remaining_seconds(self) -> float:
        return max(0, self.time_budget_seconds - self.elapsed_seconds)

    def within_budget(self, margin_seconds: float = 60) -> bool:
        return self.remaining_seconds > margin_seconds

    @property
    def budget_fraction(self) -> float:
        return min(1.0, self.elapsed_seconds / self.time_budget_seconds)

    # ── Context for Prompts ──────────────────────────────────────────────

    def prompt_context(self) -> dict:
        """Build compressed context for model prompts."""
        return {
            "state_ledger": self.state_ledger,
            "coverage_gaps": self._coverage_gaps(),
            "recent_actions": compress_actions([a.compact() for a in self.actions]),
            "prior_findings": compress_findings([f.to_dict() for f in self.findings]),
        }

    def _coverage_gaps(self) -> list[str]:
        gaps = []
        untested = [s for s in self.screens.values() if not s.tested and not s.blocked]
        if untested:
            gaps.append(f"{len(untested)} screens not yet tested")
        blocked = [s for s in self.screens.values() if s.blocked]
        if blocked:
            gaps.extend(f"{s.screen_id} blocked: {s.block_reason}" for s in blocked)
        if "landscape" not in self.orientations_checked:
            gaps.append("Landscape orientation not checked")
        if self.flows_executed == 0:
            gaps.append("No end-to-end flows executed")
        return gaps

    # ── Depth Controls ───────────────────────────────────────────────────

    @property
    def controls_per_screen(self) -> int:
        return {"smoke": 0, "standard": 5, "deep": 20}.get(self.depth, 5)

    @property
    def max_flows(self) -> int:
        return {"smoke": 2, "standard": 8, "deep": 12}.get(self.depth, 8)

    @property
    def should_check_orientation(self) -> bool:
        return self.depth != "smoke"

    @property
    def should_stress_test(self) -> bool:
        return self.depth != "smoke"

    # ── Report Generation ────────────────────────────────────────────────

    def build_report(self, run_id: str, device: str = "", android_version: str = "") -> QCReport:
        """Build the final QC report from accumulated state."""
        coverage = CoverageSummary(
            screens_discovered=len(self.screens),
            screens_tested=sum(1 for s in self.screens.values() if s.tested),
            flows_executed=self.flows_executed,
            orientations_checked=self.orientations_checked,
            gaps=self._coverage_gaps(),
        )

        # Determine verdict
        sev = self.severity_counts
        if sev.get("blocker", 0) > 0:
            verdict = Verdict.BLOCKED
        elif sev.get("critical", 0) > 0 or sev.get("major", 0) >= 3:
            verdict = Verdict.FIX_BEFORE_SHIP
        elif sev.get("major", 0) > 0 or sev.get("minor", 0) >= 5:
            verdict = Verdict.NEEDS_MANUAL_REVIEW
        else:
            verdict = Verdict.SHIP

        # Build recommendations from top findings
        recommendations = []
        for f in sorted(self.confirmed_findings, key=lambda x: x.sev.rank):
            if len(recommendations) >= 5:
                break
            recommendations.append(f"Fix: {f.title} [{f.sev.value}]")

        return QCReport(
            run_id=run_id,
            package=self.package_name,
            depth=self.depth,
            device=device,
            android_version=android_version,
            duration_seconds=self.elapsed_seconds,
            verdict=verdict,
            coverage=coverage,
            issues=self.confirmed_findings + self.suspected_findings,
            clusters=[],  # populated by synthesis phase
            disagreements=self.disagreements,
            recommendations=recommendations,
            evidence_index=self.evidence_index,
        )

    # ── Synthesis Dossier ────────────────────────────────────────────────

    def build_dossier(self) -> dict:
        """Build the full dossier for synthesis evaluation."""
        return {
            "package": self.package_name,
            "depth": self.depth,
            "duration_s": int(self.elapsed_seconds),
            "screen_inventory": [s.to_dict() for s in self.screens.values()],
            "all_findings": [f.to_dict() for f in self.findings],
            "action_trace": [a.compact() for a in self.actions],
            "screens_discovered": len(self.screens),
            "screens_tested": sum(1 for s in self.screens.values() if s.tested),
            "flows_executed": self.flows_executed,
            "coverage_gaps": self._coverage_gaps(),
        }

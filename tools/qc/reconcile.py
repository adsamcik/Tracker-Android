"""
android_qc — Reconciliation engine.

Takes two EvalResults (GPT + Opus) and produces merged findings,
resolved severity, and next-action decisions.
"""
from __future__ import annotations

from .models import (
    Finding, FindingStatus, Severity, IssueType, Evidence,
    Disagreement, EvalResult, Phase, dk_similarity,
)


# ── Finding Reconciliation ───────────────────────────────────────────────────

def reconcile_findings(
    gpt_eval: EvalResult,
    opus_eval: EvalResult,
    screen_id: str = "",
    phase: Phase = Phase.SCREEN_QC,
) -> tuple[list[Finding], list[Disagreement]]:
    """
    Reconcile findings from GPT and Opus evaluations.

    Returns (merged_findings, disagreements).
    """
    gpt_issues = gpt_eval.issues or []
    opus_issues = opus_eval.issues or []

    findings: list[Finding] = []
    disagreements: list[Disagreement] = []

    # Index by dk for matching
    gpt_by_dk = {i.get("dk", ""): i for i in gpt_issues if i.get("dk")}
    opus_by_dk = {i.get("dk", ""): i for i in opus_issues if i.get("dk")}

    matched_opus_dks: set[str] = set()

    for gpt_dk, gpt_issue in gpt_by_dk.items():
        # Try exact match
        if gpt_dk in opus_by_dk:
            opus_issue = opus_by_dk[gpt_dk]
            matched_opus_dks.add(gpt_dk)
            finding, disagreement = _merge_matched(gpt_issue, opus_issue, screen_id, phase)
            findings.append(finding)
            if disagreement:
                disagreements.append(disagreement)
            continue

        # Try fuzzy match
        best_match_dk = None
        best_sim = 0.0
        for opus_dk in opus_by_dk:
            if opus_dk in matched_opus_dks:
                continue
            sim = dk_similarity(gpt_dk, opus_dk)
            if sim >= 0.75 and sim > best_sim:
                best_sim = sim
                best_match_dk = opus_dk

        if best_match_dk:
            opus_issue = opus_by_dk[best_match_dk]
            matched_opus_dks.add(best_match_dk)
            finding, disagreement = _merge_matched(gpt_issue, opus_issue, screen_id, phase)
            findings.append(finding)
            if disagreement:
                disagreements.append(disagreement)
        else:
            # GPT-only finding → suspected
            finding = _issue_to_finding(gpt_issue, source="gpt", status=FindingStatus.SUSPECTED,
                                        screen_id=screen_id, phase=phase)
            findings.append(finding)

    # Opus-only findings → confirmed (Opus caught something GPT missed)
    for opus_dk, opus_issue in opus_by_dk.items():
        if opus_dk not in matched_opus_dks:
            finding = _issue_to_finding(opus_issue, source="opus", status=FindingStatus.CONFIRMED,
                                        screen_id=screen_id, phase=phase)
            findings.append(finding)

    # Add tool-fact findings (crashes detected by MCP tools)
    # These are auto-confirmed regardless of model output
    return findings, disagreements


def _merge_matched(
    gpt_issue: dict, opus_issue: dict, screen_id: str, phase: Phase
) -> tuple[Finding, Disagreement | None]:
    """Merge two matched issues from GPT and Opus."""
    gpt_sev = _parse_severity(gpt_issue.get("sev", "minor"))
    opus_sev = _parse_severity(opus_issue.get("sev", "minor"))
    gpt_conf = float(gpt_issue.get("conf", 0.5))
    opus_conf = float(opus_issue.get("conf", 0.5))

    # Use higher severity
    final_sev = Severity.higher(gpt_sev, opus_sev)
    final_conf = max(gpt_conf, opus_conf)

    # Prefer Opus title/hyp (deeper reasoning), GPT evidence refs
    title = opus_issue.get("title", "") or gpt_issue.get("title", "")
    hyp = opus_issue.get("hyp", "") or gpt_issue.get("hyp", "")

    # Merge evidence
    gpt_ev = gpt_issue.get("ev", {})
    opus_ev = opus_issue.get("ev", {})
    merged_ev = Evidence(
        img=_unique_list(gpt_ev.get("img", []) + opus_ev.get("img", [])),
        el=_unique_list(gpt_ev.get("el", []) + opus_ev.get("el", [])),
        txt=_unique_list(gpt_ev.get("txt", []) + opus_ev.get("txt", [])),
        act=_unique_list(gpt_ev.get("act", []) + opus_ev.get("act", [])),
    )

    finding = Finding(
        id=gpt_issue.get("id", opus_issue.get("id", "")),
        sev=final_sev,
        type=_parse_issue_type(gpt_issue.get("type", opus_issue.get("type", "logic"))),
        title=title,
        exp=opus_issue.get("exp", gpt_issue.get("exp", "")),
        act=opus_issue.get("act", gpt_issue.get("act", "")),
        why=opus_issue.get("why", gpt_issue.get("why", "")),
        hyp=hyp,
        conf=final_conf,
        dk=gpt_issue.get("dk", opus_issue.get("dk", "")),
        ev=merged_ev,
        status=FindingStatus.CONFIRMED,
        source="both",
        screen_id=screen_id,
        phase=phase,
        model_agreement={
            "gpt": {"sev": gpt_sev.value, "conf": gpt_conf},
            "opus": {"sev": opus_sev.value, "conf": opus_conf},
            "agreement": "full" if gpt_sev == opus_sev else "severity_differs",
        },
    )

    disagreement = None
    if gpt_sev != opus_sev:
        disagreement = Disagreement(
            screen_id=screen_id,
            gpt_finding={"title": gpt_issue.get("title", ""), "sev": gpt_sev.value, "conf": gpt_conf},
            opus_finding={"title": opus_issue.get("title", ""), "sev": opus_sev.value, "conf": opus_conf},
            resolution="opus_upgrade" if opus_sev.rank < gpt_sev.rank else "gpt_upgrade",
            reasoning=f"Severity resolved to {final_sev.value} (higher of the two)",
        )

    return finding, disagreement


def _issue_to_finding(
    issue: dict, source: str, status: FindingStatus, screen_id: str, phase: Phase
) -> Finding:
    """Convert a raw model issue dict into a Finding."""
    ev_data = issue.get("ev", {})
    return Finding(
        id=issue.get("id", ""),
        sev=_parse_severity(issue.get("sev", "minor")),
        type=_parse_issue_type(issue.get("type", "logic")),
        title=issue.get("title", ""),
        exp=issue.get("exp", ""),
        act=issue.get("act", ""),
        why=issue.get("why", ""),
        hyp=issue.get("hyp", ""),
        conf=float(issue.get("conf", 0.5)),
        dk=issue.get("dk", ""),
        ev=Evidence(
            img=ev_data.get("img", []),
            el=ev_data.get("el", []),
            txt=ev_data.get("txt", []),
            act=ev_data.get("act", []),
        ),
        status=status,
        source=source,
        screen_id=screen_id,
        phase=phase,
    )


def add_tool_finding(
    title: str, sev: Severity, issue_type: IssueType,
    screen_id: str, phase: Phase, evidence: Evidence | None = None,
    dk: str = "", hyp: str = "",
) -> Finding:
    """Create a finding from hard tool evidence (crash, ANR, etc.). Always confirmed."""
    return Finding(
        id=f"tool-{screen_id}-{dk[:20]}",
        sev=sev,
        type=issue_type,
        title=title,
        dk=dk,
        hyp=hyp,
        conf=1.0,
        ev=evidence or Evidence(),
        status=FindingStatus.CONFIRMED,
        source="tool",
        screen_id=screen_id,
        phase=phase,
    )


# ── Next-Action Merging ─────────────────────────────────────────────────────

def merge_next_actions(
    gpt_eval: EvalResult,
    opus_eval: EvalResult,
    open_findings: list[Finding] | None = None,
) -> list[dict]:
    """
    Merge next-action candidates from both models using utility scoring.
    Returns sorted list of action candidates (best first).
    """
    gpt_candidates = gpt_eval.candidates or []
    opus_candidates = opus_eval.candidates or []

    # Index by fingerprint
    gpt_by_fp: dict[str, dict] = {}
    for c in gpt_candidates:
        fp = c.get("fp", _make_fp(c))
        gpt_by_fp[fp] = c

    opus_by_fp: dict[str, dict] = {}
    for c in opus_candidates:
        fp = c.get("fp", _make_fp(c))
        opus_by_fp[fp] = c

    all_fps = set(gpt_by_fp.keys()) | set(opus_by_fp.keys())
    scored: list[tuple[float, dict]] = []

    for fp in all_fps:
        gpt_c = gpt_by_fp.get(fp)
        opus_c = opus_by_fp.get(fp)

        # Agreement bonus
        agreement = 0.30 if (gpt_c and opus_c) else 0.0

        # Use the candidate from whichever model provided it (or merge)
        candidate = _merge_candidate(gpt_c, opus_c) if (gpt_c and opus_c) else (gpt_c or opus_c)

        # Coverage gain (heuristic from coverage field)
        coverage_weight = {
            "navigation": 0.25, "validation": 0.20, "state": 0.20,
            "data": 0.20, "edge_case": 0.15, "confirmation": 0.10,
        }.get(candidate.get("coverage", ""), 0.15)

        # Hypothesis reduction bonus
        hyp_bonus = 0.0
        if open_findings:
            blocker_critical = any(f.sev.rank <= 1 for f in open_findings if f.status == FindingStatus.SUSPECTED)
            if blocker_critical:
                hyp_bonus = 0.15

        # Safety (inverse of risk)
        risk_score = {"low": 0.10, "medium": 0.05, "high": 0.0}.get(candidate.get("risk", "low"), 0.05)

        # Confidence from model
        conf = float(candidate.get("conf", 0.5))

        utility = agreement + coverage_weight + hyp_bonus * 0.20 + risk_score + conf * 0.15
        scored.append((utility, candidate))

    scored.sort(key=lambda x: x[0], reverse=True)
    return [c for _, c in scored[:5]]


def _make_fp(candidate: dict) -> str:
    """Generate a fingerprint for a next-action candidate."""
    kind = candidate.get("kind", "")
    target = candidate.get("target", {})
    text = target.get("text", "") if isinstance(target, dict) else ""
    direction = target.get("dir", "none") if isinstance(target, dict) else "none"
    return f"{kind}|{text}|{direction}"


def _merge_candidate(gpt_c: dict, opus_c: dict) -> dict:
    """Merge two matching next-action candidates."""
    merged = dict(gpt_c)  # start with GPT
    merged["conf"] = max(float(gpt_c.get("conf", 0.5)), float(opus_c.get("conf", 0.5)))
    # Prefer Opus reasoning (deeper)
    if opus_c.get("reason"):
        merged["reason"] = opus_c["reason"]
    return merged


# ── Helpers ──────────────────────────────────────────────────────────────────

def _parse_severity(s: str) -> Severity:
    try:
        return Severity(s.lower())
    except ValueError:
        return Severity.MINOR


def _parse_issue_type(s: str) -> IssueType:
    try:
        return IssueType(s.lower())
    except ValueError:
        return IssueType.LOGIC


def _unique_list(items: list) -> list:
    seen = set()
    result = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result

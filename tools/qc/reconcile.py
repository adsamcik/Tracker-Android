"""Evidence-based reconciliation for one or two QC evaluator results."""
from __future__ import annotations

from .models import (
    Finding, FindingStatus, Severity, IssueType, Evidence,
    Disagreement, EvalResult, Phase, dk_similarity,
)


# ── Finding Reconciliation ───────────────────────────────────────────────────

def reconcile_findings(
    primary_eval: EvalResult,
    secondary_eval: EvalResult | None = None,
    screen_id: str = "",
    phase: Phase = Phase.SCREEN_QC,
) -> tuple[list[Finding], list[Disagreement]]:
    """Reconcile evaluator hypotheses without treating evaluator identity as evidence."""
    primary_issues = primary_eval.issues or []
    secondary_issues = (secondary_eval.issues or []) if secondary_eval else []
    primary_source = _source_id(primary_eval, "evaluator-a")
    secondary_source = _source_id(secondary_eval, "evaluator-b") if secondary_eval else ""

    findings: list[Finding] = []
    disagreements: list[Disagreement] = []

    # Index by dk for matching
    primary_by_dk = {i.get("dk", ""): i for i in primary_issues if i.get("dk")}
    secondary_by_dk = {i.get("dk", ""): i for i in secondary_issues if i.get("dk")}

    matched_secondary_dks: set[str] = set()

    for primary_dk, primary_issue in primary_by_dk.items():
        # Try exact match
        if primary_dk in secondary_by_dk:
            secondary_issue = secondary_by_dk[primary_dk]
            matched_secondary_dks.add(primary_dk)
            finding, disagreement = _merge_matched(
                primary_issue,
                secondary_issue,
                primary_source,
                secondary_source,
                screen_id,
                phase,
            )
            findings.append(finding)
            if disagreement:
                disagreements.append(disagreement)
            continue

        # Try fuzzy match
        best_match_dk = None
        best_sim = 0.0
        for secondary_dk in secondary_by_dk:
            if secondary_dk in matched_secondary_dks:
                continue
            sim = dk_similarity(primary_dk, secondary_dk)
            if sim >= 0.75 and sim > best_sim:
                best_sim = sim
                best_match_dk = secondary_dk

        if best_match_dk:
            secondary_issue = secondary_by_dk[best_match_dk]
            matched_secondary_dks.add(best_match_dk)
            finding, disagreement = _merge_matched(
                primary_issue,
                secondary_issue,
                primary_source,
                secondary_source,
                screen_id,
                phase,
            )
            findings.append(finding)
            if disagreement:
                disagreements.append(disagreement)
        else:
            finding = _issue_to_finding(
                primary_issue,
                source=primary_source,
                status=FindingStatus.SUSPECTED,
                screen_id=screen_id,
                phase=phase,
            )
            findings.append(finding)

    # A finding reported by only one evaluator remains a hypothesis, regardless of identity.
    for secondary_dk, secondary_issue in secondary_by_dk.items():
        if secondary_dk not in matched_secondary_dks:
            finding = _issue_to_finding(
                secondary_issue,
                source=secondary_source,
                status=FindingStatus.SUSPECTED,
                screen_id=screen_id,
                phase=phase,
            )
            findings.append(finding)

    return findings, disagreements


def _merge_matched(
    primary_issue: dict,
    secondary_issue: dict,
    primary_source: str,
    secondary_source: str,
    screen_id: str,
    phase: Phase,
) -> tuple[Finding, Disagreement | None]:
    """Merge matched hypotheses and confirm only when they cite inspectable evidence."""
    primary_sev = _parse_severity(primary_issue.get("sev", "minor"))
    secondary_sev = _parse_severity(secondary_issue.get("sev", "minor"))
    primary_conf = float(primary_issue.get("conf", 0.5))
    secondary_conf = float(secondary_issue.get("conf", 0.5))

    # Use higher severity
    final_sev = Severity.higher(primary_sev, secondary_sev)
    final_conf = max(primary_conf, secondary_conf)

    title = primary_issue.get("title", "") or secondary_issue.get("title", "")
    hyp = primary_issue.get("hyp", "") or secondary_issue.get("hyp", "")

    # Merge evidence
    primary_ev = primary_issue.get("ev", {})
    secondary_ev = secondary_issue.get("ev", {})
    merged_ev = Evidence(
        img=_unique_list(primary_ev.get("img", []) + secondary_ev.get("img", [])),
        el=_unique_list(primary_ev.get("el", []) + secondary_ev.get("el", [])),
        txt=_unique_list(primary_ev.get("txt", []) + secondary_ev.get("txt", [])),
        act=_unique_list(primary_ev.get("act", []) + secondary_ev.get("act", [])),
    )
    status = FindingStatus.CONFIRMED if _has_grounded_evidence(merged_ev) else FindingStatus.SUSPECTED

    finding = Finding(
        id=primary_issue.get("id", secondary_issue.get("id", "")),
        sev=final_sev,
        type=_parse_issue_type(primary_issue.get("type", secondary_issue.get("type", "logic"))),
        title=title,
        exp=primary_issue.get("exp", secondary_issue.get("exp", "")),
        act=primary_issue.get("act", secondary_issue.get("act", "")),
        why=primary_issue.get("why", secondary_issue.get("why", "")),
        hyp=hyp,
        conf=final_conf,
        dk=primary_issue.get("dk", secondary_issue.get("dk", "")),
        ev=merged_ev,
        status=status,
        source=f"{primary_source}+{secondary_source}",
        screen_id=screen_id,
        phase=phase,
        model_agreement={
            "evaluators": [
                {"source": primary_source, "sev": primary_sev.value, "conf": primary_conf},
                {"source": secondary_source, "sev": secondary_sev.value, "conf": secondary_conf},
            ],
            "agreement": "full" if primary_sev == secondary_sev else "severity_differs",
            "grounded": _has_grounded_evidence(merged_ev),
        },
    )

    disagreement = None
    if primary_sev != secondary_sev:
        disagreement = Disagreement(
            screen_id=screen_id,
            evaluator_a=primary_source,
            evaluator_a_finding={
                "title": primary_issue.get("title", ""),
                "sev": primary_sev.value,
                "conf": primary_conf,
            },
            evaluator_b=secondary_source,
            evaluator_b_finding={
                "title": secondary_issue.get("title", ""),
                "sev": secondary_sev.value,
                "conf": secondary_conf,
            },
            resolution="higher_severity_retained",
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
    primary_eval: EvalResult,
    secondary_eval: EvalResult | None = None,
    open_findings: list[Finding] | None = None,
) -> list[dict]:
    """
    Merge next-action candidates from both models using utility scoring.
    Returns sorted list of action candidates (best first).
    """
    primary_candidates = primary_eval.candidates or []
    secondary_candidates = (secondary_eval.candidates or []) if secondary_eval else []

    # Index by fingerprint
    primary_by_fp: dict[str, dict] = {}
    for c in primary_candidates:
        fp = c.get("fp", _make_fp(c))
        primary_by_fp[fp] = c

    secondary_by_fp: dict[str, dict] = {}
    for c in secondary_candidates:
        fp = c.get("fp", _make_fp(c))
        secondary_by_fp[fp] = c

    all_fps = set(primary_by_fp.keys()) | set(secondary_by_fp.keys())
    scored: list[tuple[float, dict]] = []

    for fp in all_fps:
        primary_candidate = primary_by_fp.get(fp)
        secondary_candidate = secondary_by_fp.get(fp)

        # Agreement bonus
        agreement = 0.30 if (primary_candidate and secondary_candidate) else 0.0

        # Use the candidate from whichever evaluator provided it (or merge)
        candidate = (
            _merge_candidate(primary_candidate, secondary_candidate)
            if primary_candidate and secondary_candidate
            else (primary_candidate or secondary_candidate)
        )

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


def _merge_candidate(primary: dict, secondary: dict) -> dict:
    """Merge two matching next-action candidates."""
    merged = dict(primary)
    merged["conf"] = max(float(primary.get("conf", 0.5)), float(secondary.get("conf", 0.5)))
    if not merged.get("reason") and secondary.get("reason"):
        merged["reason"] = secondary["reason"]
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


def _source_id(evaluation: EvalResult | None, fallback: str) -> str:
    if evaluation and evaluation.model.strip():
        return evaluation.model.strip()
    return fallback


def _has_grounded_evidence(evidence: Evidence) -> bool:
    return any((evidence.img, evidence.el, evidence.txt, evidence.act))


def _unique_list(items: list) -> list:
    seen = set()
    result = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result

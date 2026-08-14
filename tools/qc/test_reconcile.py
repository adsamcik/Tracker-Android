"""Focused tests for evidence-based QC finding reconciliation."""

import unittest

from tools.qc.models import EvalResult, FindingStatus, IssueType, Phase, Severity
from tools.qc.reconcile import add_tool_finding, reconcile_findings


def evaluation(source: str, *issues: dict) -> EvalResult:
    return EvalResult(model=source, prompt_type="screen", issues=list(issues))


def issue(
    issue_id: str,
    *,
    dk: str = "settings|layout|save-button|clipped",
    severity: str = "major",
    evidence: dict | None = None,
) -> dict:
    return {
        "id": issue_id,
        "sev": severity,
        "type": "layout",
        "title": "Save button is clipped",
        "exp": "The complete button is visible",
        "act": "The lower edge is clipped",
        "why": "The primary action is harder to use",
        "hyp": "",
        "conf": 0.8,
        "dk": dk,
        "ev": evidence or {},
    }


class ReconcileFindingsTest(unittest.TestCase):
    def test_single_evaluator_finding_remains_suspected(self) -> None:
        findings, disagreements = reconcile_findings(
            evaluation("available-evaluator", issue("single", evidence={"img": ["screen-1"]})),
        )

        self.assertEqual([FindingStatus.SUSPECTED], [finding.status for finding in findings])
        self.assertEqual([], disagreements)
        self.assertEqual("available-evaluator", findings[0].source)

    def test_secondary_only_finding_is_not_confirmed_by_identity(self) -> None:
        findings, _ = reconcile_findings(
            evaluation("evaluator-a"),
            evaluation("evaluator-b", issue("secondary-only")),
        )

        self.assertEqual(1, len(findings))
        self.assertEqual(FindingStatus.SUSPECTED, findings[0].status)
        self.assertEqual("evaluator-b", findings[0].source)

    def test_matching_evaluators_without_evidence_remain_suspected(self) -> None:
        findings, _ = reconcile_findings(
            evaluation("evaluator-a", issue("a")),
            evaluation("evaluator-b", issue("b", severity="minor")),
        )

        self.assertEqual(1, len(findings))
        self.assertEqual(FindingStatus.SUSPECTED, findings[0].status)
        self.assertFalse(findings[0].model_agreement["grounded"])

    def test_matching_evaluators_with_inspectable_evidence_are_confirmed(self) -> None:
        findings, _ = reconcile_findings(
            evaluation("evaluator-a", issue("a", evidence={"img": ["screen-1"]})),
            evaluation("evaluator-b", issue("b", evidence={"el": ["save-button"]})),
            screen_id="settings",
            phase=Phase.SCREEN_QC,
        )

        self.assertEqual(1, len(findings))
        self.assertEqual(FindingStatus.CONFIRMED, findings[0].status)
        self.assertEqual(["screen-1"], findings[0].ev.img)
        self.assertEqual(["save-button"], findings[0].ev.el)
        self.assertTrue(findings[0].model_agreement["grounded"])

    def test_direct_tool_fact_is_confirmed(self) -> None:
        finding = add_tool_finding(
            title="App crashed during launch",
            sev=Severity.BLOCKER,
            issue_type=IssueType.RESILIENCE,
            screen_id="launch",
            phase=Phase.BOOTSTRAP,
        )

        self.assertEqual(FindingStatus.CONFIRMED, finding.status)
        self.assertEqual("tool", finding.source)


if __name__ == "__main__":
    unittest.main()

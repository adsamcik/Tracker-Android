"""
Run QC on the Android app.

Usage:
    python -m tools.qc.run                          # defaults
    python -m tools.qc.run --depth deep              # deep scan
    python -m tools.qc.run --package com.other.app   # different app
    python -m tools.qc.run --print-prompt            # print orchestrator prompt only
"""
import argparse
import json
import sys

from .orchestrator import build_evaluator_dispatch, generate_orchestrator_prompt


def main():
    parser = argparse.ArgumentParser(description="Android QC Skill")
    parser.add_argument("--package", default="com.adsamcik.tracker.debug",
                        help="App package name")
    parser.add_argument("--depth", choices=["smoke", "standard", "deep"],
                        default="standard", help="Test depth")
    parser.add_argument("--time", type=int, default=30,
                        help="Time budget in minutes")
    parser.add_argument("--apk", default=None, help="APK path to install")
    parser.add_argument("--print-prompt", action="store_true",
                        help="Print orchestrator prompt and exit")
    parser.add_argument("--print-eval", choices=["screen", "transition", "checkpoint", "synthesis"],
                        help="Print a sample evaluator dispatch prompt and exit")
    parser.add_argument("--focus", choices=["visual", "state"], default="visual",
                        help="Review focus for --print-eval")
    args = parser.parse_args()

    mock_locations = [
        {"lat": 40.7128, "lng": -74.006},
        {"lat": 40.7130, "lng": -74.0058},
    ]

    if args.print_prompt:
        prompt = generate_orchestrator_prompt(
            package_name=args.package,
            depth=args.depth,
            time_budget_minutes=args.time,
            mock_locations=mock_locations,
            apk_path=args.apk,
        )
        print(prompt)
        return

    if args.print_eval:
        sample_elements = {
            "clickable": [{"text": "Settings", "bounds": [975, 116, 63, 63]}],
            "text_visible": ["Dashboard", "Today", "5.4 km"],
            "scrollable": True,
            "total_elements": 44,
        }
        sample_context = {
            "state_ledger": {"tracking_active": False},
            "coverage_gaps": ["No screens tested"],
            "recent_actions": [],
            "prior_findings": [],
        }
        prompt = build_evaluator_dispatch(
            mode=args.print_eval,
            focus=args.focus,
            screenshot_description="Dashboard with bottom nav, FAB, and Today card",
            elements_compressed=sample_elements,
            context=sample_context,
            screen_id="dashboard",
            route="home/dashboard",
            activity="pkg/.MainActivityCompose",
        )
        print(prompt)
        return

    # Default: print invocation instructions
    print("=" * 60)
    print("  android_qc — Capability-driven QC helpers")
    print("=" * 60)
    print()
    print(f"  Package:  {args.package}")
    print(f"  Depth:    {args.depth}")
    print(f"  Budget:   {args.time} min")
    print()
    print("To run QC, use one of these methods:")
    print()
    print("1. In Copilot CLI, say:")
    print('   "Run QC on the app"')
    print()
    print("2. Or generate a capability-neutral workflow prompt:")
    print()
    print("   from tools.qc.orchestrator import generate_orchestrator_prompt")
    print(f'   prompt = generate_orchestrator_prompt("{args.package}", depth="{args.depth}")')
    print("   # Then use the invocation mechanism available in your environment")
    print()
    print("3. Preview prompts:")
    print("   python -m tools.qc.run --print-prompt       # orchestrator")
    print("   python -m tools.qc.run --print-eval screen  # evaluator")
    print()


if __name__ == "__main__":
    main()

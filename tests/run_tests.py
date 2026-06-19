#!/usr/bin/env python3
"""
run_tests.py — OpenFree Android IME Test Runner
================================================

Two execution modes:

  python tests/run_tests.py                  # --mode mock (default)
  python tests/run_tests.py --mode emulator  # full ADB / Gradle E2E

Mock mode runs offline Python unit tests in mock_harness/ and validates the
project file structure without requiring an Android build environment.

Emulator mode launches the Android emulator, deploys the app, and runs
Gradle-based instrumented tests.
"""

import argparse
import os
import subprocess
import sys
import unittest

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

REPO_ROOT   = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS_DIR   = os.path.dirname(os.path.abspath(__file__))
MOCK_DIR    = os.path.join(TESTS_DIR, "mock_harness")
GRADLEW     = os.path.join(REPO_ROOT, "gradlew.bat" if sys.platform == "win32" else "gradlew")
AVD_NAME    = "Medium_Phone_API_36.1"

# ---------------------------------------------------------------------------
# Helper utilities
# ---------------------------------------------------------------------------

def run(cmd, **kwargs):
    """Run a shell command; raise on non-zero exit."""
    print(f"  $ {' '.join(cmd)}")
    result = subprocess.run(cmd, **kwargs)
    if result.returncode != 0:
        raise RuntimeError(f"Command failed with exit {result.returncode}: {cmd}")
    return result


def emulator_is_running() -> bool:
    try:
        out = subprocess.check_output(["adb", "devices"], text=True)
        return "emulator" in out
    except FileNotFoundError:
        return False


# ---------------------------------------------------------------------------
# Mock mode
# ---------------------------------------------------------------------------

def run_mock_tests() -> int:
    """Discover and run all tests inside mock_harness/."""
    print("\n---  Mock Mode (offline) ---\n")
    loader = unittest.TestLoader()
    suite  = loader.discover(MOCK_DIR, pattern="test_*.py")
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


# ---------------------------------------------------------------------------
# Emulator mode
# ---------------------------------------------------------------------------

def run_emulator_tests() -> int:
    """Launch emulator (if needed), run Gradle instrumented tests, teardown."""
    print("\n---  Emulator Mode ---\n")

    launched_emulator = False

    # 1. Start emulator if not already running
    if not emulator_is_running():
        print(f"Starting emulator '{AVD_NAME}'…")
        subprocess.Popen(["emulator", "-avd", AVD_NAME, "-no-window", "-no-audio"])
        launched_emulator = True

        # Wait for boot_completed
        print("Waiting for emulator to boot (this can take ~60 s)…")
        run(["adb", "wait-for-device"])
        for _ in range(60):
            import time
            time.sleep(2)
            try:
                out = subprocess.check_output(
                    ["adb", "shell", "getprop", "sys.boot_completed"], text=True
                ).strip()
                if out == "1":
                    print("Emulator booted ✓")
                    break
            except subprocess.SubprocessError:
                pass
        else:
            print("ERROR: Emulator did not boot within 120 s", file=sys.stderr)
            return 1

    # 2. Run instrumented tests
    try:
        run([GRADLEW, "connectedAndroidTest"], cwd=REPO_ROOT)
        print("\n✓  All instrumented tests passed")
        return 0
    except RuntimeError as e:
        print(f"\n✗  {e}", file=sys.stderr)
        return 1
    finally:
        # 3. Shutdown emulator only if we started it
        if launched_emulator:
            print("Shutting down emulator…")
            subprocess.run(["adb", "emu", "kill"])


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="OpenFree test runner")
    parser.add_argument(
        "--mode",
        choices=["mock", "emulator"],
        default="mock",
        help="Execution mode (default: mock)"
    )
    args = parser.parse_args()

    if args.mode == "mock":
        sys.exit(run_mock_tests())
    else:
        sys.exit(run_emulator_tests())


if __name__ == "__main__":
    main()

"""Minimal test without the app: opens an app on the phone and finds (and optionally taps) the voice mode button.

    python adb_test.py                  # only prints which phone this is
    python adb_test.py --explore        # opens Claude and lists the buttons on screen
    python adb_test.py --tap            # also taps the voice mode button
    python adb_test.py --explore --package com.android.settings   # dry run on Settings
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

CLAUDE = "com.anthropic.claude"
# Same as Config.VOICE_BUTTON in the app; the Czech alternative matches the Czech UI.
VOICE = re.compile(r"^(voice mode|hlasový režim)$", re.I)


def find_adb() -> str:
    sdk = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    return shutil.which("adb") or sdk


ADB = find_adb()


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.strip()


def info() -> None:
    devices = [l for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    if not devices:
        sys.exit("No phone connected (or USB debugging was not allowed).")
    if len(devices) > 1:
        sys.exit("More than one phone connected, leave only one connected.")
    p = lambda k: adb("shell", "getprop", k)
    sdk = int(p("ro.build.version.sdk") or 0)
    battery = adb("shell", "dumpsys", "battery")
    level = re.search(r"level: (\d+)", battery)
    health = re.search(r"health: (\d+)", battery)
    health_txt = {"2": "good", "3": "overheated", "4": "dead", "5": "over voltage", "7": "cold"}.get(health.group(1) if health else "", "unknown")
    print(f"Phone:     {p('ro.product.manufacturer')} {p('ro.product.model')}")
    print(f"Android:   {p('ro.build.version.release')} (API {sdk})")
    print(f"Battery:   {level.group(1) if level else '?'} %, health per system: {health_txt}")
    claude = adb("shell", "pm", "path", CLAUDE, check=False)
    print(f"Claude:    {'installed' if claude else 'not installed'}")
    if sdk < 26:
        print("WARNING: Android older than 8, neither the Assistant app nor Claude will run on it.")


def explore(package: str, tap: bool) -> None:
    if not adb("shell", "pm", "path", package, check=False):
        sys.exit(f"App {package} is not on the phone.")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    lock = adb("shell", "dumpsys", "window", "policy")
    if re.search(r"showing=true", lock) and re.search(r"secure=true", lock):
        sys.exit("The phone is locked. Unlock it and run the test again.")
    adb("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)
    adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    with tempfile.TemporaryDirectory() as d:
        target = os.path.join(d, "window_dump.xml")
        adb("pull", "/sdcard/window_dump.xml", target)
        tree = ET.parse(target)

    foreground = {u.get("package") for u in tree.iter("node")}
    if package not in foreground:
        print(f"{package} is not on screen, but {', '.join(sorted(foreground))} is. Is the phone unlocked?")

    candidates = []
    print("\nElements with a label (description | text | id | clickable | centre):")
    for u in tree.iter("node"):
        desc, text, rid = u.get("content-desc", ""), u.get("text", ""), u.get("resource-id", "")
        label = " | ".join(x for x in (desc, text, rid) if x)
        if not label:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", u.get("bounds", "[0,0][0,0]")))
        centre = ((x1 + x2) // 2, (y1 + y2) // 2)
        voice = any(VOICE.match(x.strip()) for x in (desc, text) if x)
        if voice:
            candidates.append((label, centre))
        print(f"{'>>' if voice else '  '} {label} | {u.get('clickable')} | {centre}")

    if not candidates:
        print("\nVoice mode button not found by label. Send the output above to Claude.")
        return
    label, (x, y) = candidates[0]
    print(f"\nVoice mode button: {label} at {x},{y}")
    if tap:
        adb("shell", "input", "tap", str(x), str(y))
        print("Tapped. Check the phone to see whether voice mode is running.")


def main() -> None:
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("--explore", action="store_true", help="open the app and list the buttons")
    a.add_argument("--tap", action="store_true", help="tap the voice mode button if found")
    a.add_argument("--package", default=CLAUDE, help=f"app to open (default {CLAUDE})")
    arg = a.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    info()
    if arg.explore or arg.tap:
        explore(arg.package, arg.tap)


if __name__ == "__main__":
    main()

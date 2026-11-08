"""Minimální test bez aplikace: otevře aplikaci v telefonu a najde (a případně stiskne) tlačítko hlasového režimu.

    python adb_test.py                  # jen vypíše, co je to za telefon
    python adb_test.py --pruzkum        # otevře Clauda a vypíše tlačítka na obrazovce
    python adb_test.py --klikni         # navíc klepne na tlačítko hlasového režimu
    python adb_test.py --pruzkum --balicek com.android.settings   # nanečisto na Nastavení
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
# Shodné s Config.VOICE_BUTTON v aplikaci.
HLAS = re.compile(r"^(voice mode|hlasový režim)$", re.I)


def najdi_adb() -> str:
    sdk = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    return shutil.which("adb") or sdk


ADB = najdi_adb()


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} selhalo: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.strip()


def info() -> None:
    zarizeni = [l for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    if not zarizeni:
        sys.exit("Není připojený žádný telefon (nebo nepovolil ladění přes USB).")
    if len(zarizeni) > 1:
        sys.exit("Připojených telefonů je víc, nech připojený jen jeden.")
    p = lambda k: adb("shell", "getprop", k)
    sdk = int(p("ro.build.version.sdk") or 0)
    baterie = adb("shell", "dumpsys", "battery")
    uroven = re.search(r"level: (\d+)", baterie)
    zdravi = re.search(r"health: (\d+)", baterie)
    zdravi_txt = {"2": "dobrá", "3": "přehřátá", "4": "mrtvá", "5": "přepětí", "7": "studená"}.get(zdravi.group(1) if zdravi else "", "neznámá")
    print(f"Telefon:   {p('ro.product.manufacturer')} {p('ro.product.model')}")
    print(f"Android:   {p('ro.build.version.release')} (API {sdk})")
    print(f"Baterie:   {uroven.group(1) if uroven else '?'} %, stav podle systému: {zdravi_txt}")
    claude = adb("shell", "pm", "path", CLAUDE, check=False)
    print(f"Claude:    {'nainstalovaný' if claude else 'není nainstalovaný'}")
    if sdk < 26:
        print("POZOR: Android starší než 8, aplikace Asistent ani Claude na něm nepoběží.")


def pruzkum(balicek: str, klikni: bool) -> None:
    if not adb("shell", "pm", "path", balicek, check=False):
        sys.exit(f"Aplikace {balicek} v telefonu není.")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    zamek = adb("shell", "dumpsys", "window", "policy")
    if re.search(r"showing=true", zamek) and re.search(r"secure=true", zamek):
        sys.exit("Telefon je zamčený. Odemkni ho a spusť test znovu.")
    adb("shell", "monkey", "-p", balicek, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)
    adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    with tempfile.TemporaryDirectory() as d:
        cil = os.path.join(d, "window_dump.xml")
        adb("pull", "/sdcard/window_dump.xml", cil)
        strom = ET.parse(cil)

    popredi = {u.get("package") for u in strom.iter("node")}
    if balicek not in popredi:
        print(f"Na obrazovce není {balicek}, ale {', '.join(sorted(popredi))}. Je telefon odemčený?")

    kandidati = []
    print("\nPrvky s popiskem (popis | text | id | klikací | střed):")
    for u in strom.iter("node"):
        popis, text, rid = u.get("content-desc", ""), u.get("text", ""), u.get("resource-id", "")
        stitek = " | ".join(x for x in (popis, text, rid) if x)
        if not stitek:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", u.get("bounds", "[0,0][0,0]")))
        stred = ((x1 + x2) // 2, (y1 + y2) // 2)
        hlas = any(HLAS.match(x.strip()) for x in (popis, text) if x)
        if hlas:
            kandidati.append((stitek, stred))
        print(f"{'>>' if hlas else '  '} {stitek} | {u.get('clickable')} | {stred}")

    if not kandidati:
        print("\nTlačítko hlasového režimu se podle popisku nenašlo. Pošli Claudovi výpis výše.")
        return
    stitek, (x, y) = kandidati[0]
    print(f"\nTlačítko hlasového režimu: {stitek} na {x},{y}")
    if klikni:
        adb("shell", "input", "tap", str(x), str(y))
        print("Klepnuto. Podívej se na telefon, jestli běží hlasový režim.")


def main() -> None:
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("--pruzkum", action="store_true", help="otevřít aplikaci a vypsat tlačítka")
    a.add_argument("--klikni", action="store_true", help="klepnout na nalezené tlačítko hlasového režimu")
    a.add_argument("--balicek", default=CLAUDE, help=f"aplikace k otevření (výchozí {CLAUDE})")
    arg = a.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    info()
    if arg.pruzkum or arg.klikni:
        pruzkum(arg.balicek, arg.klikni)


if __name__ == "__main__":
    main()

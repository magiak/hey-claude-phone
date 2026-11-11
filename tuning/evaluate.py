# /// script
# requires-python = ">=3.10"
# dependencies = ["vosk"]
# ///
"""Wake word tuning on one recording of his voice, so he says the phrase once, not for an hour.

The phone records a guided 90 s (debug extra `record`): ten prompted phrases, then normal
Czech talk. This decodes the WAV with the phone's model and grammar exactly as the app does,
checks that it reproduces the phone's own decode, and scores grammar and threshold variants.

    uv run tuning/evaluate.py --pull     # pull new recordings, score the newest
    uv run tuning/evaluate.py recordings/x.wav

Recordings stay in recordings/ (ignored by git): his voice never leaves this PC.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

from vosk import KaldiRecognizer, Model, SetLogLevel

HERE = Path(__file__).resolve().parent
MODEL = HERE.parent / "android" / "vosk-assets" / "model"
CONFIG = HERE.parent / "android" / "app" / "src" / "main" / "java" / "cz" / "magiak" / "asistent" / "Config.kt"
RECORDINGS = HERE / "recordings"
PHONE_DIR = "/sdcard/Android/data/cz.magiak.asistent/files"
ADB = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"

# Must match VoiceRecording/SpeechService: 0.2 s of 16-bit mono at 16 kHz per call.
CHUNK_BYTES = 6400
PROMPT_S = 5.0
# A phrase may start a little before the prompt flash if he anticipates it.
EARLY_S = 0.5

EXTRA_DECOYS = ["hay", "they", "say", "clay", "close", "clock", "okay", "hello"]


def read_config():
    text = CONFIG.read_text(encoding="utf-8")

    def words(name):
        return re.findall(r'"([^"]+)"', re.search(rf"val {name} = listOf(?:<String>)?\(([^)]*)\)", text).group(1))

    conf = float(re.search(r"MIN_CONFIDENCE = ([0-9.]+)", text).group(1))
    gap = float(re.search(r"MAX_WAKE_GAP_S = ([0-9.]+)", text).group(1))
    return words("WAKE_PHRASE"), words("DECOY_WORDS"), words("MEETING_START"), conf, gap


def grammar(phrase, decoys, command, standalone=True):
    """Same as WakeWordListener.grammar: the phrase alone and with the meeting command."""
    items = [" ".join(phrase), " ".join(phrase + command)] + (phrase + command if standalone else []) + decoys + ["[unk]"]
    return json.dumps(list(dict.fromkeys(items)))


def missing_words(model, words):
    """Kaldi only warns about words outside the vocabulary, on the C stderr."""
    fd = sys.stderr.fileno()
    saved = os.dup(fd)
    with tempfile.TemporaryFile() as tmp:
        os.dup2(tmp.fileno(), fd)
        try:
            SetLogLevel(0)
            KaldiRecognizer(model, 16000, json.dumps(words + ["[unk]"]))
        finally:
            SetLogLevel(-1)
            os.dup2(saved, fd)
            os.close(saved)
        tmp.seek(0)
        log = tmp.read().decode("utf-8", "replace")
    return [w for w in words if f"'{w}'" in log or f" {w}\n" in log or f": {w}" in log]


def decode(model, wav_path, gram):
    with wave.open(str(wav_path), "rb") as wav:
        assert wav.getframerate() == 16000 and wav.getnchannels() == 1 and wav.getsampwidth() == 2
        rec = KaldiRecognizer(model, 16000, gram)
        rec.SetWords(True)
        results = []

        def take(raw):
            words = json.loads(raw).get("result")
            if words:
                results.append(words)

        while data := wav.readframes(CHUNK_BYTES // 2):
            if rec.AcceptWaveform(data):
                take(rec.Result())
        take(rec.FinalResult())
    return results


def is_wake(words, phrase, min_conf, max_gap):
    """Same as WakeWordListener.wakeAt."""
    n = len(phrase)
    return any(
        all(words[i + k]["word"] == phrase[k] and words[i + k]["conf"] >= min_conf
            and (k == 0 or words[i + k]["start"] - words[i + k - 1]["end"] <= max_gap) for k in range(n))
        for i in range(len(words) - n + 1)
    )


def is_miss(words, phrase, decoys):
    """Same as WakeWordListener.check: a lone first word is the recognizer's silence artifact."""
    return any(w["word"] in phrase[1:] or w["word"] in decoys for w in words)


def read_marks(path):
    marks = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            tag, t = line.split()
            marks[tag] = float(t)
    return marks


def score(results, marks, phrase, decoys, min_conf, max_gap):
    prompts = sorted((t, tag) for tag, t in marks.items() if re.fullmatch(r"(near|far)\d+", tag))
    talk = marks["talk"]
    caught = {tag: False for _, tag in prompts}
    false_hits = red_talk = red_prompt = 0
    for words in results:
        start = words[0]["start"]
        hit = is_wake(words, phrase, min_conf, max_gap)
        heard = is_miss(words, phrase, decoys)
        window = next((tag for t, tag in prompts if t - EARLY_S <= start < t + PROMPT_S), None)
        if start >= talk:
            false_hits += hit
            red_talk += heard and not hit
        elif window:
            caught[window] |= hit
            red_prompt += heard and not hit
    near = sum(v for k, v in caught.items() if k.startswith("near"))
    far = sum(v for k, v in caught.items() if k.startswith("far"))
    return {"near": near, "far": far, "false": false_hits, "red_talk": red_talk, "red_prompt": red_prompt}


def fmt(words):
    return "  ".join(f"{w['word']} {w['conf']:.2f}" for w in words)


def pull():
    listing = subprocess.run([str(ADB), "shell", "ls", PHONE_DIR], capture_output=True, text=True, timeout=20)
    if listing.returncode != 0:
        sys.exit(f"Phone not visible over USB: {listing.stderr.strip()}")
    RECORDINGS.mkdir(exist_ok=True)
    for name in listing.stdout.split():
        # The phone's replay file is rewritten by every `replay`, so it is always pulled again.
        if name.startswith("recording-") and (name.endswith("-phone.txt") or not (RECORDINGS / name).exists()):
            subprocess.run([str(ADB), "pull", f"{PHONE_DIR}/{name}", str(RECORDINGS / name)], check=True,
                           capture_output=True, timeout=120)
            print(f"pulled: {name}")


def parity(model, wav_path, phone_path):
    lines = phone_path.read_text(encoding="utf-8").splitlines()
    head = json.loads(lines[0])
    phone = [json.loads(x)["result"] for x in lines[1:] if x.strip()]
    pc = decode(model, wav_path, head["grammar"])
    words_same = [[w["word"] for w in r] for r in phone] == [[w["word"] for w in r] for r in pc]
    diffs = [abs(a["conf"] - b["conf"]) for ra, rb in zip(phone, pc) for a, b in zip(ra, rb)]
    print(f"Match with the phone (grammar {head['grammar']}):")
    print(f"  results phone {len(phone)}, PC {len(pc)}, words same: {'yes' if words_same else 'NO'}, "
          f"largest confidence difference {max(diffs, default=0):.3f}")
    if not words_same:
        for i in range(max(len(phone), len(pc))):
            a = fmt(phone[i]) if i < len(phone) else "-"
            b = fmt(pc[i]) if i < len(pc) else "-"
            if a != b:
                print(f"  phone: {a}\n  PC: {b}")
    return words_same


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("wav", nargs="?")
    ap.add_argument("--pull", action="store_true", help="pull new recordings from the phone first")
    args = ap.parse_args()
    if args.pull:
        pull()
    wav_path = Path(args.wav) if args.wav else max(RECORDINGS.glob("recording-*.wav"), default=None)
    if not wav_path:
        sys.exit("No recording in recordings/.")
    marks = read_marks(wav_path.with_suffix(".txt"))
    print(f"Recording: {wav_path.name}, {marks.get('end', 0):.0f} s")

    SetLogLevel(-1)
    model = Model(str(MODEL))
    phrase, decoys, command, min_conf, max_gap = read_config()

    phone_path = wav_path.with_name(wav_path.stem + "-phone.txt")
    if phone_path.exists():
        parity(model, wav_path, phone_path)
    else:
        print("Phone result missing, cannot verify the match with the PC.")

    current = decode(model, wav_path, grammar(phrase, decoys, command))
    print(f"\nWhat it heard with the current setup ({' '.join(phrase)}, threshold {min_conf}):")
    for words in current:
        mark = "GREEN" if is_wake(words, phrase, min_conf, max_gap) else "red" if is_miss(words, phrase, decoys) else ""
        print(f"  {words[0]['start']:6.1f} s  {fmt(words):60s} {mark}")

    absent = missing_words(model, EXTRA_DECOYS)
    extra = [w for w in EXTRA_DECOYS if w not in absent and w not in decoys and w not in phrase]
    variants = {
        "current": grammar(phrase, decoys, command),
        "no decoys": grammar(phrase, [], command),
        "more decoys": grammar(phrase, decoys + extra, command),
        "phrase only": grammar(phrase, [], command, standalone=False),
    }
    print(f"\nVariants (out of 5 near and 5 far; during talk: triggers / red):")
    rows = []
    for name, gram in variants.items():
        results = current if name == "current" else decode(model, wav_path, gram)
        for conf in [0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.7]:
            s = score(results, marks, phrase, decoys, conf, max_gap)
            rows.append((name, conf, s))
    rows.sort(key=lambda r: (r[2]["false"], -(r[2]["near"] + r[2]["far"]), r[2]["red_talk"]))
    for name, conf, s in rows:
        flag = " <- now" if name == "current" and conf == min_conf else ""
        print(f"  {name:11s} threshold {conf:.2f}:  near {s['near']}/5  far {s['far']}/5  "
              f"talk {s['false']} / {s['red_talk']}{flag}")


if __name__ == "__main__":
    main()

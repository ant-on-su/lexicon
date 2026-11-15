#!/usr/bin/env python3
"""Makes an mp3 per word and per example phrase with Google Cloud Text-to-Speech.

Reads <folder>/<language>/words/*.yaml and writes <folder>/<language>/audio/<unit id>/<entry id>.mp3 for the
word and <entry id>_1.mp3, _2.mp3, ... for its examples, or the `audio:` path of the entry or example when set.
The spoken text is `speak`, or else the term without bracketed parts and "+ acc.", or the example's text.
Latin and Greek are only read from `speak` (respellings), so entries without one are listed and skipped.

A file that exists is kept, unless this script made it and its spoken text or voice has changed since.
What it made is tracked in <folder>/<language>/audio/spoken.json.

Setup: see tools/README.md. Then:
  export GOOGLE_TTS_API_KEY=...                  # never commit it
  python3 tools/make_audio.py --dry-run          # what it would make, and the characters it would bill
  python3 tools/make_audio.py --unit la-woorden-2a
  python3 tools/make_audio.py --voice la=it-IT-Wavenet-F --unit la-woorden-2a
  python3 tools/make_audio.py --list-voices it-IT
"""

import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

import yaml

API = "https://texttospeech.googleapis.com/v1/"
KEY_VARIABLE = "GOOGLE_TTS_API_KEY"

# The voice per language code (meta.language). WaveNet: about $4 per million characters after the free tier.
VOICES = {
    "fr": "fr-FR-Wavenet-F",
    "de": "de-DE-Wavenet-G",
    "la": "it-IT-Wavenet-E",  # Latin, respelled in `speak` for an Italian voice
    "el": "nl-NL-Wavenet-F",  # Attic Greek, respelled in `speak` for a Dutch voice (Erasmian)
}
# Languages read only from `speak`: their own spelling means nothing to the voice.
RESPELLED = {"la", "el"}
SPEAKING_RATE = 0.9


def spoken(term):
    """The term without bracketed parts ("(amare)") or cases ("+ acc."), and "a / b" as "a, b", as in the app."""
    text = re.sub(r"\([^)]*\)|\+\s*[^\W\d_]+\.", " ", term).replace(" / ", ", ")
    return re.sub(r"\s+", " ", text).strip()


def clips(root, codes, units):
    """(language folder, language code, path inside audio/, spoken text or None, label) for every word and example."""
    for lang in sorted(p for p in root.iterdir() if (p / "words").is_dir()):
        for file in sorted((lang / "words").glob("*.y*ml")):
            data = yaml.safe_load(file.read_text("utf-8")) or {}
            meta = data.get("meta") or {}
            unit = str(meta.get("id") or file.stem)
            code = meta.get("language")
            if (codes and code not in codes and lang.name not in codes) or (units and unit not in units):
                continue
            respelled = code in RESPELLED
            for entry in data.get("entries") or []:
                if not isinstance(entry, dict) or entry.get("term") is None:
                    continue
                term = str(entry["term"])
                entry_id = str(entry.get("id") or term)
                speak = entry.get("speak") or (None if respelled else spoken(term))
                yield lang, code, str(entry.get("audio") or f"{unit}/{entry_id}.mp3"), speak, f"{unit}: {term}"
                for i, example in enumerate(entry.get("examples") or [], 1):
                    if not isinstance(example, dict) or example.get("text") is None:
                        continue
                    text = str(example["text"])
                    speak = example.get("speak") or (None if respelled else text)
                    yield lang, code, str(example.get("audio") or f"{unit}/{entry_id}_{i}.mp3"), speak, f"{unit}: {text}"


def request(key, path, body=None):
    headers = {"X-Goog-Api-Key": key, "Content-Type": "application/json"}
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(urllib.request.Request(API + path, data, headers), timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        sys.exit(f"Google Text-to-Speech said {e.code}: {e.read().decode(errors='replace')}")


def synthesize(key, voice, text):
    locale = "-".join(voice.split("-")[:2])
    reply = request(key, "text:synthesize", {
        "input": {"text": text},
        "voice": {"languageCode": locale, "name": voice},
        "audioConfig": {"audioEncoding": "MP3", "speakingRate": SPEAKING_RATE},
    })
    return base64.b64decode(reply["audioContent"])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", nargs="?", type=Path, default=Path(__file__).resolve().parent.parent / "Resources",
                        help="the word folder with <language>/words/ (default: the repo's Resources)")
    parser.add_argument("--dry-run", action="store_true", help="only list what would be made")
    parser.add_argument("--language", action="append", default=[], help="only this language code or folder (repeatable)")
    parser.add_argument("--unit", action="append", default=[], help="only this unit id (repeatable)")
    parser.add_argument("--voice", action="append", default=[], metavar="CODE=VOICE", help="use another voice, such as la=es-ES-Wavenet-E")
    parser.add_argument("--list-voices", metavar="LOCALE", help="list Google's voices for a locale, such as it-IT, and stop")
    args = parser.parse_args()

    key = os.environ.get(KEY_VARIABLE)
    if not key and (args.list_voices or not args.dry_run):
        sys.exit(f"Set {KEY_VARIABLE} first (see tools/README.md), or use --dry-run.")
    if args.list_voices:
        for voice in request(key, f"voices?languageCode={args.list_voices}")["voices"]:
            print(voice["name"], voice["ssmlGender"].lower())
        return

    voices = dict(VOICES)
    for override in args.voice:
        code, _, name = override.partition("=")
        voices[code] = name

    manifests = {}
    made = kept = chars = 0
    missing = []
    for lang, code, path, text, label in clips(args.folder, set(args.language), set(args.unit)):
        voice = voices.get(code)
        if voice is None:
            continue
        if not text:
            missing.append(label)
            continue
        audio = lang / "audio"
        if lang not in manifests:
            file = audio / "spoken.json"
            manifests[lang] = json.loads(file.read_text("utf-8")) if file.exists() else {}
        manifest = manifests[lang]
        target = audio / path
        done = manifest.get(path)
        if target.exists() and (done is None or done == {"text": text, "voice": voice}):
            kept += 1
            continue
        print(f"{'update' if target.exists() else 'create'} {lang.name}/audio/{path}  [{voice}] {text}")
        made += 1
        chars += len(text)
        if args.dry_run:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(synthesize(key, voice, text))
        manifest[path] = {"text": text, "voice": voice}
        (audio / "spoken.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1, sort_keys=True), "utf-8")

    if missing:
        print(f"\nNo `speak` respelling, skipped ({len(missing)}):")
        for label in missing:
            print("  " + label)
    verb = "Would make" if args.dry_run else "Made"
    print(f"\n{verb} {made} files, {chars} characters (about ${chars * 4 / 1_000_000:.2f} at WaveNet's rate"
          f" past the free tier); kept {kept}.")


if __name__ == "__main__":
    main()

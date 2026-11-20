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
  python3 tools/make_audio.py --compare-voices nl-NL --dry-run   # the voices it would try, nothing is made or billed
  python3 tools/make_audio.py --compare-voices nl-NL             # the Greek sample, respelled for Dutch, by every Dutch voice
  python3 tools/make_audio.py --compare-voices nl-NL --compare-voices de-DE   # ... and respelled for German, by every German voice
  python3 tools/make_audio.py --compare-voices it-IT --match Wavenet --match Neural2   # the Latin sample, some voices
  python3 tools/make_audio.py --compare-voices nl-NL --phrase "your own respelled text"

--compare-voices writes tools/voice-samples/<sample>/<voice>.mp3, so that you can listen to all voices one after
another, and phrase.txt: the sample word by word, with the respelling conventions, the pitfalls and what to listen for
per kind of voice. It overwrites earlier samples; keep voice-samples out of git.
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

# --compare-voices: what the respellings for each kind of voice (the language of the voice's locale) follow, and
# what tends to go wrong with it. Both end up in phrase.txt.
TARGETS = {
    "nl": ("Dutch voice (Erasmian pronunciation)", [
        "vowels: η ee, ω oo, υ u, ου oe, ει ei (Erasmian; Classical is [eː]), οι oi, αι ai, αυ au, ευ eoe, υι uï;"
        " long ᾱ aa, ῑ ie, ῡ uu; an iota subscript is not spoken",
        "consonants: θ t, φ p, χ k (Dutch cannot aspirate), ζ zd, ξ ks, ψ ps, γγ ng, ῥ rh; rough breathing is h",
        "σ between vowels is ss (a single s would be read as z); a short vowel in a stressed open syllable is closed"
        " with a doubled consonant (tallatta, bassilèoes), because Dutch would lengthen it",
        "è for a short or unstressed ε, so that it is not reduced to a schwa; a trema (ö, ï) separates two vowels",
    ], [
        "Dutch g is a fricative [ɣ], not the stop [g] of γ; probably not fixable in the spelling.",
        "No aspirates and no geminates: φ θ χ sound like plain p t k, and ττ like a single t.",
        "Stress follows Dutch rules; the Attic pitch accent cannot be written.",
        "Dutch ui [œy], eu [ø] and ij/ei [ɛi] are not the Greek sounds, hence the respellings uï and eoe.",
        "Unusual clusters (ψ-, ξ-, κτ-, γν-) may get an inserted schwa.",
    ]),
    "de": ("German voice", [
        "vowels: η äh, ω oh, υ ü, ου u, ει ee (Classical [eː]; German ei is [aɪ]), οι oi, αι ai, αυ au,"
        " ευ ä-u with a hyphen (German eu is [ɔɪ]), υι üi; long ᾱ ah, ῑ ie, ῡ üh; an iota subscript is not spoken",
        "consonants: θ t, φ p, χ k (German p, t, k are aspirated, which suits φ θ χ), ζ sd, ξ ks, ψ ps, γγ ngg, ῥ rh;"
        " rough breathing is h",
        "σ at the start of a word is ß (s before a vowel would be [z]), σ between vowels is ss",
        "short vowels get a doubled consonant (onn, oss, tallatta); long vowels take h or doubling (äh, oh, ah, üh, ie)",
        "ä for a short or unstressed ε, so that it is not reduced to a schwa; a hyphen separates two vowels, because"
        " an h before a vowel is pronounced (as in Ahorn)",
    ], [
        "r is uvular, and after a vowel it becomes [ɐ]; it is not the trill of ρ.",
        "π τ κ are aspirated too, though Greek has them plain.",
        "No real geminates (ττ, γγ): a doubled letter only shortens the vowel before it.",
        "Stress follows German rules; the Attic pitch accent cannot be written.",
        "A hyphen may add a tiny pause; an ß at the start of a word is unusual, check that it is read as [s];"
        " unusual clusters (ψ-, κτ-, γν-) may get an inserted schwa.",
    ]),
    "it": ("Italian voice (Classical Latin pronunciation)", [
        "c is k, g before e/i is gh, v is u (read as [w]), ae is ai, qu as in Italian",
        "a grave accent marks the stress where Latin does not stress the penultimate syllable, Italian's default",
    ], [
        "Italian has no vowel length, so Latin long and short vowels sound alike.",
        "A single s between vowels may be read as [z] by Italian voices (write ss); not in this sample.",
        "gn is [ŋn] in Latin, [ɲ] in Italian; not in this sample.",
    ]),
}

# Sample texts for --compare-voices: (title, words). A word is (original, {kind of voice: (respelling, what to listen
# for)}); the respellings of all words, in order, are the text that every voice of that kind reads. The words need not
# make sense together; they are chosen so that every sound of the language comes by at least once.
SAMPLES = {
    "el": ("Attic Greek: every vowel, diphthong and consonant, long and short, breathings, clusters, geminates", [
        ("Χαίρετε,", {
            "nl": ("kairète,", "χ→k, unaspirated; αι is a glide; è keeps the unstressed final ε from becoming a schwa"),
            "de": ("kairätä,", "χ→k gets a natural puff; ai is [aɪ]; ä keeps the unstressed ε from becoming a schwa")}),
        ("ὦ,", {
            "nl": ("oo,", "long ω: oo is [oː], Attic [ɔː] is more open; it must be clearly longer than a short o"),
            "de": ("oh,", "long ω: oh is [oː]; it must be clearly longer than a short o")}),
        ("ἄνδρες,", {
            "nl": ("andrès,", "νδρ cluster; r should be trilled; the final ες must stay a full vowel, not [əs]"),
            "de": ("andräss,", "r will be uvular or vowel-like instead of trilled; ä keeps ε full")}),
        ("Ἀθηναῖοι,", {
            "nl": ("ateenaioi,", "θ→t; long η as ee; two glides in a row, ai + oi, not 'aj-oj'"),
            "de": ("atähnaioi,", "θ→t with a puff; long open η as äh [ɛː]; two glides in a row, ai + oi")}),
        ("ἄγγελος,", {
            "nl": ("angèlos,", "γγ is [ŋg]; Dutch ng gives only [ŋ], so the g is lost; è keeps the middle ε full"),
            "de": ("anggäloss,", "γγ is [ŋg]; ngg should give both sounds, if only [ŋ] is heard it is German ng again")}),
        ("θάλαττα,", {
            "nl": ("tallatta,", "ττ is a long [tː], Dutch has no geminates; the ll only closes the first syllable so"
                                " that the short α is not lengthened"),
            "de": ("tallatta,", "ττ is a long [tː], German has no real geminates; the ll keeps the first α short")}),
        ("φύσει,", {
            "nl": ("pussei,", "φ→p, not f; υ is [y], Dutch u; ss for the σ between vowels, a single s would be z;"
                              " ει as ei (Erasmian; Classical is [eː])"),
            "de": ("püssee,", "φ→p with a puff, not f; ü is [y]; ss for the σ between vowels; ει is written ee"
                              " ([eː], the Classical value) because German ei is [aɪ]")}),
        ("ζῷον,", {
            "nl": ("zdooön,", "ζ is [zd], not [z] or [dz]; ῳ is a long ō followed by a separate short o: the trema"
                              " makes two syllables"),
            "de": ("sdoh-onn,", "ζ is [zd], written sd; a long ō and then a separate short o: the hyphen marks the"
                                " hiatus (an h would be pronounced); expect a tiny pause")}),
        ("ψυχή,", {
            "nl": ("psukee,", "ψ→ps at the start of a word; υ is long here, [yː]; χ→k; η→ee"),
            "de": ("psükäh,", "ps at the start of a word; ü is long here, [yː]; χ→k; long open η as äh")}),
        ("ξένος,", {
            "nl": ("ksènos,", "ξ→ks at the start of a word; è keeps the short ε short (Dutch e in an open stressed"
                              " syllable would be long)"),
            "de": ("ksennoss,", "ks at the start of a word; nn keeps the short ε short; short o in the final oss")}),
        ("οὐδέν,", {
            "nl": ("oedèn,", "ου→oe [u]; δ is a plain d; è keeps the final ε full"),
            "de": ("udenn,", "ου→u [uː]; δ is a plain d; nn keeps ε a full short vowel")}),
        ("βασιλεύς,", {
            "nl": ("bassilèoes,", "β is a real [b]; ss between vowels; ευ→èoe, because Dutch eu is [ø]; the closed"
                                  " first syllable keeps α short"),
            "de": ("bassilä-uss,", "β is a real [b]; ss between vowels; ευ→ä-u with a hyphen, because German eu/äu is"
                                   " [ɔɪ]; expect a tiny pause")}),
        ("γνῶθι,", {
            "nl": ("gnooti,", "γ is a stop [g]: Dutch g is a throaty fricative, the most likely failure; γν at the"
                              " start of a word; long ω; θι→ti with a short i"),
            "de": ("gnohti,", "γ is [g] in German, this is where German should beat Dutch; long ω as oh; θ→t with a"
                              " puff")}),
        ("σεαυτόν,", {
            "nl": ("sèauton,", "initial σ is [s]; è keeps ε full; αυ is a glide; the final ον has a short o"),
            "de": ("ßäautonn,", "ß so that the initial s is [s], not [z]; ä keeps ε full; αυ is a glide; nn keeps"
                                " the o short")}),
        ("ῥήτωρ,", {
            "nl": ("rheetoor,", "ῥ is a voiceless rho, here just rh; η→ee; ω→oo; the final r should be trilled and"
                                " audible"),
            "de": ("rhähtohr,", "rh; long open η as äh; long ω as oh; the final r tends to become a vowel [ɐ]:"
                                " expect 'tohɐ'")}),
        ("ἡμέρα,", {
            "nl": ("heemèraa,", "rough breathing: the initial h must be heard; η→ee; è for ε; the final α is long"
                                " (ᾱ after ρ): aa"),
            "de": ("hähmärah,", "the initial h must be heard; äh; ä for ε; the final α is long (ᾱ after ρ): ah")}),
        ("τιμή,", {
            "nl": ("tiemee,", "ι is long (ῑ): ie; η→ee"),
            "de": ("tiemäh,", "ι is long (ῑ): ie [iː]; äh")}),
        ("σῦκον,", {
            "nl": ("suukon,", "υ is long: uu [yː]; the final on is short"),
            "de": ("ßühkonn,", "ß for the initial σ; üh is a long [yː]; nn keeps the o short")}),
        ("υἱός,", {
            "nl": ("huïos,", "rough breathing; υι is [yi]: the trema stops Dutch ui [œy] from forming"),
            "de": ("hüioss,", "rough breathing; υι is [yi]; check that 'io' is not read as [jo]")}),
        ("κτῆμα.", {
            "nl": ("kteema.", "κτ cluster at the start of a word, watch for an inserted schwa ('kətee'); η→ee"),
            "de": ("ktähma.", "κτ cluster at the start of a word, watch for an inserted schwa; long open η as äh")}),
    ]),
    "la": ("Classical Latin: v, c and g, and stress", [
        ("Veni,", {"it": ("Ueni,", "v is [w]: u + e must glide to [we], not become two syllables u-e")}),
        ("vidi,", {"it": ("uidi,", "v is [w] again: ui as [wi]")}),
        ("vici.", {"it": ("uiki.", "c before i is [k], not Italian [tʃ] ('vichi'); v is [w]")}),
        ("Gaudeamus", {"it": ("Gaudeamus", "g before a is [g]; au is [au]; the stress is on -a-, Latin and Italian agree")}),
        ("igitur,", {"it": ("ìghitur,", "gi is written gh: [g], not [dʒ]; the accent puts the stress on the first i,"
                                         " as in Latin (Italian would stress -gi-)")}),
        ("iuvenes", {"it": ("iùuenes", "iu is [ju]; v is written u: [w]; stress on iù, not on -ve-; 'uu' may be read"
                                      " as one long u")}),
        ("dum", {"it": ("dum", "short u in a closed syllable")}),
        ("sumus.", {"it": ("sumus.", "initial s is [s]; stress on su-")}),
    ]),
}
# The sample that suits a voice locale, when --sample is not given.
SAMPLE_FOR_LANGUAGE = {"nl": "el", "de": "el", "it": "la"}


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


class ApiError(Exception):
    pass


def request(key, path, body=None, fatal=True):
    """The reply of the API; on an error the script stops, or raises ApiError when fatal is False."""
    headers = {"X-Goog-Api-Key": key, "Content-Type": "application/json"}
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(urllib.request.Request(API + path, data, headers), timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        message = f"Google Text-to-Speech said {e.code}: {e.read().decode(errors='replace')}"
        if fatal:
            sys.exit(message)
        raise ApiError(message) from None


def synthesize(key, voice, text, locale=None, fatal=True):
    locale = locale or "-".join(voice.split("-")[:2])
    reply = request(key, "text:synthesize", {
        "input": {"text": text},
        "voice": {"languageCode": locale, "name": voice},
        "audioConfig": {"audioEncoding": "MP3", "speakingRate": SPEAKING_RATE},
    }, fatal)
    return base64.b64decode(reply["audioContent"])


def sample_text(name, kind):
    """The respelling of a sample for one kind of voice (nl, de, it), or None when it has none."""
    words = SAMPLES[name][1]
    if not all(kind in versions for _, versions in words):
        return None
    return " ".join(versions[kind][0] for _, versions in words)


def describe_sample(name):
    """The text of phrase.txt: per kind of voice its conventions, its pitfalls and the sample word by word."""
    title, words = SAMPLES[name]
    lines = [title, "", "Original: " + " ".join(original for original, _ in words)]
    for kind, (label, conventions, pitfalls) in TARGETS.items():
        if sample_text(name, kind) is None:
            continue
        lines += ["", f"== {label}, voices {kind}-* ==", "", "Spoken text: " + sample_text(name, kind), "",
                  "Respelling conventions:"]
        lines += [f"  - {line}" for line in conventions]
        lines += ["", "Pitfalls in general:"]
        lines += [f"  - {line}" for line in pitfalls]
        lines += ["", "Word by word (original -> respelling, then what to listen for):"]
        for number, (original, versions) in enumerate(words, 1):
            respelled, note = versions[kind]
            lines += [f"  {number:>2}. {original}  ->  {respelled}", f"      {note}"]
    return "\n".join(lines) + "\n"


def choose_sample(sample, phrase, locales):
    """The name of the sample to compare voices with, or "custom" for --phrase."""
    if phrase:
        return "custom"
    if sample is None:
        names = {SAMPLE_FOR_LANGUAGE.get(locale.split("-")[0]) for locale in locales}
        if len(names) != 1 or None in names:
            sys.exit("Give --sample el or --sample la (or --phrase), and one language per run.")
        sample = names.pop()
    return sample


def compare_voices(key, locales, name, phrase, match, out, dry_run):
    """Makes <out>/<name>/<voice>.mp3: the sample read by every voice of the locales, to compare them by ear.
    A locale is read the respelling for its language (nl-NL: the Dutch one), or the --phrase."""
    texts = {}
    for locale in locales:
        kind = locale.split("-")[0]
        texts[locale] = phrase or sample_text(name, kind)
        if texts[locale] is None:
            sys.exit(f"The {name} sample has no respelling for {kind} voices ({locale}); use --phrase.")
    folder = out / name
    made = chars = 0
    failed = []
    for locale in locales:
        text = texts[locale]
        print(f"\n{locale}: {text}\n")
        voices = request(key, f"voices?languageCode={locale}").get("voices") or []
        for voice in sorted(voices, key=lambda v: v["name"]):
            label = voice["name"]
            if match and not any(m.lower() in label.lower() for m in match):
                continue
            description = f"{label} ({voice.get('ssmlGender', '').lower()})"
            if not dry_run:
                try:
                    audio = synthesize(key, label, text, locale, fatal=False)
                except ApiError as e:
                    failed.append(label)
                    print(f"failed {description}: {str(e)[:160]}")
                    continue
                folder.mkdir(parents=True, exist_ok=True)
                (folder / f"{label}.mp3").write_bytes(audio)
            print(description)
            made += 1
            chars += len(text)
    if made and not dry_run:
        (folder / "phrase.txt").write_text(f"Custom text: {phrase}\n" if phrase else describe_sample(name), "utf-8")
    verb = "Would make" if dry_run else "Made"
    where = "" if dry_run else f" in {folder}, with phrase.txt"
    print(f"\n{verb} {made} samples{where}, {chars} characters to bill; {len(failed)} failed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", nargs="?", type=Path, default=Path(__file__).resolve().parent.parent / "Resources",
                        help="the word folder with <language>/words/ (default: the repo's Resources)")
    parser.add_argument("--dry-run", action="store_true", help="only list what would be made")
    parser.add_argument("--language", action="append", default=[], help="only this language code or folder (repeatable)")
    parser.add_argument("--unit", action="append", default=[], help="only this unit id (repeatable)")
    parser.add_argument("--voice", action="append", default=[], metavar="CODE=VOICE", help="use another voice, such as la=es-ES-Wavenet-E")
    parser.add_argument("--list-voices", metavar="LOCALE", help="list Google's voices for a locale, such as it-IT, and stop")
    parser.add_argument("--compare-voices", action="append", default=[], metavar="LOCALE",
                        help="make a sample mp3 per voice of this locale, such as nl-NL (repeatable), and stop")
    parser.add_argument("--sample", choices=sorted(SAMPLES),
                        help="the sample for --compare-voices (default: el for nl-* and de-*, la for it-*)")
    parser.add_argument("--phrase", metavar="TEXT", help="your own, already respelled text for --compare-voices")
    parser.add_argument("--match", action="append", default=[], metavar="TEXT",
                        help="with --compare-voices, only voices whose name contains this, such as Wavenet (repeatable)")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "voice-samples",
                        help="where --compare-voices writes (default: voice-samples next to this script)")
    args = parser.parse_args()

    key = os.environ.get(KEY_VARIABLE)
    if not key and (args.list_voices or args.compare_voices or not args.dry_run):
        sys.exit(f"Set {KEY_VARIABLE} first (see tools/README.md); only --dry-run without --list-voices"
                 " or --compare-voices works without it.")
    if args.list_voices:
        for voice in request(key, f"voices?languageCode={args.list_voices}")["voices"]:
            print(voice["name"], voice["ssmlGender"].lower())
        return
    if args.compare_voices:
        name = choose_sample(args.sample, args.phrase, args.compare_voices)
        compare_voices(key, args.compare_voices, name, args.phrase, args.match, args.out, args.dry_run)
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

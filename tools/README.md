# Audio files

`make_audio.py` makes the mp3 files that Learn plays, with Google Cloud Text-to-Speech (WaveNet voices).
It needs Python 3 and PyYAML (`pip install pyyaml`).

## Setup (once)

1. Sign in at https://console.cloud.google.com and create a project.
2. Add a billing account to it. Google asks for one even when you stay within the free monthly characters.
3. Enable the **Cloud Text-to-Speech API** (APIs & Services → Library).
4. Create an API key (APIs & Services → Credentials → Create credentials → API key). Restrict it to the
   Cloud Text-to-Speech API.
5. Put the key in your shell, never in the repo:

   ```sh
   export GOOGLE_TTS_API_KEY=...   # e.g. in ~/.zshrc
   ```

Optional: set a budget alert at €1 under Billing → Budgets & alerts.

## Use

```sh
python3 tools/make_audio.py --dry-run                     # list what it would make, and the characters
python3 tools/make_audio.py --unit la-woorden-2a          # one unit
python3 tools/make_audio.py                               # everything
python3 tools/make_audio.py --list-voices it-IT           # the voices for a locale
python3 tools/make_audio.py --voice la=it-IT-Wavenet-F --unit la-woorden-2a   # try another voice
```

The files go to `Resources/<language>/audio/<unit id>/`. Copy the language folders, with their `audio`
folders, to the word folder on the phone.

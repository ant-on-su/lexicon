<p align="center">
  <img src="design/icon.png" alt="Lexicon logo" width="180">
</p>

<h1 align="center">Lexicon</h1>

<p align="center">
  A cheerful Android app for learning vocabulary from your textbooks.<br>
  French · German · Latin · Greek
</p>

## What it does

Pick a language, pick a mode, and play. The first implemented mode is **Race**: pairs of words appear in two columns, and you tap each word and its translation as fast as you can. Wrong matches cost you time, and your best time is saved.

- Race options: which units to practise, direct or reverse direction, pairs per round, number of rounds, and whether to show plurals, genders and articles.
- Words that mean the same are never mixed in one round, so there is always exactly one right answer.
- Coming soon: 
  - **Learn**: one word card at a time, with audio, grammar forms and example of use
  - **Test/(Check)** a simple, untimed check of translation and spelling

## Install a beta

Download the latest APK from the [Releases](../../releases) page on your phone and open it. Android will ask you to allow installs from your browser or file manager the first time.
## Word lists

The words live in plain YAML files, one file per textbook unit, in one folder per language.

```
<your_folder>/
  german/words/aufgabe-2-vokabeln.yaml
  latin/words/tekst-2a-2b.yaml
  greek/words/some_greek.yaml
  french/words/some_french.yaml
  ...
```

Adding a unit means adding a file and rebuilding. A file looks like this:

```yaml
meta:
  id: de-aufgabe-3-vokabeln
  title: Aufgabe 3 Vokabeln
  language: de
  translation_language: nl

entries:
  - id: haus
    term: das Haus
    forms: { plural: die Häuser }
    translation:
      - het huis
    translation_forms: { plural: de huizen }
```

Each entry has a stable `id`, the `term` in the language you are learning, and a `translation` list (one item per meaning). Forms such as plural, genitive or perfect are optional. Translations are currently in Dutch.

## How to add word lists

Just put all the words yaml files in some folder on your phone, adhering to the folders structure mentioned above (`/lang/words/yaml`). Set this folder as the "Word folder" in the app main settings. The app will read this folder with all the changes and additions on restart.<br>
You can download the latest ready word yamls from the [Resources](Resources) folder in this repo. Otherwise you can set up a GDrive folder, synchronised with a local folder on your phone.

## Roadmap

- Learn and Test modes
- Pronunciation audio and example phrases per word

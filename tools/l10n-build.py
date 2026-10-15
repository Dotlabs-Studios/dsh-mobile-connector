#!/usr/bin/env python3
"""Generates the app's two string resource files from the translation tables.

One table per area lives in ``tools/l10n/<area>.tsv``, three tab-separated
columns and no header::

    key<TAB>italian<TAB>english

The tables are the source of truth for *what exists*; the generated XML is the
source of truth for what the app ships. Nobody edits the XML by hand, because a
hand-built pair of files is exactly how a translation goes missing without
anyone noticing: the app keeps working, and one sentence is left in the wrong
language.

What this refuses to do, loudly, because each one is a bug that only shows up on
a phone in the other language:

* a key that appears twice, in one table or across two;
* a value that is empty, or an english column left as a copy of the italian one
  by accident (reported as a warning when the two are identical and the text has
  letters in it — some strings really are the same word);
* a placeholder in the translation that the original does not have, or the other
  way round — ``%1$s`` dropped in translation is a crash at runtime, and an extra
  one is a sentence with a hole in it;
* a plural whose forms do not cover the quantities each language needs;
* an apostrophe, ``&`` or ``<`` that would not survive being parsed as XML.

Usage::

    python tools/l10n-build.py            # generate
    python tools/l10n-build.py --check    # verify only, exit 1 on any problem
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
TABLES = ROOT / "tools" / "l10n"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

# The base language ships in the default folder: `values-it` would leave a phone
# set to French with resource *names* on screen, because there would be nothing
# for it to fall back to. Italian is the fallback, and that is why it is here.
# Every other language is an overlay folder named after its resource qualifier.
BASE_LOCALE = "it"
BASE_FOLDER = "values"
TRANSLATIONS = {"en": "values-en"}

PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?([sdfx])")
KEY = re.compile(r"^[a-z][a-z0-9_]*$")
APOSTROPHE = re.compile(r"(?<!\\)'")

# The quantities each language's plural rules can ask for. A language asked for
# a quantity it has no form for falls back to `other`, so `other` is the only
# one that is genuinely required; the rest are checked against the language.
QUANTITIES = {
    "it": {"one", "other"},
    "en": {"one", "other"},
}


class Problem(Exception):
    """Something that must be fixed before anything is written."""


def placeholders(value: str) -> set[str]:
    """The distinct placeholder positions a value uses, positional or not."""
    found: set[str] = set()
    for position, kind in PLACEHOLDER.findall(value):
        found.add(position or "1")  # a bare `%s` is the first one
    return found


def check_xml_safe(key: str, value: str, where: str) -> None:
    """Rejects the characters that would change the value once parsed as XML."""
    if "\n" in value or "\t" in value:
        raise Problem(f"{where}:{key}: a newline or tab inside a value — that is two strings")
    if "</" in value:
        raise Problem(f"{where}:{key}: `</` cannot appear in a value")
    if '"' in value:
        raise Problem(f'{where}:{key}: a double quote — write «» or nothing at all')


def literal(value: str) -> str:
    """The value as it must appear inside `<string>…</string>`."""
    return escape(APOSTROPHE.sub(r"\\'", value))


def attributes(kind: str, name: str, value: str) -> str:
    """The attributes a `<string>` or `<plurals>` needs, if any."""
    if kind == "plurals":
        return ""
    # No `%` placeholder means any `%` in the text is literal, and the resource
    # system would otherwise try to read it as a format specifier.
    if PLACEHOLDER.search(value):
        return ""
    return ' formatted="false"' if "%" in value else ""


def read_tables() -> tuple[dict[str, tuple[str, str, str]], dict[str, str]]:
    """Every row of every table, keyed, with the area each key came from."""
    rows: dict[str, tuple[str, str, str]] = {}
    areas: dict[str, str] = {}
    for table in sorted(TABLES.glob("*.tsv")):
        area = table.stem
        for number, line in enumerate(table.read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            where = f"{table.name}:{number}"
            if len(parts) < 2:
                raise Problem(f"{where}: expected `key<TAB>italian<TAB>english`")
            key = parts[0].strip()
            italian = parts[1]
            english = parts[2] if len(parts) > 2 else ""
            if not KEY.match(key):
                raise Problem(f"{where}: `{key}` is not a lowercase snake_case name")
            if key in rows:
                raise Problem(f"{where}: `{key}` is already defined by {areas[key]}.tsv")
            kind = "plurals" if "|" in italian or "|" in english else "string"
            for column, value in (("it", italian), ("en", english)):
                if kind == "plurals":
                    forms = [form for form in value.split("|")]
                    if any(not form.strip() for form in forms):
                        raise Problem(f"{where}: `{key}` has an empty plural form")
                    for form in forms:
                        check_xml_safe(key, form, f"{where}:{column}")
                else:
                    if not value:
                        raise Problem(f"{where}: `{key}` has no {column} value")
                    check_xml_safe(key, value, f"{where}:{column}")
            rows[key] = (kind, italian, english)
            areas[key] = area
    return rows, areas


def check_translations(rows: dict[str, tuple[str, str, str]]) -> list[str]:
    """Every check that only makes sense once both languages are in hand."""
    warnings: list[str] = []
    for key, (kind, italian, english) in sorted(rows.items()):
        if kind == "plurals":
            for locale, value in ((BASE_LOCALE, italian), ("en", english)):
                forms = {form.split("=", 1)[0].strip() if "=" in form else "" for form in value.split("|")}
                forms.discard("")
                missing = QUANTITIES[locale] - forms if forms else set()
                if missing:
                    # Unnamed forms are positional, which is how these tables are
                    # usually written: `one | other` for both languages.
                    named = [form for form in value.split("|") if "=" in form]
                    if named:
                        raise Problem(
                            f"{key}: the {locale} plurals name their forms "
                            f"({', '.join(named)}) but do not cover {', '.join(sorted(missing))}"
                        )
            it_forms = [form.strip() for form in italian.split("|")]
            en_forms = [form.strip() for form in english.split("|")]
            if len(it_forms) != len(en_forms):
                raise Problem(
                    f"{key}: {len(it_forms)} italian form(s) against {len(en_forms)} english — "
                    "a language's plural rules decide how many there are, not the table"
                )
            pairs = zip(it_forms, en_forms)
        else:
            missing = placeholders(italian) - placeholders(english)
            if missing:
                raise Problem(f"{key}: the english drops {', '.join(sorted(missing))}")
            extra = placeholders(english) - placeholders(italian)
            if extra:
                raise Problem(f"{key}: the english adds {', '.join(sorted(extra))}, which has no value")
            if italian == english and any(character.isalpha() for character in italian) and len(italian) > 3:
                warnings.append(f"{key}: italian and english are identical — {italian!r}")
            pairs = [(italian, english)]
        for it_value, en_value in pairs:
            if not en_value:
                raise Problem(f"{key}: no english")
    return warnings


def render(kind: str, key: str, value: str) -> str:
    """One resource, as XML."""
    if kind == "plurals":
        forms = [form.strip() for form in value.split("|")]
        named = any("=" in form for form in forms)
        quantities = ["one", "other"] if not named else [form.split("=", 1)[0].strip() for form in forms]
        assert len(quantities) == len(forms), key
        items = "\n".join(
            f'        <item quantity="{quantity}">{literal(form.split("=", 1)[-1].strip())}</item>'
            for quantity, form in zip(quantities, forms)
        )
        return f'    <plurals name="{key}">\n{items}\n    </plurals>\n'
    assert kind == "string"
    return f'    <string name="{key}"{attributes(kind, key, value)}>{literal(value)}</string>\n'


HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
    GENERATED FILE — do not edit.

    Written by `tools/l10n-build.py` from the tables in `tools/l10n/*.tsv`.
    An edit made here is lost the next time the tables are built, and it is also
    invisible to the test that checks the two languages against each other.

    {note}
-->
<resources>
"""

NOTES = {
    "it": """    Italian is the base language of this app, which is why it lives in the
    default `values` folder and not in `values-it`: a phone set to a language
    this app does not ship falls back to what is here.""",
    "en": """    English, the first translation. It is an overlay on the base language: the
    keys are the same, the order is not, and a missing key would be answered
    with Italian rather than with a resource name.""",
}


def build(check_only: bool) -> int:
    rows, areas = read_tables()
    if not rows:
        print("no tables in tools/l10n — nothing to build", file=sys.stderr)
        return 1
    warnings = check_translations(rows)

    written: list[Path] = []
    for locale, folder in [(BASE_LOCALE, BASE_FOLDER)] + list(TRANSLATIONS.items()):
        column = 1 if locale == BASE_LOCALE else 2
        body = "".join(
            render(value[0], key, value[column])
            for key, value in sorted(rows.items())
        )
        document = HEADER.format(note=NOTES[locale]) + body + "</resources>\n"
        target = RES / folder / "strings.xml"
        if check_only:
            if not target.exists() or target.read_text(encoding="utf-8") != document:
                print(f"{target.relative_to(ROOT)} is out of date", file=sys.stderr)
                return 1
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(document, encoding="utf-8", newline="\n")
            written.append(target)

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)
    counts: dict[str, int] = {}
    for kind, _it, _en in rows.values():
        counts[kind] = counts.get(kind, 0) + 1
    summary = ", ".join(f"{count} {kind}" for kind, count in sorted(counts.items()))
    if check_only:
        print(f"{len(rows)} keys ({summary}) — both files up to date")
    else:
        print(f"{len(rows)} keys ({summary}) from {len(set(areas.values()))} table(s):")
        for target in written:
            print(f"  {target.relative_to(ROOT)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="verify without writing")
    arguments = parser.parse_args()
    try:
        return build(arguments.check)
    except Problem as problem:
        print(f"error: {problem}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

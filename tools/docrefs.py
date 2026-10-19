#!/usr/bin/env python3
"""Keep docs/API.md citing the plugin's source without citing line numbers.

The reference used to say `lib/index.js:2269` for a fact about the port check. The
file grew by two thousand lines in the middle, and a wrong coordinate reads exactly
like a right one — which is why a first attempt at *repairing* them was thrown away:
matching prose identifiers to definitions produced confident nonsense (`port`,
`status`, `callId`).

The honest fix is the one here: a citation names the file, and the prose says what
happens. The shapes the API promises are enforced by `test/plugin-check.mjs` and by
the committed fixtures in `test/contract/`, which a line number could never do.

Usage:
    python tools/docrefs.py            # drop the stale numbers
    python tools/docrefs.py --check    # exit 1 if any line coordinate remains
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / 'docs' / 'API.md'

REF = re.compile(r'lib/index\.js:(\d+)')
# A sentence that cited two lines — "(`lib/index.js:2269`, `2276`)" — left the second
# number behind once the first lost its coordinate. These two shapes are the only
# places a bare number is that leftover, and both are anchored to the file name.
ORPHAN_COMMA = re.compile(r'`lib/index\.js`(?:,\s*`\d{2,5}`)+')
ORPHAN_RANGE = re.compile(r'`lib/index\.js`[–-]`\d{2,5}`')
ORPHAN_PLAIN = re.compile(r'lib/index\.js,\s*`\d{2,5}`')


def main() -> int:
    """Rewrite or check the coordinates, and report."""
    check_only = '--check' in sys.argv[1:]
    text = DOC.read_text(encoding='utf-8')
    coordinates = REF.findall(text)

    if check_only:
        if coordinates:
            print(f'{len(coordinates)} line coordinate(s) in docs/API.md; run python tools/docrefs.py')
            for line in coordinates[:10]:
                print(f'  lib/index.js:{line}')
            return 1
        print('docs/API.md cites the plugin by file, with no line coordinates to drift')
        return 0

    text = REF.sub('lib/index.js', text)
    text = ORPHAN_COMMA.sub('`lib/index.js`', text)
    text = ORPHAN_RANGE.sub('`lib/index.js`', text)
    text = ORPHAN_PLAIN.sub('lib/index.js', text)
    DOC.write_text(text, encoding='utf-8')
    print(f'{len(coordinates)} line coordinate(s) dropped from docs/API.md')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

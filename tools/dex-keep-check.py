#!/usr/bin/env python3
"""Check that the shrunk APK still carries the serializers it decodes with.

R8 is off by default because a missing keep rule for a serializer fails at the first
decode on a phone, not at build time. That failure has a shape this can look for
without a device: if the generated `Entry$$serializer` and its kind were stripped or
renamed, their names are not in the dex string table any more.

This is not a substitute for `docs/VERIFICATION.md` — it cannot prove a decode
*works*, only that the machinery R8 would have deleted is still there. It is the
cheap check that catches the most likely mistake.

Usage:
    python tools/dex-keep-check.py [path-to-apk]     # default: the release APK
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_APK = ROOT / 'android' / 'app' / 'build' / 'outputs' / 'apk' / 'release' / 'app-release.apk'

# Every wire type the app decodes, plus the two runtime pieces a stripped
# serializer takes with it.
TYPES = [
    'SessionSummary',
    'Workspace',
    'Entry',
    'Block',
    'MessagePage',
    'StatusResponse',
    'HealthResponse',
    'PulseResponse',
    'PairedDevice',
    'DeviceListResponse',
    'ApprovalRequest',
    'PendingQuestion',
    'WorkspaceListResponse',
]
RUNTIME = [b'$$serializer', b'KSerializer']


def main() -> int:
    """Read the dex files and report the names that survived."""
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_APK
    if not apk.exists():
        print(f'no APK at {apk}; run: gradlew assembleRelease -PenableR8=true')
        return 2

    with zipfile.ZipFile(apk) as archive:
        dexes = [name for name in archive.namelist() if name.endswith('.dex')]
        blob = b''.join(archive.read(name) for name in dexes)

    print(f'{apk.name}: {len(dexes)} dex file(s), {len(blob) // 1024} KiB of dex')
    missing: list[str] = []
    for name in TYPES:
        # The class name appears as `dev/dsh/mobile/data/Entry` in the string table,
        # and the generator's companion as `Entry$$serializer`.
        present = name.encode() in blob
        print(f'  {"ok " if present else "GONE"} {name}')
        if not present:
            missing.append(name)

    for marker in RUNTIME:
        present = marker in blob
        print(f'  {"ok " if present else "GONE"} {marker.decode()}')
        if not present:
            missing.append(marker.decode())

    if missing:
        print(f'\n{len(missing)} name(s) did not survive shrinking: {", ".join(missing)}')
        print('a keep rule in android/app/proguard-rules.pro is missing or too narrow')
        return 1
    print('\nevery wire type and its serializer machinery survived shrinking')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

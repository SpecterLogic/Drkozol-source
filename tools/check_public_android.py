"""Inspect every decoded APK entry for prohibited public identity markers."""
import argparse
from pathlib import Path
import zipfile


def find_markers(apk: Path, forbidden: list[str]) -> list[str]:
    needles = [marker.encode(encoding).lower() for marker in forbidden if marker
               for encoding in ('utf-8', 'utf-16-le', 'utf-16-be')]
    if not needles:
        raise ValueError('At least one nonempty forbidden marker is required')
    found = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            payload = archive.read(name).lower()
            if any(needle in payload or needle in name.encode().lower() for needle in needles):
                found.append(name)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--forbidden', action='append', required=True)
    args = parser.parse_args()
    found = find_markers(args.apk, args.forbidden)
    if found:
        raise SystemExit('Public identity marker found in entries: ' + ', '.join(found))
    print('Public identity scan passed for all APK entries')


if __name__ == '__main__':
    main()

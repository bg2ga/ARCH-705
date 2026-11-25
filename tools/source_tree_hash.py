#!/usr/bin/env python3
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENTRIES = [ROOT / "app/src", ROOT / "gradle"]
FILES = [ROOT / "build.gradle.kts", ROOT / "settings.gradle.kts", ROOT / "gradle.properties", ROOT / "app/build.gradle.kts", ROOT / "tools"]

def files():
    for entry in ENTRIES:
        if entry.is_dir():
            for path in entry.rglob("*"):
                if path.is_file():
                    yield path
        elif entry.is_file():
            yield entry
    for entry in FILES:
        if entry.is_dir():
            for path in entry.rglob("*"):
                if path.is_file():
                    yield path
        elif entry.is_file():
            yield entry

rows = []
for path in sorted(set(files()), key=lambda p: p.relative_to(ROOT).as_posix()):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    rows.append(f"{digest}  {path.relative_to(ROOT).as_posix()}\n")
print(hashlib.sha256("".join(rows).encode()).hexdigest())

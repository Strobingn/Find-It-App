from pathlib import Path
import re

root = Path("app/src")
issues = []
for path in root.rglob("*.kt"):
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for i, line in enumerate(lines):
        if i + 1 >= len(lines):
            continue
        a = line.rstrip()
        b = lines[i + 1].lstrip()
        if not a or not b:
            continue
        if a.strip().startswith("//") or a.strip().startswith("*") or a.strip().startswith("@"):
            continue
        if a.endswith((")", "]", "}", ",", ";", '"', "'", "{", "(", "=", "+", "-", "*", ":", ".")):
            continue
        m = re.search(r"([A-Za-z]{1,24})$", a)
        if not m:
            continue
        tok = m.group(1)
        if not re.match(r"^[a-z]{2,40}", b):
            continue
        cont = re.match(r"^([a-zA-Z0-9_?<>]+)", b)
        if not cont:
            continue
        joined = tok + cont.group(1)
        known = {
            "Bitmap",
            "scheduleRender",
            "applyCustomTerrain",
            "updateLoggedSignal",
            "private",
            "kotlin",
            "unitTests",
            "isIncludeAndroidResources",
        }
        if joined in known or (tok[-1:].isupper() and cont.group(1)[0].islower() and len(tok) < 10):
            issues.append((str(path), i + 1, a[-50:], b[:50], joined))

print(f"issues={len(issues)}")
for item in issues[:40]:
    print(item)

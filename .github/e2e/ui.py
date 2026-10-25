#!/usr/bin/env python3
# Pomocník pre family.sh: stred prvku z uiautomator dumpu podľa textu.
#   ui.py <dump.xml> <text> [exact]   → "x y" alebo nič
#   ui.py <dump.xml> --texts          → všetky texty na obrazovke (do logu krokov)
import re
import sys
import xml.etree.ElementTree as E

try:
    root = E.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(0)
want = sys.argv[2]
if want == "--texts":
    print(" | ".join(t for t in ((n.get("text") or "").strip() for n in root.iter("node")) if t))
    sys.exit(0)
exact = len(sys.argv) > 3 and sys.argv[3] == "exact"
for n in root.iter("node"):
    t = n.get("text") or ""
    if (t == want) if exact else (want in t):
        m = re.findall(r"\d+", n.get("bounds", ""))
        if len(m) == 4:
            print((int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2)
            break

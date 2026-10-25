#!/usr/bin/env python3
# Pomocník pre family.sh: čitateľný výpis stavu databáz stiahnutých z emulátora (len čítanie kópie).
#   dbsum.py <priečinok s people.db, faces.db, family.db …>
import os
import sqlite3
import sys

d = sys.argv[1]


def q(db, sql):
    path = os.path.join(d, db)
    if not os.path.exists(path):
        return [("(chýba %s)" % db,)]
    try:
        con = sqlite3.connect(path)
        rows = con.execute(sql).fetchall()
        con.close()
        return rows
    except Exception as e:  # noqa: BLE001
        return [("(chyba %s: %s)" % (db, e),)]


def section(title, rows):
    print("## " + title)
    for r in rows:
        print("   " + " | ".join("" if v is None else str(v) for v in r))


section("persons (id, meno, ručne/auto priradené tváre)", q("people.db",
        "SELECT p.id, p.name, SUM(a.is_manual = 1), SUM(a.is_manual = 0) FROM persons p "
        "LEFT JOIN face_assignments a ON a.person_id = p.id GROUP BY p.id ORDER BY p.id"))
section("faces podľa fotky (cesta, počet, id)", q("faces.db",
        "SELECT media_full_path, COUNT(*), GROUP_CONCAT(id) FROM faces GROUP BY media_full_path"))
section("indexed_photos", q("faces.db", "SELECT * FROM indexed_photos"))
section("priradenia tvárí (face_id, person_id, ručne)", q("people.db",
        "SELECT face_id, person_id, is_manual FROM face_assignments ORDER BY face_id"))
section("peers", q("family.db", "SELECT device_id, name, last_lamport_seen, last_seen_at FROM peers"))
section("family_persons", q("family.db", "SELECT * FROM family_persons"))
section("proposals", q("family.db", "SELECT id, type, state, payload_json FROM proposals"))
section("ops (zariadenie, typ, aplikované, počet)", q("family.db",
        "SELECT substr(device_id, 1, 8), type, applied_at IS NOT NULL, COUNT(*) FROM ops GROUP BY 1, 2, 3"))
section("outbox", q("family.db", "SELECT kind, state, attempts FROM outbox"))
section("bundle_inbox", q("family.db", "SELECT id, state, applied_path FROM bundle_inbox"))
section("remote_assignment", q("family.db", "SELECT * FROM remote_assignment"))

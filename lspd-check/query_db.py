# 临时诊断：列全部表及内容
import sqlite3

con = sqlite3.connect("lspd-check/modules_config.db")
cur = con.cursor()
tables = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")]
print("TABLES:", tables)
for t in tables:
    if t == "android_metadata":
        continue
    print(f"\n===== {t} =====")
    print("COLS:", [d[1] for d in cur.execute(f"PRAGMA table_info({t})")])
    for row in cur.execute(f"SELECT * FROM {t}"):
        print(row)

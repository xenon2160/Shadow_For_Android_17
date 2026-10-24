# 定位多余大括号：深度首次归零的行即类提前闭合处
lines = open(r"app\src\main\java\com\kankan\globaltraveling\HookMain.java", encoding="utf-8").readlines()
depth = 0
for i, ln in enumerate(lines, 1):
    depth += ln.count("{") - ln.count("}")
    if depth == 0 and i < len(lines) - 1:
        print(f"depth hit 0 at line {i}: {ln.rstrip()}")
        nxt = lines[i].rstrip() if i < len(lines) else "EOF"
        print(f"next line: {nxt}")
print("final depth:", depth)

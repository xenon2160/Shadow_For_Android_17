# 解析微信 dex：提取腾讯定位 SDK 类的字段与方法名
import zipfile, struct, io, sys

TARGETS = (
    "Lcom/tencent/map/geolocation/sapp/TencentLocation;",
    "Lcom/tencent/map/geolocation/sapp/TencentLocationManager;",
    "Lcom/tencent/map/geolocation/sapp/TencentLocationListener;",
)
# 附加任务：类名含 Fuzzy 的类（wx.getFuzzyLocation 相关）
FUZZY_MATCH = "Fuzzy"

def uleb(data, off):
    result = shift = 0
    while True:
        b = data[off]; off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80): break
        shift += 7
    return result, off

def sleb(data, off):
    result = shift = 0
    while True:
        b = data[off]; off += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            if b & 0x40: result |= - (1 << shift)
            break
    return result, off

def parse_dex(data):
    if data[:4] != b"dex\n": return
    str_ids_size, str_ids_off = struct.unpack_from("<II", data, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 64)
    proto_ids_off = struct.unpack_from("<I", data, 76)[0]
    field_ids_off = struct.unpack_from("<I", data, 84)[0]
    method_ids_off = struct.unpack_from("<I", data, 92)[0]
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)

    def get_string(idx):
        off = struct.unpack_from("<I", data, str_ids_off + idx * 4)[0]
        _, off = uleb(data, off)
        end = data.index(b"\x00", off)
        return data[off:end].decode("utf-8", "replace")

    def get_type(idx):
        return get_string(struct.unpack_from("<I", data, type_ids_off + idx * 4)[0])

    def get_field(idx):
        c, t, n = struct.unpack_from("<HHI", data, field_ids_off + idx * 8)
        return get_type(c), get_type(t), get_string(n)

    def get_method(idx):
        c, p, n = struct.unpack_from("<HHI", data, method_ids_off + idx * 8)
        return get_type(c), get_string(n)

    for i in range(class_defs_size):
        base = class_defs_off + i * 32
        class_idx = struct.unpack_from("<I", data, base)[0]
        cname = get_type(class_idx)
        superclass_idx = struct.unpack_from("<I", data, base + 8)[0]
        superclass = get_type(superclass_idx) if superclass_idx != 0xFFFFFFFF else None
        # 额外任务：找出继承 TencentLocation 的子类
        if superclass == "Lcom/tencent/map/geolocation/sapp/TencentLocation;":
            print(f"\n##### SUBCLASS: {cname} (super=TencentLocation) #####")
            dump_class(data, base, cname, get_type, get_field, get_method, uleb)
            continue
        if FUZZY_MATCH in cname:
            print(f"\n##### FUZZY: {cname} (super={superclass}) #####")
            dump_class(data, base, cname, get_type, get_field, get_method, uleb)
            continue
        if cname not in TARGETS: continue
        dump_class(data, base, cname, get_type, get_field, get_method, uleb)

def dump_class(data, base, cname, get_type, get_field, get_method, uleb):
        data_off = struct.unpack_from("<I", data, base + 24)[0]
        print(f"\n===== {cname} =====")
        if data_off == 0:
            print("(no class_data, likely in another dex or synthetic)"); return
        off = data_off
        static_fields_size, off = uleb(data, off)
        instance_fields_size, off = uleb(data, off)
        direct_methods_size, off = uleb(data, off)
        virtual_methods_size, off = uleb(data, off)
        # 注意：class_data 数据顺序 = static 字段 → instance 字段 → direct 方法 → virtual 方法
        idx = 0
        print(f"static fields ({static_fields_size}):")
        for _ in range(static_fields_size):
            diff, off = uleb(data, off); _, off = uleb(data, off)
            idx += diff
            fc, ft, fn = get_field(idx)
            print(f"  {fn} : {ft}")
        print(f"instance fields ({instance_fields_size}):")
        idx = 0
        for _ in range(instance_fields_size):
            diff, off = uleb(data, off); _, off = uleb(data, off)
            idx += diff
            fc, ft, fn = get_field(idx)
            print(f"  {fn} : {ft}")
        for label, count in (("direct", direct_methods_size), ("virtual", virtual_methods_size)):
            print(f"{label} methods ({count}):")
            idx = 0
            for _ in range(count):
                diff, off = uleb(data, off); _, off = uleb(data, off); _, off = uleb(data, off)
                idx += diff
                mc, mn = get_method(idx)
                if any(k in mn for k in ("get", "set", "request", "onLocation", "getInstance")):
                    print(f"  {mn}")

apk = zipfile.ZipFile("mm-base.apk")
dex_names = [n for n in apk.namelist() if n.endswith(".dex")]
print("dex files:", len(dex_names))
found = set()
for n in dex_names:
    data = apk.read(n)
    out = io.StringIO()
    old = sys.stdout; sys.stdout = out
    try:
        parse_dex(data)
    finally:
        sys.stdout = old
    text = out.getvalue()
    if "=====" in text:
        print(f"\n### {n}"); print(text)

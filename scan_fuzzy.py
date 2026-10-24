# 扫描微信 dex：找出声明 fuzzy location 相关方法的类
import zipfile, struct

def uleb(data, off):
    result = shift = 0
    while True:
        b = data[off]; off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80): break
        shift += 7
    return result, off

apk = zipfile.ZipFile("mm-base.apk")
hits = set()
for n in [x for x in apk.namelist() if x.endswith(".dex")]:
    data = apk.read(n)
    if data[:4] != b"dex\n":
        continue
    _, str_ids_off = struct.unpack_from("<II", data, 56)
    _, type_ids_off = struct.unpack_from("<II", data, 64)
    _, method_ids_off = struct.unpack_from("<II", data, 88)  # method_ids_size@88, off@92
    method_ids_off = struct.unpack_from("<I", data, 92)[0]
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)

    def get_string(idx):
        off = struct.unpack_from("<I", data, str_ids_off + idx * 4)[0]
        _, off = uleb(data, off)
        end = data.index(b"\x00", off)
        return data[off:end].decode("utf-8", "replace")

    def get_type(idx):
        return get_string(struct.unpack_from("<I", data, type_ids_off + idx * 4)[0])

    def get_method(idx):
        c, p, nm = struct.unpack_from("<HHI", data, method_ids_off + idx * 8)
        return get_type(c), get_string(nm)

    for i in range(class_defs_size):
        base = class_defs_off + i * 32
        class_idx = struct.unpack_from("<I", data, base)[0]
        data_off = struct.unpack_from("<I", data, base + 24)[0]
        if data_off == 0:
            continue
        off = data_off
        sf, off = uleb(data, off); inf, off = uleb(data, off)
        dm, off = uleb(data, off); vm, off = uleb(data, off)
        for _ in range(sf + inf):
            _, off = uleb(data, off); _, off = uleb(data, off)
        for count in (dm, vm):
            idx = 0
            for _ in range(count):
                diff, off = uleb(data, off); _, off = uleb(data, off); _, off = uleb(data, off)
                idx += diff
                mc, mn = get_method(idx)
                if "uzzy" in mn and "ocation" in mn:
                    hits.add((get_type(class_idx), mn))

for h in sorted(hits):
    print(h)

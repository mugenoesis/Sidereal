#!/usr/bin/env python3
"""Prints the lens-related EXIF values of a JPEG: focal length, 35 mm equivalent, f-number, lens model.

    python3 tools/exif_info.py photo.jpg

Used by tools/lens_step.sh to learn which focal length a lens is really at. Prints numbers only; the picture is not kept.
"""
import struct
import sys


def read_exif(path):
    d = open(path, "rb").read(400_000)
    i = d.find(b"Exif\0\0")
    if i < 0:
        return {}
    t = i + 6
    e = "<" if d[t:t + 2] == b"II" else ">"
    u16 = lambda o: struct.unpack(e + "H", d[t + o:t + o + 2])[0]
    u32 = lambda o: struct.unpack(e + "I", d[t + o:t + o + 4])[0]

    def ifd(off):
        out = {}
        for k in range(u16(off)):
            o = off + 2 + 12 * k
            tag, typ, cnt = u16(o), u16(o + 2), u32(o + 4)
            size = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}.get(typ, 1) * cnt
            val = u32(o + 8) if size > 4 else o + 8
            out[tag] = (typ, cnt, val)
        return out

    def value(entry):
        typ, cnt, off = entry
        if typ == 5:
            n, dn = struct.unpack(e + "II", d[t + off:t + off + 8])
            return n / dn if dn else None
        if typ == 3:
            return struct.unpack(e + "H", d[t + off:t + off + 2])[0]
        if typ == 2:
            return d[t + off:t + off + cnt].split(b"\0")[0].decode("latin-1", "replace")
        return None

    main = ifd(u32(4))
    # the Exif sub-IFD pointer is a LONG stored inline: read the number at its data position
    sub = ifd(u32(main[0x8769][2])) if 0x8769 in main else {}
    names = {0x920A: "focal_length_mm", 0xA405: "focal_length_35mm_equiv", 0x829D: "f_number",
             0xA434: "lens_model", 0xA433: "lens_make", 0x8827: "iso"}
    return {names[k]: value(v) for k, v in sub.items() if k in names}


if __name__ == "__main__":
    info = read_exif(sys.argv[1])
    for k in ("lens_make", "lens_model", "focal_length_mm", "focal_length_35mm_equiv", "f_number", "iso"):
        print(f"EXIF {k} = {info.get(k)}")

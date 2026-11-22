#!/usr/bin/env python3
import copy
import os
import struct
import sys
import tempfile
import zipfile

ALIGN_EXTRA_ID = 0xD935
LOCAL_HEADER_MAGIC = b"PK" + bytes((3, 4))

def strip_align_extra(extra: bytes) -> bytes:
    out = bytearray()
    i = 0
    while i + 4 <= len(extra):
        tag, size = struct.unpack_from("<HH", extra, i)
        end = i + 4 + size
        if end > len(extra):
            raise ValueError("Malformed ZIP extra field")
        if tag != ALIGN_EXTRA_ID:
            out.extend(extra[i:end])
        i = end
    if i != len(extra):
        out.extend(extra[i:])
    return bytes(out)

def data_offset(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    fp = zf.fp
    fp.seek(info.header_offset)
    header = fp.read(30)
    if len(header) != 30 or header[:4] != LOCAL_HEADER_MAGIC:
        raise ValueError(f"Invalid local ZIP header: {info.filename}")
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len

def verify(path: str, alignment: int) -> int:
    failures = []
    with zipfile.ZipFile(path, "r") as zf:
        for info in zf.infolist():
            offset = data_offset(zf, info)
            if offset % alignment:
                failures.append((info.filename, offset))
    if failures:
        for name, offset in failures[:30]:
            print(f"unaligned: {name} offset={offset}", file=sys.stderr)
        print(f"zipalign: {len(failures)} entries are not aligned to {alignment}", file=sys.stderr)
        return 1
    print(f"Verification successful: all ZIP entries aligned to {alignment} bytes")
    return 0

def align_zip(src: str, dst: str, alignment: int) -> None:
    if alignment <= 0 or alignment & (alignment - 1):
        raise ValueError("Alignment must be a positive power of two")
    if os.path.abspath(src) == os.path.abspath(dst):
        fd, tmp = tempfile.mkstemp(prefix="zipalign-")
        os.close(fd)
        try:
            align_zip(src, tmp, alignment)
            os.replace(tmp, dst)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)
        return
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w") as zout:
        zout.comment = zin.comment
        for original in zin.infolist():
            data = zin.read(original)
            info = copy.copy(original)
            base_extra = strip_align_extra(info.extra)
            encoded_name, _flags = info._encodeFilenameFlags()
            start_without_padding = zout.fp.tell() + 30 + len(encoded_name) + len(base_extra)
            padding = (-start_without_padding) % alignment
            if 0 < padding < 4:
                padding += alignment
            if padding:
                base_extra += struct.pack("<HH", ALIGN_EXTRA_ID, padding - 4)
                base_extra += bytes(padding - 4)
            info.extra = base_extra
            zout.writestr(info, data, compress_type=info.compress_type)

def main(argv):
    args = list(argv)
    verify_only = "-c" in args
    alignment = 4
    if "-P" in args:
        ix = args.index("-P")
        try:
            alignment = int(args[ix + 1])
        except (IndexError, ValueError):
            raise SystemExit("zipalign: invalid -P alignment")
    else:
        numeric = [a for a in args if a.isdigit()]
        if numeric:
            alignment = int(numeric[-1])
    candidates = [a for a in args if not a.startswith("-") and not a.isdigit()]
    if verify_only:
        if len(candidates) < 1:
            raise SystemExit("usage: zipalign -c [alignment] file.apk")
        return verify(candidates[-1], alignment)
    if len(candidates) < 2:
        raise SystemExit("usage: zipalign [-f] [-v] [alignment] input.apk output.apk")
    src, dst = candidates[-2], candidates[-1]
    align_zip(src, dst, alignment)
    if "-v" in args:
        print(f"Aligned {src} -> {dst} ({alignment}-byte alignment)")
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:
        print(f"zipalign: {exc}", file=sys.stderr)
        sys.exit(2)

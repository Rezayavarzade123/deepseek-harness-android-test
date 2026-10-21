#!/usr/bin/env python3
"""Rewrite DT_RUNPATH in place for the bundled Android runtime.

The Node build we ship comes from Termux and therefore hardcodes
``/data/data/com.termux/files/usr/lib`` as its runpath. On the phone the
runtime lives in app-private storage instead, so every ELF file gets a
relative runpath that Android's linker expands for us:

* ``runtime/node``      -> ``$ORIGIN/lib``
* ``runtime/lib/*.so``  -> ``$ORIGIN``

Bionic supports ``$ORIGIN`` in DT_RUNPATH from API 24 on, and relative paths
keep working for secondary users and work profiles where the absolute
``/data/data/...`` path would not exist.

Replacing the string only ever *shrinks* it, so this rewrites the existing
``.dynstr`` bytes (NUL padded) and never moves a section, which keeps the
signed/loaded layout of the binary untouched.

Usage:
    patch-elf-rpath.py <runpath> <elf> [<elf> ...]
    patch-elf-rpath.py --show <elf> [<elf> ...]

Exit status is non-zero when a file has no DT_RUNPATH/DT_RPATH, or when the
replacement would not fit.
"""

import pathlib
import struct
import sys

PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_RPATH = 15
DT_RUNPATH = 29


def _program_headers(data):
    endian = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
    phnum = struct.unpack_from(endian + "H", data, 0x38)[0]
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type = struct.unpack_from(endian + "I", data, base)[0]
        p_offset = struct.unpack_from(endian + "Q", data, base + 0x08)[0]
        p_vaddr = struct.unpack_from(endian + "Q", data, base + 0x10)[0]
        p_filesz = struct.unpack_from(endian + "Q", data, base + 0x20)[0]
        yield p_type, p_offset, p_vaddr, p_filesz
    return


def read_runpath(path):
    """Return the DT_RUNPATH string of *path*, or None when it has none."""
    data = pathlib.Path(path).read_bytes()
    endian = "<" if data[5] == 1 else ">"
    loads, dynamic = [], None
    for p_type, p_offset, p_vaddr, p_filesz in _program_headers(data):
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        return None

    def to_offset(vaddr):
        for base, offset, size in loads:
            if base <= vaddr < base + size:
                return offset + (vaddr - base)
        raise SystemExit(f"{path}: address {vaddr:#x} is not backed by a PT_LOAD")

    strtab = None
    entries = []
    offset, size = dynamic
    for index in range(size // 16):
        tag, value = struct.unpack_from(endian + "QQ", data, offset + index * 16)
        if tag == DT_NULL:
            break
        if tag == DT_STRTAB:
            strtab = value
        entries.append((tag, value))
    if strtab is None:
        return None
    values = [value for tag, value in entries if tag == DT_RUNPATH]
    if not values:
        return None
    # String-valued dynamic entries are offsets into the string table.
    start = to_offset(strtab) + values[0]
    end = data.index(b"\0", start)
    return data[start:end].decode("utf-8", "replace")


def patch(path, runpath):
    data = bytearray(pathlib.Path(path).read_bytes())
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit(f"{path}: not a 64-bit ELF file")
    endian = "<" if data[5] == 1 else ">"

    loads, dynamic = [], None
    for p_type, p_offset, p_vaddr, p_filesz in _program_headers(data):
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        raise SystemExit(f"{path}: no PT_DYNAMIC segment")

    def to_offset(vaddr):
        for base, offset, size in loads:
            if base <= vaddr < base + size:
                return offset + (vaddr - base)
        raise SystemExit(f"{path}: address {vaddr:#x} is not backed by a PT_LOAD")

    strtab = None
    entries = []
    offset, size = dynamic
    for index in range(size // 16):
        tag, value = struct.unpack_from(endian + "QQ", data, offset + index * 16)
        if tag == DT_NULL:
            break
        if tag == DT_STRTAB:
            strtab = value
        entries.append((tag, value))
    if strtab is None:
        raise SystemExit(f"{path}: no DT_STRTAB")

    # DT_NEEDED/DT_RPATH/DT_RUNPATH values are byte offsets into the string
    # table, not virtual addresses (see ELF gABI); add the table's file offset.
    targets = [value for tag, value in entries if tag in (DT_RPATH, DT_RUNPATH)]
    tags = {tag for tag, _ in entries}
    if not targets:
        raise SystemExit(f'{path}: no DT_RUNPATH to rewrite (add one with patchelf first)')
    if DT_RPATH in tags:
        raise SystemExit(f"{path}: has DT_RPATH; convert it to DT_RUNPATH first")

    encoded = runpath.encode() + b"\0"
    for value in targets:
        start = to_offset(strtab) + value
        end = data.index(b"\0", start)
        current = data[start:end].decode("utf-8", "replace")
        room = end - start + 1
        if len(encoded) > room:
            raise SystemExit(f"{path}: {runpath!r} ({len(encoded)}B) does not fit in {room}B (was {current!r})")
        # Replace the string *including* its terminator so the file keeps its
        # exact length; anything else would shift every later section.
        data[start:end + 1] = encoded + b"\0" * (room - len(encoded))
        print(f"  {pathlib.Path(path).name:22} {current!r} -> {runpath!r}")
    pathlib.Path(path).write_bytes(bytes(data))


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    if argv[1] == "--show":
        for name in argv[2:]:
            print(f"{name}: {read_runpath(name) or '(no runpath)'}")
        return 0
    runpath, files = argv[1], argv[2:]
    for name in files:
        patch(name, runpath)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

"""Minimal patchelf-compatible helper for building the Python runtime on Windows.

Only the operations used by PreparePythonAarTask are supported:
  --page-size <bytes> --set-soname <name> <file>
"""

import os
import sys
import tempfile

import lief

LOAD = lief.ELF.Segment.TYPE.LOAD
RELRO = lief.ELF.Segment.TYPE.GNU_RELRO


def parse_args(argv):
    page_size = None
    soname = None
    path = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--page-size":
            index += 1
            page_size = int(argv[index], 0)
        elif arg == "--set-soname":
            index += 1
            soname = argv[index]
        elif arg.startswith("--"):
            raise SystemExit(f"unsupported option: {arg}")
        elif path is None:
            path = arg
        else:
            raise SystemExit("only one input file is supported")
        index += 1
    if path is None:
        raise SystemExit("missing input file")
    return page_size, soname, path


def patch_once(page_size, soname, path):
    binary = lief.parse(path)
    if binary is None:
        raise SystemExit(f"unable to parse {path}")
    if page_size is not None:
        for segment in binary.segments:
            if segment.type == LOAD:
                segment.alignment = page_size
        for segment in binary.segments:
            if segment.type == RELRO:
                end = segment.virtual_address + segment.virtual_size
                aligned = end - end % page_size
                if aligned > segment.virtual_address:
                    segment.virtual_size = aligned - segment.virtual_address
    if soname is not None:
        entry = binary.get(lief.ELF.DynamicEntry.TAG.SONAME)
        if entry is None:
            needed = [item for item in binary.dynamic_entries
                      if item.tag == lief.ELF.DynamicEntry.TAG.NEEDED and item.name == soname]
            if not needed:
                binary.add_library(soname)
                needed = [item for item in binary.dynamic_entries
                          if item.tag == lief.ELF.DynamicEntry.TAG.NEEDED and item.name == soname]
            entry = needed[-1]
            entry.tag = lief.ELF.DynamicEntry.TAG.SONAME
        else:
            entry.name = soname
    directory = os.path.dirname(os.path.abspath(path))
    descriptor, temporary = tempfile.mkstemp(dir=directory, suffix=".patchelf")
    os.close(descriptor)
    try:
        binary.write(temporary)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main(argv):
    page_size, soname, path = parse_args(argv)
    # LIEF may re-lay out segments when the dynamic string table grows, so apply
    # the page-size constraints a second time after the SONAME has been added.
    patch_once(page_size, soname, path)
    patch_once(page_size, soname, path)


if __name__ == "__main__":
    main(sys.argv[1:])

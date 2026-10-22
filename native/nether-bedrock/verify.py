import ctypes
import hashlib
import json
import os
import struct
import sys
import time
from pathlib import Path

expected_seed = 765906787396911863
seed_hash = int.from_bytes(hashlib.sha256(struct.pack("<Q", expected_seed)).digest()[:8], "little")
fixture = Path(__file__).parent / "fixtures" / "known-seed-765906787396911863.txt"
entries = []
for line in fixture.read_text().splitlines():
    values = line.split()
    if len(values) >= 4 and values[1] in ("4", "123"):
        entries.extend((int(values[0]), int(values[1]), int(values[2]), 0))

library = ctypes.CDLL(str(Path(sys.argv[1]).resolve()))
library.seedy_nether_crack.argtypes = [ctypes.POINTER(ctypes.c_int32), ctypes.c_size_t, ctypes.c_uint64, ctypes.c_uint64, ctypes.POINTER(ctypes.c_int64)]
library.seedy_nether_crack.restype = ctypes.c_int64
blocks = (ctypes.c_int32 * len(entries))(*entries)
result = (ctypes.c_int64 * 1)()
started = time.monotonic()
count = library.seedy_nether_crack(blocks, len(entries) // 4, min(8, max(1, os.cpu_count() or 1)), seed_hash, result)
elapsed = round(time.monotonic() - started, 2)
print(json.dumps({"observations": len(entries) // 4, "result": count, "seed": result[0], "matches": count == 1 and result[0] == expected_seed, "seconds": elapsed}))
if count != 1 or result[0] != expected_seed:
    raise SystemExit(1)

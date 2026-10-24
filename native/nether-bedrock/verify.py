import ctypes
import hashlib
import json
import os
import struct
import sys
import time
import threading
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
workers = min(4, max(1, (os.cpu_count() or 1) // 2))
library.seedy_nether_budget(35)
library.seedy_nether_reset()
library.seedy_nether_cancel()
assert library.seedy_nether_crack(blocks, len(entries) // 4, workers, seed_hash, result) == -4
for attempt in range(3):
    library.seedy_nether_reset()
    cancelled_result = []
    task = threading.Thread(target=lambda: cancelled_result.append(library.seedy_nether_crack(blocks, len(entries) // 4, workers, seed_hash ^ 1, result)))
    task.start()
    time.sleep(0.05)
    stopped_at = time.monotonic()
    library.seedy_nether_cancel()
    task.join(1)
    assert not task.is_alive(), "Nether workers did not stop within one second"
    assert cancelled_result == [-4], cancelled_result
    print(json.dumps({"cancel_attempt": attempt + 1, "stop_ms": round((time.monotonic() - stopped_at) * 1000, 2)}))
library.seedy_nether_reset()
library.seedy_nether_budget(int(sys.argv[2]) if len(sys.argv) > 2 else 85)
started = time.monotonic()
count = library.seedy_nether_crack(blocks, len(entries) // 4, workers, seed_hash, result)
elapsed = round(time.monotonic() - started, 2)
print(json.dumps({"observations": len(entries) // 4, "result": count, "seed": result[0], "matches": count == 1 and result[0] == expected_seed, "seconds": elapsed}))
if count != 1 or result[0] != expected_seed:
    raise SystemExit(1)

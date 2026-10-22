import argparse
import collections
import gzip
import io
import struct
import zlib
from pathlib import Path

import nbtlib


BLOCK_TYPES = {
    "minecraft:bedrock": 0,
    "minecraft:raw_iron_block": 1,
    "minecraft:raw_copper_block": 1,
    "minecraft:deepslate_iron_ore": 2,
    "minecraft:copper_ore": 2,
    "minecraft:tuff": 3,
    "minecraft:granite": 3,
}


def read_chunk(region, chunk_x, chunk_z):
    with region.open("rb") as stream:
        slot = (chunk_x & 31) + (chunk_z & 31) * 32
        stream.seek(slot * 4)
        location = int.from_bytes(stream.read(4), "big")
        if location == 0:
            return None
        stream.seek((location >> 8) * 4096)
        length = int.from_bytes(stream.read(4), "big")
        codec = stream.read(1)[0]
        payload = stream.read(length - 1)
    if codec == 1:
        payload = gzip.decompress(payload)
    elif codec == 2:
        payload = zlib.decompress(payload)
    elif codec != 3:
        raise ValueError(f"unsupported chunk compression {codec}")
    return nbtlib.File.parse(io.BytesIO(payload))


def name_of(value):
    if isinstance(value, nbtlib.Compound):
        return str(value.get("Name", value.get("")))
    return str(value)


def entries_in_chunk(chunk):
    chunk_x = int(chunk["xPos"])
    chunk_z = int(chunk["zPos"])
    for section in chunk["sections"]:
        if "block_states" not in section:
            continue
        base_y = int(section["Y"]) * 16
        if base_y > 50 or base_y + 15 < -63:
            continue
        states = section["block_states"]
        palette = [name_of(value) for value in states["palette"]]
        types = [BLOCK_TYPES.get(name, 5 if name == "minecraft:deepslate" else 4 if name == "minecraft:stone" else -1) for name in palette]
        if all(value < 0 for value in types):
            continue
        data = states.get("data")
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        mask = (1 << bits) - 1
        for index in range(4096):
            y = base_y + (index >> 8)
            if y < -63 or y > 50:
                continue
            palette_index = 0 if data is None else ((int(data[index // per_long]) & ((1 << 64) - 1)) >> ((index % per_long) * bits)) & mask
            block_type = types[palette_index]
            if block_type == 4 and not 1 <= y <= 7:
                continue
            if block_type == 5 and not (-63 <= y <= -60 or 1 <= y <= 7):
                continue
            if block_type >= 0:
                yield chunk_x * 16 + (index & 15), y, chunk_z * 16 + (index >> 4 & 15), block_type


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("world", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--radius", type=int, default=16)
    args = parser.parse_args()
    spawn = nbtlib.load(args.world / "level.dat")["Data"]["spawn"]["pos"]
    center_x = int(spawn[0]) >> 4
    center_z = int(spawn[2]) >> 4
    regions = args.world / "dimensions/minecraft/overworld/region"
    counts = collections.Counter()
    chunks = 0
    with args.output.open("wb") as output:
        for chunk_x in range(center_x - args.radius, center_x + args.radius + 1):
            for chunk_z in range(center_z - args.radius, center_z + args.radius + 1):
                region = regions / f"r.{chunk_x // 32}.{chunk_z // 32}.mca"
                if not region.exists():
                    continue
                chunk = read_chunk(region, chunk_x, chunk_z)
                if chunk is None or str(chunk["Status"]) != "minecraft:full":
                    continue
                chunks += 1
                for entry in entries_in_chunk(chunk):
                    output.write(struct.pack(">iiii", *entry))
                    counts[entry[3]] += 1
    print("seed", int(nbtlib.load(args.world / "data/minecraft/world_gen_settings.dat")["data"]["seed"]))
    print("chunks", chunks, "entries", sum(counts.values()), "types", dict(counts))


if __name__ == "__main__":
    main()

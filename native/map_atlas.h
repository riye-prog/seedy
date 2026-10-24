#pragma once
#include <imgui.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <unordered_map>
#include <vector>
#include <utility>

namespace MapAtlas {
    constexpr int side = 2048, tileSide = 32, pitch = 34, columns = side / pitch, capacity = columns * columns, uploadLimit = 64;
    struct Upload { int x, y; std::array<ImU32, pitch * pitch> pixels; };
    struct Entry { int slot; uint64_t used; };
    struct Region { ImVec2 first, last; bool ready = false; };
    inline std::unordered_map<uint64_t, Entry> entries;
    inline std::vector<Upload> uploads;
    inline ImTextureID texture = 0;
    inline ImTextureID nearestTexture = 0;
    inline uint64_t frame = 0, sequence = 0;
    inline bool writable = false;

    inline void begin() { ++frame; uploads.clear(); writable = false; }
    inline void reset() { entries.clear(); uploads.clear(); texture = 0; nearestTexture = 0; writable = false; }
    inline Region region(int slot) {
        int x = (slot % columns) * pitch + 1, y = (slot / columns) * pitch + 1;
        return {{x / float(side),y / float(side)},{(x + tileSide) / float(side),(y + tileSide) / float(side)},true};
    }
    template<class Raster> Region get(uint64_t key, Raster raster) {
        auto found = entries.find(key);
        if (found != entries.end()) { found->second.used = frame; return region(found->second.slot); }
        if (!texture || !writable || uploads.size() >= uploadLimit) return {};
        int slot = static_cast<int>(entries.size());
        if (slot == capacity) {
            auto oldest = entries.end();
            for (auto it = entries.begin(); it != entries.end(); ++it) if (it->second.used < frame && (oldest == entries.end() || it->second.used < oldest->second.used)) oldest = it;
            if (oldest == entries.end()) return {};
            slot = oldest->second.slot;
            entries.erase(oldest);
        }
        Upload update{(slot % columns) * pitch,(slot / columns) * pitch,{}};
        std::array<ImU32,tileSide * tileSide> pixels;
        raster(pixels);
        for (int y = 0; y < pitch; ++y) for (int x = 0; x < pitch; ++x) update.pixels[y * pitch + x] = pixels[std::clamp(y - 1,0,tileSide - 1) * tileSide + std::clamp(x - 1,0,tileSide - 1)];
        uploads.push_back(std::move(update));
        entries.emplace(key,Entry{slot,frame});
        return region(slot);
    }
}

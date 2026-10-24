#pragma once
#include <imgui.h>
#include <algorithm>
#include <cmath>

struct DrawRegion {
    int x = 0, y = 0, width = 0, height = 0;
};

inline DrawRegion drawRegion(const ImDrawData *data, int width, int height) {
    if (!data || data->TotalVtxCount == 0 || width <= 0 || height <= 0) return {};
    float left = static_cast<float>(width), top = static_cast<float>(height), right = 0, bottom = 0;
    for (auto *list : data->CmdLists) for (const auto &vertex : list->VtxBuffer) {
        left = std::min(left,vertex.pos.x); top = std::min(top,vertex.pos.y);
        right = std::max(right,vertex.pos.x); bottom = std::max(bottom,vertex.pos.y);
    }
    int x1 = std::clamp(static_cast<int>(std::floor(left - 1)),0,width);
    int y1 = std::clamp(static_cast<int>(std::floor(top - 1)),0,height);
    int x2 = std::clamp(static_cast<int>(std::ceil(right + 1)),0,width);
    int y2 = std::clamp(static_cast<int>(std::ceil(bottom + 1)),0,height);
    return {x1,height - y2,std::max(0,x2 - x1),std::max(0,y2 - y1)};
}

inline void flipDrawData(ImDrawData *data, int height) {
    for (auto *list : data->CmdLists) {
        for (auto &vertex : list->VtxBuffer) vertex.pos.y = height - vertex.pos.y;
        for (auto &command : list->CmdBuffer) {
            float top = command.ClipRect.y;
            command.ClipRect.y = height - command.ClipRect.w;
            command.ClipRect.w = height - top;
        }
    }
}

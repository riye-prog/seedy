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
    for (auto *list : data->CmdLists) for (const auto &command : list->CmdBuffer) {
        if (!command.ElemCount || command.UserCallback) continue;
        float x1 = command.ClipRect.z, y1 = command.ClipRect.w, x2 = command.ClipRect.x, y2 = command.ClipRect.y;
        for (unsigned int i = 0; i < command.ElemCount; ++i) {
            const auto &point = list->VtxBuffer[list->IdxBuffer[command.IdxOffset + i] + command.VtxOffset].pos;
            x1 = std::min(x1,point.x); y1 = std::min(y1,point.y); x2 = std::max(x2,point.x); y2 = std::max(y2,point.y);
        }
        x1 = std::max(x1,command.ClipRect.x); y1 = std::max(y1,command.ClipRect.y);
        x2 = std::min(x2,command.ClipRect.z); y2 = std::min(y2,command.ClipRect.w);
        if (x1 >= x2 || y1 >= y2) continue;
        left = std::min(left,x1); top = std::min(top,y1); right = std::max(right,x2); bottom = std::max(bottom,y2);
    }
    if (left >= right || top >= bottom) return {};
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

#pragma once
#include <unordered_map>

struct TerrainTile {
    int x = 0, z = 0;
    uint64_t key = ++MapAtlas::sequence << 1;
    uint64_t used = 0;
    std::array<ImU32,1024> pixels;
};

static std::unordered_map<uint64_t,TerrainTile> terrainTiles;
static long long terrainEpoch = -1;
static std::string terrainDimension;
static MapView miniView;

static void receiveTerrain(const Json &incoming) {
    if (incoming.contains("reset")) {
        terrainEpoch = incoming.value("reset",-1LL); terrainDimension = incoming.value("dimension",""); terrainTiles.clear(); return;
    }
    if (incoming.value("epoch",-2LL) != terrainEpoch || incoming.value("dimension","") != terrainDimension || !incoming.contains("runs")) return;
    TerrainTile tile; tile.x = incoming.value("x",0); tile.z = incoming.value("z",0); tile.used = MapAtlas::frame;
    int offset = 0;
    const auto &runs = incoming["runs"];
    for (size_t i = 0; i + 1 < runs.size(); i += 2) {
        int count = runs[i].get<int>(), rgb = runs[i + 1].get<int>();
        if (count <= 0 || count > 256 - offset) return;
        ImU32 color = IM_COL32((rgb >> 16) & 255,(rgb >> 8) & 255,rgb & 255,255);
        for (int j = 0; j < count; ++j, ++offset) {
            int index = (offset / 16) * 64 + (offset % 16) * 2;
            tile.pixels[index] = tile.pixels[index + 1] = tile.pixels[index + 32] = tile.pixels[index + 33] = color;
        }
    }
    if (offset != 256) return;
    uint64_t key = (uint64_t(uint32_t(tile.x)) << 32) | uint32_t(tile.z);
    auto found = terrainTiles.find(key);
    if (found != terrainTiles.end() && found->second.pixels == tile.pixels) return;
    terrainTiles.insert_or_assign(key,std::move(tile));
    if (terrainTiles.size() > 1024) {
        auto oldest = terrainTiles.begin();
        for (auto it = terrainTiles.begin(); it != terrainTiles.end(); ++it) if (it->second.used < oldest->second.used) oldest = it;
        terrainTiles.erase(oldest);
    }
}

static void minimapSettings() {
    ImGui::Checkbox("Enable minimap (F10)",&preferences.miniEnabled);
    const char *modes[] = {"Terrain + seed biomes","Loaded terrain","Seed biomes"};
    ImGui::SetNextItemWidth(260 * uiScale); ImGui::Combo("Map layers",&preferences.miniMode,modes,3);
    const char *anchors[] = {"Top left","Top right","Bottom left","Bottom right"};
    ImGui::SetNextItemWidth(260 * uiScale); ImGui::Combo("Minimap position",&preferences.miniAnchor,anchors,4);
    ImGui::SetNextItemWidth(260 * uiScale); ImGui::SliderFloat("Minimap size",&preferences.miniSize,128,360,"%.0f px");
    ImGui::SetNextItemWidth(260 * uiScale); ImGui::SliderFloat("Blocks per pixel",&preferences.miniZoom,0.5f,8,"%.2f",ImGuiSliderFlags_Logarithmic);
    ImGui::Checkbox("Rotate with player",&preferences.miniRotate);
    ImGui::Checkbox("Structure markers##mini",&preferences.miniStructures);
    std::string dimension = state.value("dimension","overworld");
    const char *overworld[] = {"Villages","Ancient cities","Desert temples","Strongholds"};
    const char *nether[] = {"Fortresses","Bastions","Ruined portals","Nether fossils"};
    const char *end[] = {"End cities","End cities","End cities","End cities"};
    ImGui::SetNextItemWidth(260 * uiScale); ImGui::Combo("Minimap structures",&preferences.miniTarget,dimension == "the_nether" ? nether : dimension == "the_end" ? end : overworld,4);
    ImGui::TextWrapped("Loaded terrain is sampled from chunks your client already has. Seed biomes fill unexplored areas after recovery. Nether terrain uses a slice near your height.");
}

static void drawMinimap(int width, int height) {
    if (!preferences.miniEnabled || !state.value("connected",false)) return;
    float scale = uiScale;
    float size = std::clamp(preferences.miniSize * scale,96.0f,std::max(96.0f,std::min(width - 32.0f,height - 100.0f)));
    ImVec2 window{size + 20 * scale,size + 66 * scale};
    float margin = 16 * scale;
    ImVec2 origin{preferences.miniAnchor % 2 ? width - window.x - margin : margin,preferences.miniAnchor >= 2 ? height - window.y - margin : margin};
    ImGui::SetNextWindowPos(origin); ImGui::SetNextWindowSize(window);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,{10 * scale,10 * scale});
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding,12 * scale);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize,0);
    ImGui::PushStyleColor(ImGuiCol_WindowBg,{0.035f,0.037f,0.044f,0.94f});
    ImGui::Begin("Seedy minimap",nullptr,ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoNav);
    ImGui::TextUnformatted("Minimap");
    const char *source = preferences.miniMode == 1 ? "Terrain" : preferences.miniMode == 2 ? "Biomes" : "Terrain + Biomes";
    auto textSize = ImGui::CalcTextSize(source); ImGui::SameLine(window.x - textSize.x - 12 * scale); ImGui::TextDisabled("%s",source);
    ImVec2 start = ImGui::GetCursorScreenPos(), end{start.x + size,start.y + size}, center{start.x + size / 2,start.y + size / 2};
    auto draw = ImGui::GetWindowDrawList(); draw->AddRectFilled(start,end,IM_COL32(24,29,37,255),4 * scale); draw->PushClipRect(start,end,true);
    std::string dimension = state.value("dimension","overworld"), seed = state.value("seed","");
    const auto &incoming = miniView.data;
    bool predicted = state.value("gameVersion","") == state.value("generationVersion","") && !seed.empty() && incoming.value("seed","") == seed && incoming.value("dimension","") == dimension;

    double angle = preferences.miniRotate ? 3.141592653589793 - liveMapYaw * 0.0174532925199433 : 0;
    double cosine = std::cos(angle), sine = std::sin(angle), zoom = preferences.miniZoom / scale;
    auto point = [&](double x, double z) { double dx = (x - liveMapX) / zoom, dz = (z - liveMapZ) / zoom; return ImVec2{float(center.x + dx * cosine - dz * sine),float(center.y + dx * sine + dz * cosine)}; };
    auto visible = [&](double x, double z, double span) { return std::abs(x + span / 2 - liveMapX) < size * zoom * 0.72 + span / 2 && std::abs(z + span / 2 - liveMapZ) < size * zoom * 0.72 + span / 2; };
    auto image = [&](MapAtlas::Region region, double x, double z, double span) {
        if (!region.ready) return;
        draw->AddImageQuad(MapAtlas::texture,point(x,z),point(x + span,z),point(x + span,z + span),point(x,z + span),region.first,{region.last.x,region.first.y},region.last,{region.first.x,region.last.y});
    };
    if (predicted && preferences.miniMode != 1) {
        int span = incoming.value("step",1) * 32;
        for (const auto &tile : miniView.tiles) if (visible(tile.x * span,tile.z * span,span)) image(mapTexture(tile,miniView,false,true,-1),tile.x * span,tile.z * span,span);
    }
    int rendered = 0;
    if (preferences.miniMode != 2 && terrainDimension == dimension) for (auto &entry : terrainTiles) {
        auto &tile = entry.second;
        if (!visible(tile.x * 16,tile.z * 16,16)) continue;
        tile.used = MapAtlas::frame;
        image(MapAtlas::get(tile.key << 12,[&](auto &pixels) { pixels = tile.pixels; }),tile.x * 16,tile.z * 16,16); ++rendered;
    }
    if (predicted && preferences.miniStructures && incoming.value("structuresVisible",false)) {
        int count = 0;
        if (incoming.contains("markers")) for (const auto &marker : incoming["markers"]) {
            if (++count > 256) break;
            auto p = point(marker.value("x",0),marker.value("z",0));
            if (p.x < start.x + 6 * scale || p.x > end.x - 6 * scale || p.y < start.y + 6 * scale || p.y > end.y - 6 * scale) continue;
            draw->AddCircleFilled(p,4.5f * scale,IM_COL32(13,17,22,255),12); draw->AddCircleFilled(p,2.8f * scale,marker.contains("elytra") && !marker["elytra"].is_null() ? IM_COL32(248,204,116,255) : IM_COL32(238,235,211,255),12);
        }
    }
    if (state.contains("waypoint") && state["waypoint"].value("dimension",dimension) == dimension) {
        auto p = point(state["waypoint"].value("x",0),state["waypoint"].value("z",0));
        draw->AddCircle(p,6 * scale,IM_COL32(247,194,102,255),16,2 * scale);
    }
    double heading = liveMapYaw * 0.0174532925199433;
    auto front = point(liveMapX - std::sin(heading) * zoom * 10 * scale,liveMapZ + std::cos(heading) * zoom * 10 * scale);
    ImVec2 delta{front.x - center.x,front.y - center.y};
    draw->AddCircleFilled(center,7 * scale,IM_COL32(10,14,19,220),16);
    draw->AddTriangleFilled(front,{center.x + delta.y * 0.45f - delta.x * 0.35f,center.y - delta.x * 0.45f - delta.y * 0.35f},{center.x - delta.y * 0.45f - delta.x * 0.35f,center.y + delta.x * 0.45f - delta.y * 0.35f},IM_COL32(237,246,244,255));
    auto north = point(liveMapX,liveMapZ - zoom * (size / 2 - 12 * scale)); draw->AddText({north.x - 4 * scale,north.y - 7 * scale},IM_COL32(246,249,247,255),"N");
    if (!predicted && !rendered) draw->AddText({start.x + 12 * scale,end.y - 24 * scale},IM_COL32(190,199,211,255),preferences.miniMode == 2 ? "Recover or enter a seed" : "Waiting for loaded terrain");
    draw->PopClipRect();
    ImGui::SetCursorScreenPos({start.x,end.y + 7 * scale}); ImGui::Text("%d  /  %d",int(std::floor(liveMapX)),int(std::floor(liveMapZ)));
    char range[32]; std::snprintf(range,sizeof(range),"%d blocks",int(size * zoom));
    ImGui::SameLine(window.x - ImGui::CalcTextSize(range).x - 12 * scale); ImGui::TextDisabled("%s",range);
    ImGui::End(); ImGui::PopStyleColor(); ImGui::PopStyleVar(3);
}

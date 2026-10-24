#pragma once
#include <unordered_map>

struct TerrainTile {
    int x = 0, z = 0;
    uint64_t key = ++MapAtlas::sequence << 1;
    uint64_t used = 0;
    std::array<ImU32,4096> pixels;
    std::array<ImU32,1024> overview;
    std::array<std::vector<ImU32>,4> mipmaps;
};

static std::unordered_map<uint64_t,TerrainTile> terrainTiles;
static long long terrainEpoch = -1;
static std::string terrainDimension;
static MapView miniView;

static void receiveTerrain(const Json &incoming) {
    if (incoming.contains("reset")) {
        terrainEpoch = incoming.value("reset",-1LL); terrainDimension = incoming.value("dimension",""); terrainTiles.clear(); return;
    }
    if (incoming.value("epoch",-2LL) != terrainEpoch || incoming.value("dimension","") != terrainDimension) return;
    TerrainTile tile; tile.x = incoming.value("x",0); tile.z = incoming.value("z",0); tile.used = MapAtlas::frame;
    if (incoming.contains("pixels")) {
        auto encoded = incoming.value("pixels","");
        if (incoming.value("resolution",0) != 64 || encoded.size() != 16384) return;
        auto value = [](unsigned char c) { if (c >= 'A' && c <= 'Z') return int(c - 'A'); if (c >= 'a' && c <= 'z') return int(c - 'a' + 26); if (c >= '0' && c <= '9') return int(c - '0' + 52); return c == '+' ? 62 : c == '/' ? 63 : -1; };
        for (int i = 0; i < 4096; ++i) {
            int a = value(encoded[i * 4]), b = value(encoded[i * 4 + 1]), c = value(encoded[i * 4 + 2]), d = value(encoded[i * 4 + 3]);
            if ((a | b | c | d) < 0) return;
            tile.pixels[i] = IM_COL32((a << 2) | (b >> 4),((b & 15) << 4) | (c >> 2),((c & 3) << 6) | d,255);
        }
    } else {
        if (!incoming.contains("runs")) return;
        int offset = 0;
        const auto &runs = incoming["runs"];
        for (size_t i = 0; i + 1 < runs.size(); i += 2) {
            int count = runs[i].get<int>(), rgb = runs[i + 1].get<int>();
            if (count <= 0 || count > 256 - offset) return;
            ImU32 color = IM_COL32((rgb >> 16) & 255,(rgb >> 8) & 255,rgb & 255,255);
            for (int j = 0; j < count; ++j, ++offset) for (int row = 0; row < 4; ++row) for (int col = 0; col < 4; ++col) tile.pixels[(offset / 16 * 4 + row) * 64 + offset % 16 * 4 + col] = color;
        }
        if (offset != 256) return;
    }
    for (int row = 0; row < 32; ++row) for (int col = 0; col < 32; ++col) {
        unsigned red = 0, green = 0, blue = 0;
        for (int dz = 0; dz < 2; ++dz) for (int dx = 0; dx < 2; ++dx) { auto c = tile.pixels[(row * 2 + dz) * 64 + col * 2 + dx]; red += c & 255; green += c >> 8 & 255; blue += c >> 16 & 255; }
        tile.overview[row * 32 + col] = IM_COL32(red / 4,green / 4,blue / 4,255);
    }
    for (int level = 0; level < 4; ++level) {
        int side = 16 >> level, previousSide = side * 2;
        tile.mipmaps[level].resize(side * side);
        const ImU32 *previous = level == 0 ? tile.overview.data() : tile.mipmaps[level - 1].data();
        for (int row = 0; row < side; ++row) for (int col = 0; col < side; ++col) {
            unsigned red = 0, green = 0, blue = 0;
            for (int dz = 0; dz < 2; ++dz) for (int dx = 0; dx < 2; ++dx) { auto color = previous[(row * 2 + dz) * previousSide + col * 2 + dx]; red += color & 255; green += color >> 8 & 255; blue += color >> 16 & 255; }
            tile.mipmaps[level][row * side + col] = IM_COL32(red / 4,green / 4,blue / 4,255);
        }
    }
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
    ImGui::Checkbox("Structure markers##mini",&preferences.miniStructures);
    std::string dimension = state.value("dimension","overworld");
    const char *overworld[] = {"Villages","Ancient cities","Desert temples","Strongholds"};
    const char *nether[] = {"Fortresses","Bastions","Ruined portals","Nether fossils"};
    const char *end[] = {"All End cities","Cities with elytra","Cities without elytra"};
    if (dimension == "the_end" && preferences.miniTarget > 2) preferences.miniTarget = 0;
    ImGui::SetNextItemWidth(260 * uiScale); ImGui::Combo("Minimap structures",&preferences.miniTarget,dimension == "the_nether" ? nether : dimension == "the_end" ? end : overworld,dimension == "the_end" ? 3 : 4);
    ImGui::TextWrapped("Terrain shows the chunks you have loaded. Seed biomes fill unexplored areas after recovery. In the Nether, the map follows your height.");
}

static void minimapCornerMask(ImDrawList *draw, ImVec2 start, ImVec2 end, float radius, ImU32 color) {
    const float pi = 3.14159265358979323846f;
    const ImVec2 corners[] = {start,{end.x,start.y},end,{start.x,end.y}};
    const ImVec2 centers[] = {{start.x + radius,start.y + radius},{end.x - radius,start.y + radius},{end.x - radius,end.y - radius},{start.x + radius,end.y - radius}};
    for (int corner = 0; corner < 4; ++corner) for (int segment = 0; segment < 12; ++segment) {
        float a = pi + corner * pi / 2 + segment * pi / 24, b = a + pi / 24;
        draw->AddTriangleFilled(corners[corner],{centers[corner].x + std::cos(a) * radius,centers[corner].y + std::sin(a) * radius},{centers[corner].x + std::cos(b) * radius,centers[corner].y + std::sin(b) * radius},color);
    }
}

static void minimapStructure(ImDrawList *draw, ImVec2 p, const std::string &name, bool ship, float scale) {
    float r = 6 * scale;
    ImU32 ink = IM_COL32(238,242,240,255), base = IM_COL32(12,16,20,235);
    if (ship) ink = IM_COL32(238,196,115,255);
    draw->AddCircleFilled({p.x,p.y + scale},r + 2 * scale,IM_COL32(0,0,0,75),16);
    draw->AddCircleFilled(p,r + scale,base,16);
    if (ship) {
        draw->AddTriangleFilled({p.x - r * 0.8f,p.y - r * 0.4f},{p.x,p.y + r * 0.5f},{p.x - r * 0.1f,p.y - r * 0.2f},ink);
        draw->AddTriangleFilled({p.x + r * 0.8f,p.y - r * 0.4f},{p.x,p.y + r * 0.5f},{p.x + r * 0.1f,p.y - r * 0.2f},ink);
    } else if (name.find("village") != std::string::npos) {
        draw->AddTriangleFilled({p.x - r * 0.7f,p.y - r * 0.05f},{p.x,p.y - r * 0.65f},{p.x + r * 0.7f,p.y - r * 0.05f},ink);
        draw->AddRectFilled({p.x - r * 0.46f,p.y - r * 0.12f},{p.x + r * 0.46f,p.y + r * 0.58f},ink);
        draw->AddRectFilled({p.x - r * 0.13f,p.y + r * 0.12f},{p.x + r * 0.13f,p.y + r * 0.58f},base);
    } else if (name.find("stronghold") != std::string::npos) {
        draw->AddCircle(p,r * 0.53f,ink,12,1.5f * scale);
        draw->AddLine({p.x - r * 0.7f,p.y + r * 0.65f},{p.x + r * 0.7f,p.y + r * 0.65f},ink,scale);
    } else {
        draw->AddQuadFilled({p.x,p.y - r * 0.65f},{p.x + r * 0.55f,p.y},{p.x,p.y + r * 0.65f},{p.x - r * 0.55f,p.y},ink);
        draw->AddCircleFilled(p,1.2f * scale,base,8);
    }
}

static void drawMinimap(int width, int height) {
    if (!preferences.miniEnabled || !state.value("connected",false)) return;
    float scale = uiScale;
    float size = std::clamp(preferences.miniSize * scale,96.0f,std::max(96.0f,std::min(width - 32.0f,height - 100.0f)));
    ImVec2 window{size + 24 * scale,size + 74 * scale};
    float margin = 16 * scale;
    ImVec2 origin{preferences.miniAnchor % 2 ? width - window.x - margin : margin,preferences.miniAnchor >= 2 ? height - window.y - margin : margin};
    ImGui::SetNextWindowPos(origin); ImGui::SetNextWindowSize(window);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,{12 * scale,12 * scale});
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding,11 * scale);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize,0);
    ImGui::PushStyleColor(ImGuiCol_WindowBg,{0.039f,0.043f,0.055f,0.96f});
    ImGui::Begin("Seedy minimap",nullptr,ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoNav);
    ImVec2 start = ImGui::GetCursorScreenPos(), end{start.x + size,start.y + size}, center{start.x + size / 2,start.y + size / 2};
    auto draw = ImGui::GetWindowDrawList(); draw->AddRectFilled(start,end,IM_COL32(23,29,34,255),6 * scale); draw->PushClipRect(start,end,true);
    std::string dimension = state.value("dimension","overworld"), seed = state.value("seed","");
    const auto &incoming = miniView.data;
    bool predicted = state.value("gameVersion","") == state.value("generationVersion","") && !seed.empty() && incoming.value("seed","") == seed && incoming.value("dimension","") == dimension;
    double angle = 3.141592653589793 - liveMapYaw * 0.0174532925199433;
    double cosine = std::cos(angle), sine = std::sin(angle), zoom = preferences.miniZoom / scale;
    auto point = [&](double x, double z) { double dx = (x - liveMapX) / zoom, dz = (z - liveMapZ) / zoom; return ImVec2{float(center.x + dx * cosine - dz * sine),float(center.y + dx * sine + dz * cosine)}; };
    auto visible = [&](double x, double z, double span) {
        auto p = point(x + span / 2,z + span / 2);
        double radius = (std::abs(cosine) + std::abs(sine)) * span / (2 * zoom);
        return p.x + radius > start.x && p.x - radius < end.x && p.y + radius > start.y && p.y - radius < end.y;
    };
    ImTextureID texture = MapAtlas::nearestTexture ? MapAtlas::nearestTexture : MapAtlas::texture;
    auto image = [&](MapAtlas::Region region, double x, double z, double span) {
        if (!region.ready) return;
        draw->AddImageQuad(texture,point(x,z),point(x + span,z),point(x + span,z + span),point(x,z + span),region.first,{region.last.x,region.first.y},region.last,{region.first.x,region.last.y});
    };
    if (predicted && preferences.miniMode != 1) {
        int span = incoming.value("step",1) * 32;
        for (const auto &tile : miniView.tiles) if (visible(tile.x * span,tile.z * span,span)) image(mapTexture(tile,miniView,true,false,-1),tile.x * span,tile.z * span,span);
    }
    int rendered = 0;
    bool detailed = scale / preferences.miniZoom > 1.5f && preferences.miniSize * preferences.miniZoom <= 400;
    int overviewSide = std::clamp(int(std::pow(2.0,std::ceil(std::log2(16 / zoom)))),2,32);
    if (preferences.miniMode != 2 && terrainDimension == dimension) for (auto &entry : terrainTiles) {
        auto &tile = entry.second;
        if (!visible(tile.x * 16,tile.z * 16,16)) continue;
        tile.used = MapAtlas::frame;
        if (detailed) for (int part = 0; part < 4; ++part) {
            auto region = MapAtlas::get((tile.key << 12) | (512 + part),[&](auto &pixels) {
                int x = part % 2 * 32, z = part / 2 * 32;
                for (int row = 0; row < 32; ++row) std::copy_n(tile.pixels.begin() + (z + row) * 64 + x,32,pixels.begin() + row * 32);
            });
            image(region,tile.x * 16 + part % 2 * 8,tile.z * 16 + part / 2 * 8,8);
        } else {
            auto region = MapAtlas::get((tile.key << 12) | (516 + overviewSide),[&](auto &pixels) {
                const ImU32 *source = overviewSide == 32 ? tile.overview.data() : tile.mipmaps[overviewSide == 16 ? 0 : overviewSide == 8 ? 1 : overviewSide == 4 ? 2 : 3].data();
                for (int row = 0; row < 32; ++row) for (int col = 0; col < 32; ++col) pixels[row * 32 + col] = source[std::min(row,overviewSide - 1) * overviewSide + std::min(col,overviewSide - 1)];
            });
            region.last = {region.first.x + overviewSide / float(MapAtlas::side),region.first.y + overviewSide / float(MapAtlas::side)};
            image(region,tile.x * 16,tile.z * 16,16);
        }
        ++rendered;
    }
    if (predicted && preferences.miniStructures && incoming.value("structuresVisible",false) && incoming.contains("markers")) {
        int count = 0;
        for (const auto &marker : incoming["markers"]) {
            if (++count > 256) break;
            auto p = point(marker.value("x",0),marker.value("z",0));
            if (p.x < start.x + 9 * scale || p.x > end.x - 9 * scale || p.y < start.y + 9 * scale || p.y > end.y - 9 * scale) continue;
            minimapStructure(draw,p,marker.value("name",""),marker.contains("elytra") && !marker["elytra"].is_null(),scale);
        }
    }
    if (state.contains("waypoint") && state["waypoint"].value("dimension",dimension) == dimension) {
        auto p = point(state["waypoint"].value("x",0),state["waypoint"].value("z",0));
        draw->AddCircleFilled(p,4 * scale,IM_COL32(239,193,112,255),12);
        draw->AddCircle(p,7 * scale,IM_COL32(239,193,112,190),16,1.5f * scale);
    }
    if (!predicted && !rendered) draw->AddText({start.x + 10 * scale,start.y + 10 * scale},IM_COL32(187,198,204,255),preferences.miniMode == 2 ? "Seed unavailable" : "Loading terrain");
    auto font = ImGui::GetFont();
    float labelSize = ImGui::GetFontSize();
    const char *cardinals[] = {"N","E","S","W"};
    const ImVec2 vectors[] = {{0,-1},{1,0},{0,1},{-1,0}};
    for (int i = 0; i < 4; ++i) {
        auto p = point(liveMapX + vectors[i].x * zoom * (size / 2 - 13 * scale),liveMapZ + vectors[i].y * zoom * (size / 2 - 13 * scale));
        auto text = font->CalcTextSizeA(labelSize,FLT_MAX,0,cardinals[i]);
        draw->AddRectFilled({p.x - text.x / 2 - 4 * scale,p.y - text.y / 2 - 2 * scale},{p.x + text.x / 2 + 4 * scale,p.y + text.y / 2 + 2 * scale},IM_COL32(10,15,18,185),3 * scale);
        draw->AddText(font,labelSize,{p.x - text.x / 2,p.y - text.y / 2},i == 0 ? IM_COL32(214,232,221,255) : IM_COL32(200,209,213,235),cardinals[i]);
    }
    minimapCornerMask(draw,start,end,6 * scale,IM_COL32(10,11,14,255));
    draw->PopClipRect();
    int distance = std::max(1,int(std::pow(2.0,std::floor(std::log2(size * zoom / 8)))));
    float length = float(distance / zoom);
    ImVec2 bar{start.x,end.y + 8 * scale};
    char range[32]; std::snprintf(range,sizeof(range),"%d m",distance);
    draw->AddText(font,9 * scale,{bar.x + length + 5 * scale,bar.y - 4.5f * scale},IM_COL32(144,158,165,255),range);
    draw->AddLine(bar,{bar.x + length,bar.y},IM_COL32(144,158,165,255),scale);
    draw->AddLine({bar.x,bar.y - 2 * scale},{bar.x,bar.y + scale},IM_COL32(144,158,165,255),scale);
    draw->AddLine({bar.x + length,bar.y - 2 * scale},{bar.x + length,bar.y + scale},IM_COL32(144,158,165,255),scale);
    char coordinates[80], xCoordinate[40], zCoordinate[40], altitude[24];
    std::snprintf(coordinates,sizeof(coordinates),"X %d   Z %d",int(std::floor(liveMapX)),int(std::floor(liveMapZ)));
    std::snprintf(xCoordinate,sizeof(xCoordinate),"X %d",int(std::floor(liveMapX)));
    std::snprintf(zCoordinate,sizeof(zCoordinate),"Z %d",int(std::floor(liveMapZ)));
    std::snprintf(altitude,sizeof(altitude),"Y %d",state.value("y",64));
    float coordinateSize = 12 * scale;
    auto textWidth = [&](const char *text, float fontSize) { return font->CalcTextSizeA(fontSize,FLT_MAX,0,text).x; };
    bool splitCoordinates = textWidth(coordinates,coordinateSize) + textWidth(altitude,coordinateSize) + 14 * scale > size;
    std::string biome = hudLabel(state.value("biome",""));
    const char *dimensionName = dimension == "the_nether" ? "Nether" : dimension == "the_end" ? "End" : "Overworld";
    float dimensionWidth = textWidth(dimensionName,11 * scale);
    bool showDimension = size >= 190 * scale;
    float available = size - (showDimension ? dimensionWidth + 14 * scale : 0);
    if (textWidth(biome.c_str(),14 * scale) > available) {
        while (biome.size() > 1 && textWidth((biome + "...").c_str(),14 * scale) > available) biome.pop_back();
        biome += "...";
    }
    draw->AddText(font,14 * scale,{start.x,end.y + 21 * scale},IM_COL32(220,227,225,255),biome.c_str());
    if (showDimension) draw->AddText(font,11 * scale,{end.x - dimensionWidth,end.y + 23 * scale},IM_COL32(132,146,154,255),dimensionName);
    draw->AddText(font,coordinateSize,{start.x,end.y + 41 * scale},IM_COL32(160,174,180,255),splitCoordinates ? xCoordinate : coordinates);
    const char *rightCoordinate = splitCoordinates ? zCoordinate : altitude;
    if (textWidth(splitCoordinates ? xCoordinate : coordinates,coordinateSize) + textWidth(rightCoordinate,coordinateSize) + 10 * scale <= size)
        draw->AddText(font,coordinateSize,{end.x - textWidth(rightCoordinate,coordinateSize),end.y + 41 * scale},IM_COL32(142,157,165,255),rightCoordinate);
    else draw->AddText(font,coordinateSize,{start.x,end.y + 54 * scale},IM_COL32(142,157,165,255),rightCoordinate);
    ImGui::End(); ImGui::PopStyleColor(); ImGui::PopStyleVar(3);
}

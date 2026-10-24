#include <array>

struct MapTile {
    int x, z;
    uint64_t atlasKey = (++MapAtlas::sequence << 1) | 1;
    long long version = 0;
    std::vector<int> counts, anchors;
    std::vector<int> cells = std::vector<int>(1024);
    std::vector<unsigned char> shade = std::vector<unsigned char>(1024,128);
};

struct MapView {
    double x = 0, z = 0, blocksPerPixel = 4;
    int dimension = 0, target = 0, y = 64, cityFilter = 0;
    bool structures = true, initialized = false;
    double changedAt = 0;
    long long requestId = 0, revision = -1;
    std::string seed, desiredKey, sentKey;
    Json data = Json::object(), selected = Json::object();
    std::vector<MapTile> tiles;
    std::vector<ImU32> colors;
    std::vector<std::string> names;
    std::vector<bool> water;
    std::vector<double> areas;
    int highlight = -1;
    bool followPlayer = false;
};

static MapView mapView;
static std::array<Json,3> mapChannels = {Json::object(),Json::object(),Json::object()};
static double liveMapX = 0, liveMapZ = 0;
static float liveMapYaw = 0;

static void mapZoom(double factor, ImVec2 anchor, ImVec2 center) {
    double before = mapView.blocksPerPixel;
    mapView.blocksPerPixel = std::clamp(before * factor, 0.125, 1024.0);
    mapView.x += (anchor.x - center.x) * (before - mapView.blocksPerPixel);
    mapView.z += (anchor.y - center.y) * (before - mapView.blocksPerPixel);
}

static void mapDecode(MapView &mapView, const Json &incoming) {
    mapView.revision = incoming.value("revision", -1LL);
    auto previousTiles = std::move(mapView.tiles);
    bool partial = incoming.value("partial",false);
    bool sameWorld = mapView.data.value("seed","") == incoming.value("seed","") && mapView.data.value("dimension","") == incoming.value("dimension","") && mapView.data.value("y",64) == incoming.value("y",64) && mapView.data.value("step",1) == incoming.value("step",1);
    bool compatible = sameWorld && mapView.data.value("palette",Json::array()) == incoming.value("palette",Json::array());
    if (!compatible) previousTiles.clear();
    mapView.tiles.clear();
    if (!compatible) {
        mapView.colors.clear(); mapView.names.clear(); mapView.water.clear();
        for (const auto &swatch : incoming.value("palette", Json::array())) {
            int rgb = swatch.value("color", 0);
            mapView.colors.push_back(IM_COL32((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255));
            mapView.names.push_back(label(swatch.value("name", "")));
            auto name = swatch.value("name",""); mapView.water.push_back(name.find("ocean") != std::string::npos || name.find("river") != std::string::npos);
        }
    }
    for (const auto &tile : incoming.value("tiles", Json::array())) {
        int x = tile.value("x",0), z = tile.value("z",0);
        long long version = tile.value("version",0LL);
        auto previous = std::find_if(previousTiles.begin(),previousTiles.end(),[&](const MapTile &entry) { return compatible && version && entry.x == x && entry.z == z && entry.version == version; });
        if (previous != previousTiles.end()) { mapView.tiles.push_back(std::move(*previous)); previous->version = -1; continue; }
        for (auto &entry : previousTiles) if (entry.x == x && entry.z == z) entry.version = -1;
        MapTile decoded{};
        decoded.x = x; decoded.z = z; decoded.version = version;
        if (version > 0) decoded.atlasKey = (uint64_t(version) << 1) | 1;
        decoded.counts.assign(mapView.colors.size(),0); decoded.anchors.assign(mapView.colors.size(),-1);
        auto heights = tile.value("heights",std::vector<int>{});
        if (heights.size() == 49) {
            std::array<ImVec2,25> gradients;
            float spacing = incoming.value("step",4) * 16.0f;
            for (int row = 0; row < 5; row++) for (int col = 0; col < 5; col++) {
                int center = (row + 1) * 7 + col + 1;
                gradients[row * 5 + col] = {(heights[center + 1] - heights[center - 1]) / spacing,(heights[center + 7] - heights[center - 7]) / spacing};
            }
            for (int row = 0; row < 32; row++) for (int col = 0; col < 32; col++) {
                int gx = col / 8, gz = row / 8;
                float dx = (col % 8 + 0.5f) / 8, dz = (row % 8 + 0.5f) / 8;
                auto a = gradients[gz * 5 + gx], b = gradients[gz * 5 + gx + 1], c = gradients[(gz + 1) * 5 + gx], d = gradients[(gz + 1) * 5 + gx + 1];
                float gradientX = (a.x * (1 - dx) + b.x * dx) * (1 - dz) + (c.x * (1 - dx) + d.x * dx) * dz;
                float gradientZ = (a.y * (1 - dx) + b.y * dx) * (1 - dz) + (c.y * (1 - dx) + d.y * dx) * dz;
                float light = (0.85f - 0.4f * gradientX - 0.32f * gradientZ) / std::sqrt(1 + gradientX * gradientX + gradientZ * gradientZ);
                decoded.shade[row * 32 + col] = static_cast<unsigned char>(std::clamp(1 + (light - 0.85f) * 0.65f,0.68f,1.22f) * 128);
            }
        }
        int offset = 0;
        const auto &runs = tile["runs"];
        for (size_t i = 0; i + 1 < runs.size(); i += 2) {
            int count = runs[i].get<int>(), value = runs[i+1].get<int>();
            if (count <= 0 || count > 1024 - offset || value < 0 || value >= static_cast<int>(mapView.colors.size())) break;
            std::fill_n(decoded.cells.begin() + offset, count, value);
            offset += count;
        }
        if (offset == 1024) {
            for (int index = 0; index < 1024; ++index) { int value = decoded.cells[index]; decoded.counts[value]++; if (decoded.anchors[value] < 0) decoded.anchors[value] = index; }
            mapView.tiles.push_back(std::move(decoded));
        }
    }
    if (partial && compatible) for (auto &tile : previousTiles) {
        int span = incoming.value("step",1) * 32;
        if (tile.version < 0 || tile.x * span > incoming.value("maxX",0) || tile.z * span > incoming.value("maxZ",0) || (tile.x + 1) * span <= incoming.value("minX",0) || (tile.z + 1) * span <= incoming.value("minZ",0)) continue;
        mapView.tiles.push_back(std::move(tile));
    }
    Json markers = sameWorld && mapView.data.value("id",-1LL) == incoming.value("id",-2LL) ? mapView.data.value("markers",Json::array()) : Json::array();
    mapView.data = incoming;
    mapView.data.erase("tiles");
    if (!incoming.contains("markers")) mapView.data["markers"] = std::move(markers);
}

static void mapReceive(const std::string &dimension) {
    if (mapChannels[0].empty() && !state.contains("map")) return;
    const auto &incoming = mapChannels[0].empty() ? state["map"] : mapChannels[0];
    if (incoming.value("revision",-1LL) == mapView.revision || incoming.value("id",-1LL) != mapView.requestId || incoming.value("seed","") != mapView.seed || incoming.value("dimension","") != dimension || incoming.value("y",64) != mapView.y) return;
    mapDecode(mapView,incoming);
}

static MapAtlas::Region mapTexture(const MapTile &tile, const MapView &view, bool relief, bool coastlines, int highlight) {
    uint64_t variant = static_cast<uint64_t>((highlight + 1) * 4 + (relief ? 1 : 0) + (coastlines ? 2 : 0));
    return MapAtlas::get((tile.atlasKey << 12) | variant,[&](auto &pixels) {
        for (int i = 0; i < 1024; ++i) {
            int value = tile.cells[i]; ImU32 color = view.colors[value];
            float factor = (relief ? tile.shade[i] / 128.0f : 1.0f) * (highlight >= 0 && value != highlight ? 0.35f : 1.0f);
            if (coastlines && ((i % 32 < 31 && view.water[value] != view.water[tile.cells[i + 1]]) || (i < 992 && view.water[value] != view.water[tile.cells[i + 32]]))) factor *= 0.78f;
            pixels[i] = IM_COL32(std::min(255,int((color & 255) * factor)),std::min(255,int(((color >> 8) & 255) * factor)),std::min(255,int(((color >> 16) & 255) * factor)),255);
        }
    });
}

static void mapSection(const char *name) {
    ImGui::Dummy({0,5 * uiScale});
    ImGui::TextDisabled("%s",name);
}

static void mapMarker(ImDrawList *draw, ImVec2 position, float radius, const std::string &name, bool ship, bool selected) {
    float r = std::max(6.0f * uiScale,radius);
    ImU32 ink = IM_COL32(30,52,58,255), paper = ship ? IM_COL32(246,210,134,255) : IM_COL32(239,240,219,255);
    if (selected) draw->AddCircle(position,r + 6 * uiScale,IM_COL32(220,246,238,255),24,2 * uiScale);
    draw->AddCircleFilled({position.x,position.y + 2 * uiScale},r + 3 * uiScale,IM_COL32(15,30,34,80),20);
    draw->AddCircleFilled(position,r + 2 * uiScale,ink,20);
    draw->AddCircleFilled(position,r,paper,20);
    if (name.find("village") != std::string::npos) {
        draw->AddTriangleFilled({position.x - r * 0.65f,position.y},{position.x,position.y - r * 0.65f},{position.x + r * 0.65f,position.y},ink);
        draw->AddRectFilled({position.x - r * 0.4f,position.y - r * 0.05f},{position.x + r * 0.4f,position.y + r * 0.55f},ink);
    } else if (ship) {
        draw->AddTriangleFilled({position.x - r * 0.75f,position.y - r * 0.35f},{position.x,position.y + r * 0.55f},{position.x - r * 0.1f,position.y - r * 0.5f},ink);
        draw->AddTriangleFilled({position.x + r * 0.75f,position.y - r * 0.35f},{position.x,position.y + r * 0.55f},{position.x + r * 0.1f,position.y - r * 0.5f},ink);
    } else {
        draw->AddQuadFilled({position.x,position.y - r * 0.65f},{position.x + r * 0.5f,position.y},{position.x,position.y + r * 0.65f},{position.x - r * 0.5f,position.y},ink);
    }
}

static void mapPanel(Json &command) {
    const char *dimensions[] = {"overworld", "the_nether", "the_end"};
    const char *dimensionNames[] = {"Overworld", "Nether", "End"};
    auto &view = mapView;
    std::string seed = state.value("seed", "");
    if (seed.empty()) {
        view = MapView{};
        ImGui::TextWrapped("Enter or recover a seed to explore its map.");
        if (ImGui::Button("Enter seed")) activeSection = 2;
        return;
    }
    if (!view.initialized || view.seed != seed) {
        view = MapView{};
        view.initialized = true; view.seed = seed;
        std::string currentDimension = state.value("dimension", "overworld");
        for (int i = 0; i < 3; i++) if (currentDimension == dimensions[i]) view.dimension = i;
        view.x = state.value("x", 0); view.z = state.value("z", 0);
    }
    if (ImGui::Button(preferences.controls ? "Hide controls" : "Show controls")) preferences.controls = !preferences.controls;
    ImGui::SameLine();
    if (ImGui::Button("-##map-zoom")) mapZoom(2, {0,0}, {0,0});
    ImGui::SameLine();
    if (ImGui::Button("+##map-zoom")) mapZoom(0.5, {0,0}, {0,0});
    ImGui::SameLine();
    float zoomLevel = static_cast<float>(10 - std::log2(view.blocksPerPixel));
    ImGui::SetNextItemWidth(std::max(100 * uiScale,ImGui::GetContentRegionAvail().x - 174 * uiScale));
    if (ImGui::SliderFloat("##map-zoom-level", &zoomLevel, 0, 13, "Zoom %.1f")) view.blocksPerPixel = std::pow(2.0,10 - zoomLevel);
    ImGui::SameLine();
    ImGui::SetNextItemWidth(156 * uiScale);
    const char *presetNames[] = {"Overview", "Region", "Local", "Biome detail", "Chunks", "Close-up"};
    const double presetScales[] = {256,64,16,4,1,0.125};
    if (ImGui::BeginCombo("##map-zoom-presets", "Zoom presets")) {
        for (int i = 0; i < 6; i++) if (ImGui::Selectable(presetNames[i],view.blocksPerPixel == presetScales[i])) view.blocksPerPixel = presetScales[i];
        ImGui::EndCombo();
    }
    float availableWidth = ImGui::GetContentRegionAvail().x;
    bool sideBySide = availableWidth >= 680 * uiScale;
    float sidebarWidth = std::clamp(preferences.sidebarWidth * uiScale,180 * uiScale,std::max(180 * uiScale,availableWidth - 340 * uiScale));
    if (preferences.controls) {
        ImGui::PushStyleColor(ImGuiCol_ChildBg,{0.065f,0.065f,0.072f,0.94f});
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,{12 * uiScale,12 * uiScale});
        ImGui::BeginChild("map-controls",{sideBySide ? sidebarWidth : 0,sideBySide ? 0 : std::min(280 * uiScale,ImGui::GetContentRegionAvail().y * 0.45f)},ImGuiChildFlags_AlwaysUseWindowPadding);
        ImGui::PopStyleVar();
        ImGui::PopStyleColor();
    }
    bool dimensionChanged = false;
    if (preferences.controls) {
        ImGui::TextUnformatted("Explore");
        mapSection("Dimension");
        ImGui::SetNextItemWidth(-1);
        dimensionChanged = ImGui::Combo("##map-dimension", &view.dimension, dimensionNames, 3);
    }
    std::string dimension = dimensions[view.dimension];
    if (dimensionChanged) {
        view.target = 0; view.cityFilter = 0; view.highlight = -1; view.areas.clear(); view.selected = Json::object(); view.data = Json::object(); view.tiles.clear(); view.sentKey.clear(); view.followPlayer = false;
        view.x = 0; view.z = 0; view.y = 64;
    }
    if (preferences.controls) {
    mapSection("Biome height");
    ImGui::SetNextItemWidth(64 * uiScale);
    bool heightChanged = ImGui::InputInt("Y##map", &view.y, 0, 0);
    view.y = std::clamp(view.y, -64, 320);
    if (heightChanged) { view.tiles.clear(); view.data = Json::object(); }
    if (ImGui::IsItemHovered()) ImGui::SetTooltip("Biome slice height. Try Y -40 for underground biomes.");
    if (ImGui::SmallButton("Surface")) { view.y = 64; view.tiles.clear(); view.data = Json::object(); }
    ImGui::SameLine();
    if (ImGui::SmallButton("Caves")) { view.y = -40; view.tiles.clear(); view.data = Json::object(); }
    ImGui::BeginDisabled(!state.value("connected", false) || state.value("dimension", "overworld") != dimension);
    if (ImGui::Button("Player##map")) { view.x = state.value("x", 0); view.z = state.value("z", 0); }
    ImGui::SameLine(); ImGui::Checkbox("Follow", &view.followPlayer);
    ImGui::EndDisabled();
    }
    bool layerChanged = false;
    if (preferences.controls) { mapSection("Structure layer"); layerChanged = ImGui::Checkbox("Structures##map", &view.structures); }
    auto catalogs = state.value("catalogs", Json::object());
    auto catalog = catalogs.value(dimension, Json::object());
    auto targets = catalog.value("structures", std::vector<std::string>{});
    view.target = std::clamp(view.target,0,std::max(0,static_cast<int>(targets.size()) - 1));
    std::string target = targets.empty() ? "" : targets[view.target];
    if (preferences.controls) {
    ImGui::BeginDisabled(!view.structures);
    ImGui::SetNextItemWidth(std::max(120.0f, ImGui::GetContentRegionAvail().x));
    int previousTarget = view.target;
    target = selectValues("##map-layer", targets, view.target);
    layerChanged |= previousTarget != view.target;
    ImGui::EndDisabled();
    if (target == "minecraft:end_cities" && view.structures) {
        const char *filters[] = {"All End cities", "With elytra ship", "Without elytra ship"};
        ImGui::SetNextItemWidth(-1);
        layerChanged |= ImGui::Combo("##map-ships", &view.cityFilter, filters, 3);
    }
    }
    if (target != "minecraft:end_cities" || !view.structures) view.cityFilter = 0;
    if (layerChanged) { view.selected = Json::object(); view.data["markers"] = Json::array(); }
    const char *cityFilters[] = {"any", "with_elytra", "without_elytra"};
    std::string cityFilter = cityFilters[view.cityFilter];
    if (preferences.controls) {
    mapSection("Go to coordinates");
    int center[2] = {static_cast<int>(std::round(view.x)), static_cast<int>(std::round(view.z))};
    ImGui::SetNextItemWidth(std::max(130.0f, ImGui::GetContentRegionAvail().x - 60 * uiScale));
    if (ImGui::InputInt2("X / Z##map", center, ImGuiInputTextFlags_EnterReturnsTrue)) { view.x = center[0]; view.z = center[1]; view.followPlayer = false; }
    if (ImGui::CollapsingHeader("Display")) mapAppearanceSettings();
    if (ImGui::CollapsingHeader("Biome legend",ImGuiTreeNodeFlags_DefaultOpen)) {
        if (view.highlight >= 0 && ImGui::SmallButton("Clear highlight")) view.highlight = -1;
        for (size_t i = 0; i < view.areas.size() && i < view.names.size(); i++) if (view.areas[i] > 0) {
            ImGui::PushID(static_cast<int>(i));
            ImVec2 at = ImGui::GetCursorScreenPos();
            ImGui::GetWindowDrawList()->AddRectFilled({at.x,at.y + 3 * uiScale},{at.x + 12 * uiScale,at.y + 15 * uiScale},view.colors[i],3 * uiScale);
            ImGui::Dummy({16 * uiScale,18 * uiScale}); ImGui::SameLine();
            if (ImGui::Selectable(view.names[i].c_str(),view.highlight == static_cast<int>(i))) view.highlight = view.highlight == static_cast<int>(i) ? -1 : static_cast<int>(i);
            ImGui::PopID();
        }
        if (view.areas.empty()) ImGui::TextDisabled("Visible biomes appear here.");
    }
    ImGui::Dummy({0,6 * uiScale});
    ImGui::TextDisabled("About this map");
    if (ImGui::IsItemHovered()) ImGui::SetTooltip("Colors use Minecraft's block-level biome blending at the selected height. Terrain shading uses predicted ground height. Close-up views sample individual blocks.");
    ImGui::EndChild();
    if (sideBySide) {
        ImGui::SameLine(0,4 * uiScale);
        ImGui::InvisibleButton("Resize map controls",{6 * uiScale,ImGui::GetContentRegionAvail().y});
        if (ImGui::IsItemHovered() || ImGui::IsItemActive()) {
            ImGui::SetMouseCursor(ImGuiMouseCursor_ResizeEW);
            auto a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
            ImGui::GetWindowDrawList()->AddRectFilled({a.x + 2 * uiScale,a.y},{b.x - 2 * uiScale,b.y},IM_COL32(145,145,155,160),uiScale);
        }
        if (ImGui::IsItemActive()) preferences.sidebarWidth = std::clamp(preferences.sidebarWidth + ImGui::GetIO().MouseDelta.x / uiScale,180.0f,400.0f);
        ImGui::SameLine(0,4 * uiScale);
    }
    }
    ImGui::BeginChild("map-viewport",{0,0},ImGuiChildFlags_None,ImGuiWindowFlags_NoScrollWithMouse);
    if (view.followPlayer && state.value("connected",false) && state.value("dimension", "overworld") == dimension) { view.x = state.value("x",0); view.z = state.value("z",0); }
    ImGui::TextUnformatted(dimensionNames[view.dimension]);
    ImGui::SameLine(); ImGui::TextDisabled("   Y %d   /   %.3g blocks per pixel",view.y,view.blocksPerPixel);

    ImVec2 size{ImGui::GetContentRegionAvail().x, std::max(100 * uiScale, ImGui::GetContentRegionAvail().y - 126 * uiScale)};
    ImVec2 start = ImGui::GetCursorScreenPos(), end{start.x + size.x, start.y + size.y}, middle{start.x + size.x / 2, start.y + size.y / 2};
    ImGui::InvisibleButton("Seed map canvas", size, ImGuiButtonFlags_MouseButtonLeft | ImGuiButtonFlags_MouseButtonRight);
    ImGui::SetItemKeyOwner(ImGuiKey_MouseWheelY);
    bool hovered = ImGui::IsItemHovered(), active = ImGui::IsItemActive();
    auto &io = ImGui::GetIO();
    if (hovered && io.MouseWheel != 0) { view.followPlayer = false; mapZoom(std::pow(2.0, -io.MouseWheel * preferences.zoomSpeed * (io.KeyShift ? 0.25 : 1)), io.MousePos, middle); }
    if (active && ImGui::IsMouseDragging(ImGuiMouseButton_Left, 3)) {
        view.x -= io.MouseDelta.x * view.blocksPerPixel;
        view.z -= io.MouseDelta.y * view.blocksPerPixel;
        view.followPlayer = false;
        ImGui::SetMouseCursor(ImGuiMouseCursor_ResizeAll);
    }
    double halfWidth = size.x * view.blocksPerPixel / 2, halfHeight = size.y * view.blocksPerPixel / 2;
    view.x = std::clamp(view.x, -29'960'000.0 + halfWidth, 29'960'000.0 - halfWidth);
    view.z = std::clamp(view.z, -29'960'000.0 + halfHeight, 29'960'000.0 - halfHeight);
    int minX = static_cast<int>(std::floor(view.x - halfWidth)), maxX = static_cast<int>(std::ceil(view.x + halfWidth));
    int minZ = static_cast<int>(std::floor(view.z - halfHeight)), maxZ = static_cast<int>(std::ceil(view.z + halfHeight));
    int step = 1;
    const double detailPixels[] = {4,1.5,0.75};
    while (step < view.blocksPerPixel * detailPixels[preferences.detail]) step *= 2;
    auto tileCount = [&](int span) { return (std::floor(static_cast<double>(maxX) / span) - std::floor(static_cast<double>(minX) / span) + 1) * (std::floor(static_cast<double>(maxZ) / span) - std::floor(static_cast<double>(minZ) / span) + 1); };
    const int tileBudgets[] = {64,192,512};
    while (tileCount(step * 32) > tileBudgets[preferences.detail]) step *= 2;
    bool structuresVisible = view.structures && !target.empty();
    Json request = {{"action","map"},{"dimension",dimension},{"y",view.y},{"step",step},{"minX",minX},{"minZ",minZ},{"maxX",maxX},{"maxZ",maxZ},{"target",view.structures ? target : ""},{"cityFilter",cityFilter},{"relief",preferences.relief}};
    Json key = request;
    for (const char *name : {"minX", "minZ", "maxX", "maxZ"}) key[name] = static_cast<int>(std::floor(request[name].get<double>() / (step * 32)));
    key["structureBounds"] = {static_cast<int>(std::floor(minX / 1024.0)),static_cast<int>(std::floor(minZ / 1024.0)),static_cast<int>(std::floor(maxX / 1024.0)),static_cast<int>(std::floor(maxZ / 1024.0))};
    std::string desired = key.dump();
    if (desired != view.desiredKey) { view.desiredKey = desired; view.changedAt = ImGui::GetTime(); }
    if (view.sentKey != desired && ImGui::GetTime() - view.changedAt >= 0.18 && !ImGui::IsMouseDown(ImGuiMouseButton_Left) && command.empty()) {
        view.sentKey = desired;
        request["id"] = ++view.requestId;
        command = request;
    }
    mapReceive(dimension);

    auto point = [&](double x, double z) { return ImVec2{static_cast<float>(middle.x + (x - view.x) / view.blocksPerPixel), static_cast<float>(middle.y + (z - view.z) / view.blocksPerPixel)}; };
    auto draw = ImGui::GetWindowDrawList();
    draw->AddRectFilled(start, end, IM_COL32(36,46,59,255), 6 * uiScale);
    draw->PushClipRect(start, end, true);
    int biome = -1;
    double mouseX = view.x + (io.MousePos.x - middle.x) * view.blocksPerPixel, mouseZ = view.z + (io.MousePos.y - middle.y) * view.blocksPerPixel;
    int drawnStep = view.data.value("step", step);
    view.areas.assign(view.names.size(),0);
    std::vector<ImVec2> biomeAnchors(view.names.size());
    std::vector<float> biomeDistances(view.names.size(),1e30f);
    auto biomeColors = view.colors;
    if (view.highlight >= 0) for (size_t i = 0; i < biomeColors.size(); i++) if (static_cast<int>(i) != view.highlight) {
        auto color = ImGui::ColorConvertU32ToFloat4(biomeColors[i]);
        color.x *= 0.35f; color.y *= 0.35f; color.z *= 0.35f;
        biomeColors[i] = ImGui::ColorConvertFloat4ToU32(color);
    }
    for (const auto &tile : view.tiles) {
        int tileX = tile.x * drawnStep * 32, tileZ = tile.z * drawnStep * 32;
        if (tileX > maxX || tileZ > maxZ || tileX + drawnStep * 32 < minX || tileZ + drawnStep * 32 < minZ) continue;
        if (hovered && mouseX >= tileX && mouseX < tileX + drawnStep * 32 && mouseZ >= tileZ && mouseZ < tileZ + drawnStep * 32) biome = tile.cells[static_cast<int>((mouseZ - tileZ) / drawnStep) * 32 + static_cast<int>((mouseX - tileX) / drawnStep)];
        auto texture = mapTexture(tile,view,preferences.relief,preferences.coastlines,view.highlight);
        if (texture.ready) draw->AddImage(MapAtlas::texture,point(tileX,tileZ),point(tileX + drawnStep * 32,tileZ + drawnStep * 32),texture.first,texture.last);
        ImVec2 a = point(tileX,tileZ), b = point(tileX + drawnStep * 32,tileZ + drawnStep * 32);
        double area = std::max(0.0f,std::min(b.x,end.x) - std::max(a.x,start.x)) * std::max(0.0f,std::min(b.y,end.y) - std::max(a.y,start.y));
        for (size_t value = 0; value < tile.counts.size(); ++value) if (tile.counts[value]) {
            view.areas[value] += area * tile.counts[value] / 1024;
            int index = tile.anchors[value];
            auto anchor = point(tileX + (index % 32 + 0.5) * drawnStep,tileZ + (index / 32 + 0.5) * drawnStep);
            float distance = std::hypot(anchor.x - middle.x,anchor.y - middle.y);
            if (distance < biomeDistances[value]) { biomeDistances[value] = distance; biomeAnchors[value] = anchor; }
        }
    }
    if (preferences.grid != 0) {
        double spacing = preferences.grid == 2 ? 16 : std::pow(2.0,std::ceil(std::log2(100 * uiScale * view.blocksPerPixel)));
        if (spacing / view.blocksPerPixel >= 12 * uiScale) {
            for (double x = std::ceil(minX / spacing) * spacing; x <= maxX; x += spacing) {
                float px = point(x,view.z).x;
                draw->AddLine({px,start.y},{px,end.y},IM_COL32(235,243,247,25));
                if (spacing / view.blocksPerPixel >= 60 * uiScale || static_cast<int>(x) % 64 == 0) {
                    std::string coordinate = std::to_string(static_cast<int>(x));
                    draw->AddText({px + 4 * uiScale,start.y + 5 * uiScale},IM_COL32(246,248,241,210),coordinate.c_str());
                }
            }
            for (double z = std::ceil(minZ / spacing) * spacing; z <= maxZ; z += spacing) {
                float py = point(view.x,z).y;
                draw->AddLine({start.x,py},{end.x,py},IM_COL32(235,243,247,25));
                if (spacing / view.blocksPerPixel >= 60 * uiScale || static_cast<int>(z) % 64 == 0) {
                    std::string coordinate = std::to_string(static_cast<int>(z));
                    draw->AddText({start.x + 5 * uiScale,py + 3 * uiScale},IM_COL32(246,248,241,210),coordinate.c_str());
                }
            }
        }
    }
    std::vector<ImVec4> occupied;
    auto mapLabel = [&](const std::string &text, ImVec2 anchor, ImU32 color, bool biomeLabel = false) {
        auto textSize = ImGui::CalcTextSize(text.c_str());
        ImVec4 box{anchor.x - textSize.x / 2 - 5 * uiScale,anchor.y,anchor.x + textSize.x / 2 + 5 * uiScale,anchor.y + textSize.y + 6 * uiScale};
        if (box.x < start.x + 8 * uiScale || box.y < start.y + 24 * uiScale || box.z > end.x - 8 * uiScale || box.w > end.y - 46 * uiScale) return;
        for (auto prior : occupied) if (box.x < prior.z + 8 * uiScale && box.z > prior.x - 8 * uiScale && box.y < prior.w + 8 * uiScale && box.w > prior.y - 8 * uiScale) return;
        occupied.push_back(box);
        if (!biomeLabel) draw->AddRectFilled({box.x,box.y},{box.z,box.w},IM_COL32(15,15,18,235),5 * uiScale);
        else draw->AddText({box.x + 6 * uiScale,box.y + 4 * uiScale},IM_COL32(20,36,37,230),text.c_str());
        draw->AddText({box.x + 5 * uiScale,box.y + 3 * uiScale},color,text.c_str());
    };
    const Json *hit = nullptr;
    float closest = std::max(12.0f,preferences.markerSize + 5) * uiScale;
    int visibleMarkers = 0;
    bool currentLayer = view.data.value("target", "") == target && view.data.value("cityFilter", "any") == cityFilter;
    if (structuresVisible && currentLayer && view.data.contains("markers")) for (const auto &marker : view.data["markers"]) {
        ImVec2 pos = point(marker.value("x",0), marker.value("z",0));
        if (pos.x < start.x || pos.x > end.x || pos.y < start.y || pos.y > end.y) continue;
        visibleMarkers++;
        bool ship = marker.contains("elytra") && !marker["elytra"].is_null();
        ImU32 color = ship ? IM_COL32(255,216,126,255) : IM_COL32(250,247,230,255);
        bool selected = !view.selected.empty() && view.selected.value("x",0) == marker.value("x",0) && view.selected.value("z",0) == marker.value("z",0);
        mapMarker(draw,pos,preferences.markerSize * uiScale,marker.value("name",""),ship,selected);
        if (preferences.structureLabels && view.blocksPerPixel <= 8) {
            std::string text = label(marker.value("name", ""));
            if (view.blocksPerPixel <= 1) text += "\n" + coordinatesOf(marker);
            mapLabel(text,{pos.x,pos.y + (preferences.markerSize + 5) * uiScale},color);
        }
        float distance = std::hypot(pos.x - io.MousePos.x, pos.y - io.MousePos.y);
        if (hovered && distance < closest) { closest = distance; hit = &marker; }
    }
    if (preferences.biomeLabels) for (size_t i = 0; i < view.areas.size(); i++) if (view.areas[i] > 2600 * uiScale * uiScale && (view.highlight < 0 || view.highlight == static_cast<int>(i))) mapLabel(view.names[i],biomeAnchors[i],IM_COL32(241,247,225,255),true);
    if (preferences.showPlayer && state.value("connected", false) && state.value("dimension", "overworld") == dimension) {
        ImVec2 player = point(state.value("x",0),state.value("z",0));
        draw->AddCircleFilled(player, 8 * uiScale, IM_COL32(38,63,81,230), 20);
        draw->AddCircleFilled(player, 4 * uiScale, IM_COL32(169,224,255,255), 16);
        draw->AddLine({player.x,player.y - 11 * uiScale},{player.x,player.y - 6 * uiScale}, IM_COL32(218,241,255,255), 2 * uiScale);
    }
    if (hovered && ImGui::IsMouseReleased(ImGuiMouseButton_Left) && io.MouseDragMaxDistanceSqr[0] < 9) view.selected = hit ? *hit : Json{{"name",biome >= 0 ? view.names[biome] : "Map position"},{"x",static_cast<int>(std::floor(mouseX))},{"z",static_cast<int>(std::floor(mouseZ))}};
    if (hovered && !ImGui::IsMouseDragging(ImGuiMouseButton_Left, 3)) {
        ImGui::BeginTooltip();
        if (hit) {
            ImGui::TextUnformatted(label(hit->value("name", "")).c_str());
            ImGui::TextDisabled("%s", coordinatesOf(*hit).c_str());
            if (hit->value("name", "") == "minecraft:end_city") ImGui::TextUnformatted(hit->contains("elytra") && !(*hit)["elytra"].is_null() ? "Elytra ship" : "No ship");
        } else {
            ImGui::TextUnformatted(biome >= 0 ? view.names[biome].c_str() : "Loading biomes");
            ImGui::TextDisabled("%d / %d", static_cast<int>(std::floor(mouseX)), static_cast<int>(std::floor(mouseZ)));
        }
        ImGui::EndTooltip();
    }
    double scaleBlocks = std::pow(2.0, std::floor(std::log2(90 * uiScale * view.blocksPerPixel)));
    float scaleWidth = static_cast<float>(scaleBlocks / view.blocksPerPixel);
    ImVec2 scalePos{start.x + 14 * uiScale, end.y - 14 * uiScale};
    draw->AddRectFilled({scalePos.x - 7 * uiScale,scalePos.y - 29 * uiScale},{scalePos.x + std::max(scaleWidth,65 * uiScale) + 7 * uiScale,scalePos.y + 5 * uiScale},IM_COL32(15,15,18,225),4 * uiScale);
    std::string scaleLabel = std::to_string(static_cast<int>(scaleBlocks)) + " blocks";
    draw->AddText({scalePos.x,scalePos.y - 25 * uiScale},IM_COL32(232,238,244,255),scaleLabel.c_str());
    draw->AddLine(scalePos,{scalePos.x + scaleWidth,scalePos.y},IM_COL32(232,238,244,255),2 * uiScale);
    ImVec2 compass{end.x - 26 * uiScale,start.y + 30 * uiScale};
    draw->AddCircleFilled(compass,19 * uiScale,IM_COL32(15,15,18,225),24);
    draw->AddText({compass.x - 5 * uiScale,compass.y - 15 * uiScale},IM_COL32(235,244,228,255),"N");
    draw->AddTriangleFilled({compass.x,compass.y + 1 * uiScale},{compass.x - 5 * uiScale,compass.y + 11 * uiScale},{compass.x + 5 * uiScale,compass.y + 11 * uiScale},IM_COL32(220,220,228,255));
    draw->PopClipRect();

    if (hovered && ImGui::IsMouseReleased(ImGuiMouseButton_Right)) ImGui::OpenPopup("Map position");
    if (ImGui::BeginPopup("Map position")) {
        auto popup = ImGui::GetMousePosOnOpeningCurrentPopup();
        int x = static_cast<int>(std::floor(view.x + (popup.x - middle.x) * view.blocksPerPixel));
        int z = static_cast<int>(std::floor(view.z + (popup.y - middle.y) * view.blocksPerPixel));
        ImGui::TextDisabled("%d / %d",x,z);
        if (ImGui::MenuItem("Copy coordinates")) command = {{"action","copy"},{"text",std::to_string(x) + " " + std::to_string(z)}};
        if (ImGui::MenuItem("Center here")) { view.x = x; view.z = z; view.followPlayer = false; }
        if (ImGui::MenuItem("Zoom here")) { view.x = x; view.z = z; view.followPlayer = false; mapZoom(0.5,{0,0},{0,0}); }
        ImGui::EndPopup();
    }

    if (!view.selected.empty()) {
        ImGui::PushStyleColor(ImGuiCol_ChildBg,{0.045f,0.045f,0.052f,1});
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,{10 * uiScale,8 * uiScale});
        ImGui::BeginChild("map-selection",{0,56 * uiScale},ImGuiChildFlags_AlwaysUseWindowPadding);
        ImGui::PopStyleVar(); ImGui::PopStyleColor();
        if (ImGui::SmallButton("Copy##map-selection")) command = {{"action","copy"},{"text",coordinatesOf(view.selected)}};
        ImGui::SameLine();
        if (ImGui::SmallButton("Center##map-selection")) { view.x = view.selected.value("x",0); view.z = view.selected.value("z",0); view.followPlayer = false; }
        ImGui::SameLine();
        if (ImGui::SmallButton("Track##map-selection")) command = {{"action","track"},{"name",view.selected.value("name","Waypoint")},{"x",view.selected.value("x",0)},{"z",view.selected.value("z",0)},{"dimension",dimension}};
        ImGui::SameLine(); ImGui::TextUnformatted(label(view.selected.value("name", "")).c_str());
        ImGui::TextDisabled("%s",coordinatesOf(view.selected).c_str());
        ImGui::EndChild();
    } else {
        ImGui::Dummy({0,3 * uiScale});
        ImGui::TextDisabled("Drag to pan. Scroll to zoom. Shift for finer zoom.");
        ImGui::TextDisabled("Select a marker to inspect its location.");
    }
    std::string mapError = view.data.value("error", "");
    if (!mapError.empty()) ImGui::TextWrapped("%s",mapError.c_str());
    else if (view.sentKey != desired || view.data.value("id", -1LL) != view.requestId) ImGui::TextDisabled("Loading map...");
    else if (view.data.value("remaining", 0) > 0) ImGui::TextDisabled("Loading %d tiles...   %d structures",view.data.value("remaining",0),visibleMarkers);
    else if (view.structures && view.data.value("overviewStructures",false)) ImGui::TextDisabled("%d nearest structures shown at this zoom",visibleMarkers);
    else ImGui::TextDisabled("%d structures   Biomes at Y %d", visibleMarkers, view.y);
    std::string mapWarning = view.data.value("warning", "");
    if (!mapWarning.empty()) ImGui::TextColored({1,0.71f,0.65f,1}, "%s", mapWarning.c_str());
    ImGui::TextDisabled("%d x %d blocks   Sampling: %d blocks%s",maxX - minX,maxZ - minZ,drawnStep,drawnStep != step ? " (updating)" : "");
    ImGui::EndChild();
}

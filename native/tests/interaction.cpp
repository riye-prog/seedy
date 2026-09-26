#include <imgui_internal.h>
#include <map>
#include <iostream>
#include <fstream>
#include "../overlay.cpp"

static std::map<ImGuiID, ImRect> bounds;
static std::map<std::string, ImGuiID> labels;

void ImGuiTestEngineHook_ItemAdd(ImGuiContext *, ImGuiID id, const ImRect &rect, const ImGuiLastItemData *) { bounds[id] = rect; }
void ImGuiTestEngineHook_ItemInfo(ImGuiContext *, ImGuiID id, const char *name, ImGuiItemStatusFlags) { labels[name] = id; }
void ImGuiTestEngineHook_Log(ImGuiContext *, const char *, ...) { }
const char *ImGuiTestEngine_FindItemDebugLabel(ImGuiContext *, ImGuiID) { return ""; }

static void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

static Json tick() {
    ImGui::NewFrame();
    auto action = drawPanel(1440, 1000);
    ImGui::Render();
    return action;
}

static Json click(const char *name) {
    for (auto *window : context->Windows) { auto id = window->GetID(name); if (window->Active && !window->Hidden && bounds.count(id)) { labels[name] = id; break; } }
    require(labels.count(name) != 0, name);
    auto point = bounds.at(labels.at(name)).GetCenter();
    ImGui::GetIO().AddMousePosEvent(point.x, point.y);
    tick();
    ImGui::GetIO().AddMouseButtonEvent(0, true);
    tick();
    ImGui::GetIO().AddMouseButtonEvent(0, false);
    return tick();
}

static void exportFrame(const std::string &path) {
    Json frame = {{"width",1440},{"height",1000},{"lists",Json::array()}};
    for (auto *list : ImGui::GetDrawData()->CmdLists) {
        Json vertices = Json::array(), indices = Json::array(), commands = Json::array();
        for (const auto &vertex : list->VtxBuffer) vertices.push_back({vertex.pos.x,vertex.pos.y,vertex.uv.x,vertex.uv.y,vertex.col});
        for (const auto index : list->IdxBuffer) indices.push_back(index);
        for (const auto &command : list->CmdBuffer) commands.push_back({{"clip",{command.ClipRect.x,command.ClipRect.y,command.ClipRect.z,command.ClipRect.w}},{"count",command.ElemCount},{"index",command.IdxOffset},{"vertex",command.VtxOffset}});
        frame["lists"].push_back({{"vertices",vertices},{"indices",indices},{"commands",commands}});
    }
    unsigned char *pixels;
    int width, height;
    ImGui::GetIO().Fonts->GetTexDataAsRGBA32(&pixels,&width,&height);
    frame["atlas"] = {width,height};
    std::ofstream(path + ".rgba",std::ios::binary).write(reinterpret_cast<char *>(pixels), width * height * 4);
    std::ofstream(path) << frame.dump();
}

int main(int argc, char **argv) {
    try {
        context = ImGui::CreateContext();
        context->TestEngineHookItems = true;
        auto &io = ImGui::GetIO();
        io.IniFilename = nullptr;
        io.DisplaySize = {1440, 1000};
        io.DeltaTime = 1.0f / 60;
        io.BackendFlags |= ImGuiBackendFlags_RendererHasVtxOffset;
        configureAppearance(1);
        io.Fonts->Build();
        state = {{"connected",true},{"seed","123"},{"structures",{"minecraft:ancient_cities"}},{"recoveryTypes",{"minecraft:desert_pyramids"}},{"observations",Json::array()}};
        tick(); tick();
        require(click("Pause collection").value("action", "") == "collect", "Collection button did not activate");
        require(click("Rescan").value("action", "") == "rescan", "Rescan button did not activate");
        click("Locate##nav");
        require(activeSection == 1, "Locate tab did not activate");
        ImGui::SetWindowSize(ImGui::FindWindowByName("Seedy"), {920,900});
        ImGui::SetWindowPos(ImGui::FindWindowByName("Seedy"), {20,20});
        tick(); tick();
        require(click("Locate").value("action", "") == "locate", "Locate action did not activate");
        state["searchTarget"] = "minecraft:ancient_cities";
        state["searchKind"] = "structure";
        state["searchGeneration"] = 42;
        state["results"] = Json::array({{{"name","minecraft:ancient_city"},{"x",120},{"y",-37},{"z",240},{"dimension","overworld"},{"distance",268},{"confidence","Vanilla generation"}}});
        tick(); tick();
        require(click("Copy").value("text", "") == "120 -37 240", "Structure height was lost when copying coordinates");
        auto pinAction = click("Save");
        require(pinAction.value("action", "") == "pin" && pinAction.value("generation", 0) == 42, "Save location did not retain its search generation");
        state["savedLocations"] = Json::array({{{"id","saved-1"},{"name","minecraft:ancient_city"},{"x",120},{"y",-37},{"z",240},{"dimension","overworld"}}});
        click("Saved##nav");
        require(activeSection == 3, "Saved tab did not activate");
        require(click("Copy").value("text", "") == "120 -37 240", "Saved coordinates were not copied");
        require(click("Remove").value("action", "") == "unpin", "Saved location could not be removed");
        click("Seed##nav");
        require(activeSection == 2, "Seed tab did not activate");
        click("##seed");
        io.AddInputCharactersUTF8("-123456"); tick();
        auto seedAction = click("Use seed");
        require(seedAction.value("seed", "") == "-123456", "Seed text entry failed");
        require(click("Copy seed").value("action", "") == "copy", "Copy button did not activate");
        auto *window = ImGui::FindWindowByName("Seedy");
        auto start = window->Pos;
        auto handle = bounds.at(labels.at("Move panel")).GetCenter();
        io.AddMousePosEvent(handle.x, handle.y); tick();
        io.AddMouseButtonEvent(0, true); tick();
        io.AddMousePosEvent(handle.x + 80, handle.y + 45); tick();
        io.AddMouseButtonEvent(0, false); tick();
        require(window->Pos.x == start.x + 80 && window->Pos.y == start.y + 45, "Panel did not follow the drag");
        require(click("X##close").value("action", "") == "close", "Close button did not activate after dragging");
        click("World##nav");
        require(activeSection == 4, "World tab did not activate");
        auto outlines = click("Buried treasure outlines");
        require(outlines.value("action", "") == "outlines" && !outlines.value("treasure", true), "Treasure toggle did not activate");
        outlines = click("Mob spawner outlines");
        require(outlines.value("action", "") == "outlines" && !outlines.value("spawners", true), "Spawner toggle did not activate");
        outlines = click("Matching loot chest outlines");
        require(outlines.value("action", "") == "outlines" && !outlines.value("loot", true), "Loot toggle did not activate");
        ImGui::SetWindowSize(window, {920, 900});
        ImGui::SetWindowPos(window, {20,20});
        state["lootItems"] = Json::array({"minecraft:enchanted_golden_apple", "minecraft:diamond"});
        tick(); tick();
        click("Locate##nav");
        require(activeSection == 1, "Locate tab did not activate after resizing");
        tick(); tick();
        auto predictionClick = click("Predict chest loot");
        require(predictionClick.value("action", "") == "cancelSearch", "Changing loot options left stale results");
        auto lootAction = click("Locate");
        require(lootAction.value("lootEnabled", false), "Chest prediction was not enabled");
        require(lootAction["lootRequirements"][0]["item"] == "minecraft:enchanted_golden_apple" && lootAction["lootRequirements"][0]["minimum"] == 3, "Item filter was not sent with the search");
        require(click("Remove").value("action", "") == "cancelSearch", "Filter removal did not clear previous results");
        require(click("Locate")["lootRequirements"].empty(), "Removing filters did not allow unfiltered chest browsing");
        state["results"][0]["loot"] = {{"chestCount",1},{"uncertainCount",0},{"totals",{{"minecraft:enchanted_golden_apple",3}}}};
        tick(); tick();
        auto detailAction = click("120 -37 240##chests");
        require(detailAction.value("action", "") == "chests" && detailAction.value("generation",0) == 42, "Chest details did not retain the search generation");
        state["chestDetailIndex"] = 0;
        state["chestDetails"] = state["results"][0];
        state["chestDetails"]["loot"]["chests"] = Json::array({{{"x",125},{"y",-47},{"z",243},{"wanted",true},{"warning",""},{"items",Json::array({{{"id","minecraft:enchanted_golden_apple"},{"count",3},{"details",""}}})}}});
        tick(); tick();
        require(click("Copy chest").value("text", "") == "125 -47 243", "Chest coordinates were not copied");
        state["catalogs"] = {{"overworld",{{"structures",{"minecraft:ancient_cities"}},{"biomes",{"minecraft:plains"}}}}, {"the_nether",{{"structures",{"minecraft:nether_complexes"}},{"biomes",{"minecraft:warped_forest"}}}}, {"the_end",{{"structures",{"minecraft:end_cities"}},{"biomes",{"minecraft:end_highlands"}}}}};
        click("Dimension"); tick();
        require(click("End").value("action", "") == "cancelSearch", "Dimension change did not clear results");
        auto endSearch = click("Locate");
        require(endSearch.value("dimension", "") == "the_end" && endSearch.value("target", "") == "minecraft:end_cities", "End search used the wrong catalog");
        click("End cities"); tick();
        require(click("With elytra ship").value("action", "") == "cancelSearch", "Ship filter change did not clear results");
        endSearch = click("Locate");
        require(endSearch.value("cityFilter", "") == "with_elytra", "Ship filter was not sent");
        state["searchDimension"] = "the_end";
        state["searchTarget"] = "minecraft:end_cities";
        state["searchCityFilter"] = "with_elytra";
        state["results"] = Json::array({{{"name","minecraft:end_city"},{"dimension","the_end"},{"x",3200},{"y",72},{"z",3400},{"distance",250},{"elytra",{{"x",3220},{"y",102},{"z",3420}}}}});
        state.erase("chestDetails"); state["chestDetailIndex"] = -1;
        tick(); tick();
        require(click("Copy elytra").value("text", "") == "3220 102 3420", "Elytra coordinates were not copied");
        click("End cities"); tick(); click("Without elytra ship");
        require(click("Locate").value("cityFilter", "") == "without_elytra", "No-ship filter was not sent");
        click("Dimension"); tick(); click("Nether");
        click("Biomes"); tick();
        auto netherSearch = click("Locate");
        require(netherSearch.value("dimension", "") == "the_nether" && netherSearch.value("target", "") == "minecraft:warped_forest" && netherSearch.value("cityFilter", "") == "any", "Nether biome search retained End filters");
        click("Map##nav");
        require(activeSection == 5, "Map tab did not activate");
        Json mapRequest;
        for (int i = 0; i < 30; i++) { auto action = tick(); if (action.value("action", "") == "map") mapRequest = action; }
        require(!mapRequest.empty() && mapRequest.value("dimension", "") == "overworld", "Map did not request visible tiles");
        state["map"] = mapRequest;
        state["map"]["seed"] = "123";
        state["map"]["revision"] = 1;
        state["map"]["remaining"] = 0;
        state["map"]["palette"] = Json::array({{{"name","minecraft:plains"},{"color",0xa0b773}}});
        state["map"]["tiles"] = Json::array({{{"x",0},{"z",0},{"runs",{1024,0}}}});
        state["map"]["markers"] = Json::array({{{"name","minecraft:ancient_city"},{"x",0},{"y",-37},{"z",0}}});
        tick(); tick();
        require(mapView.tiles.size() == 1 && mapView.colors.size() == 1, "Map tile was not decoded");
        auto canvas = bounds.at(labels.at("Seed map canvas"));
        auto canvasCenter = canvas.GetCenter();
        io.AddMousePosEvent(canvasCenter.x + 30, canvasCenter.y + 20); tick();
        double beforeZoom = mapView.blocksPerPixel;
        float anchorX = io.MousePos.x - canvasCenter.x, anchorZ = io.MousePos.y - canvasCenter.y;
        double anchoredX = mapView.x + anchorX * beforeZoom, anchoredZ = mapView.z + anchorZ * beforeZoom;
        io.AddMouseWheelEvent(0,1); tick();
        require(mapView.blocksPerPixel < beforeZoom, "Mouse wheel did not zoom the map");
        require(std::abs(mapView.x + anchorX * mapView.blocksPerPixel - anchoredX) < 0.001 && std::abs(mapView.z + anchorZ * mapView.blocksPerPixel - anchoredZ) < 0.001, "Map zoom did not preserve the cursor position");
        double beforeX = mapView.x, beforeZ = mapView.z;
        auto windowPosition = window->Pos;
        io.AddMousePosEvent(canvasCenter.x,canvasCenter.y); tick();
        io.AddMouseButtonEvent(0,true); tick();
        io.AddMousePosEvent(canvasCenter.x + 40,canvasCenter.y + 25); tick();
        io.AddMouseButtonEvent(0,false); tick();
        require(std::abs(mapView.x - (beforeX - 40 * mapView.blocksPerPixel)) < 0.001 && std::abs(mapView.z - (beforeZ - 25 * mapView.blocksPerPixel)) < 0.001, "Map did not follow the drag");
        require(window->Pos.x == windowPosition.x && window->Pos.y == windowPosition.y, "Dragging the map moved the panel");
        double beforeButton = mapView.blocksPerPixel;
        click("+##map-zoom");
        require(mapView.blocksPerPixel < beforeButton, "Map zoom button did not activate");
        mapView.selected = {{"name","minecraft:ancient_city"},{"x",12},{"y",-37},{"z",32}};
        tick(); tick();
        require(click("Copy##map-selection").value("text", "") == "12 -37 32", "Map selection coordinates were not copied");
        click("##map-dimension"); tick(); click("End");
        require(mapView.dimension == 2 && mapView.tiles.empty(), "Changing map dimension retained stale tiles");
        click("##map-ships"); tick(); click("With elytra ship");
        require(mapView.cityFilter == 1, "Map ship filter did not activate");
        for (int i = 0; i < 30; i++) { auto action = tick(); if (action.value("action", "") == "map") mapRequest = action; }
        require(mapRequest.value("dimension", "") == "the_end" && mapRequest.value("cityFilter", "") == "with_elytra", "Map request lost dimension or ship filter");
        auto leaveMap = click("Saved##nav");
        require(leaveMap.value("action", "") == "mapStop" && leaveMap.value("stopMap",false), "Leaving the map did not stop its worker");
        auto floatingSize = window->Size;
        auto floatingPosition = window->Pos;
        click("Expand##window"); tick();
        require(preferences.maximized && window->Size.x == 1428 && window->Size.y == 988, "Window did not maximize inside Minecraft");
        click("Restore##window"); tick();
        require(!preferences.maximized && window->Size.x == floatingSize.x && window->Size.y == floatingSize.y && window->Pos.x == floatingPosition.x && window->Pos.y == floatingPosition.y, "Restore lost the floating window geometry");
        require(io.ConfigWindowsResizeFromEdges, "Window edges are not resizable");
        auto beforeResize = window->Size;
        ImVec2 rightEdge{window->Pos.x + window->Size.x - 1,window->Pos.y + window->Size.y / 2};
        io.AddMousePosEvent(rightEdge.x,rightEdge.y);tick();io.AddMouseButtonEvent(0,true);tick();
        io.AddMousePosEvent(rightEdge.x + 48,rightEdge.y);tick();io.AddMouseButtonEvent(0,false);tick();
        require(window->Size.x > beforeResize.x + 40 && window->Size.y == beforeResize.y, "Dragging the window edge did not resize it");
        click("Settings##nav"); tick();
        require(activeSection == 6, "Settings tab did not open");
        click("Map controls");
        require(!preferences.controls, "Map sidebar setting did not toggle");
        click("Map controls");
        click("Map##nav"); tick(); tick();
        click("##map-zoom-presets"); tick(); click("Close-up");
        require(mapView.blocksPerPixel == 0.125, "Close-up preset did not reach the new zoom limit");
        mapZoom(0.1,{0,0},{0,0}); require(mapView.blocksPerPixel == 0.125,"Zoom exceeded close-up limit");
        click("##map-zoom-presets"); tick(); click("Overview");
        require(mapView.blocksPerPixel == 256, "Overview preset did not activate");
        click("Hide controls");
        require(!preferences.controls, "Map controls did not collapse");
        tick();
        click("Show controls");
        require(preferences.controls, "Map controls did not expand");
        float beforeSidebar = preferences.sidebarWidth;
        auto splitter = bounds.at(labels.at("Resize map controls")).GetCenter();
        io.AddMousePosEvent(splitter.x,splitter.y);tick();io.AddMouseButtonEvent(0,true);tick();
        io.AddMousePosEvent(splitter.x + 40,splitter.y);tick();io.AddMouseButtonEvent(0,false);tick();
        require(preferences.sidebarWidth == beforeSidebar + 40, "Map sidebar splitter did not resize");
        click("Display"); tick();
        click("Detail##map"); tick(); click("Detailed");
        require(preferences.detail == 2, "Map detail setting did not change");
        click("Grid##map"); tick(); click("Chunk grid");
        require(preferences.grid == 2, "Chunk grid setting did not change");
        auto preferencesAction = click("X##close");
        require(preferencesAction.contains("preferences") && preferencesAction["preferences"]["detail"] == 2 && preferencesAction["preferences"]["sidebarWidth"] == beforeSidebar + 40, "Closing did not save updated map settings");
        preferences.markerSize = 7;
        Json closedPreferences;
        persistPreferences(closedPreferences,true);
        require(closedPreferences.value("action", "") == "preferences" && closedPreferences["preferences"]["markerSize"] == 7, "Closing with a key did not flush pending settings");
        ImGui::NewFrame();
        drawOutlines({10,10,11,11,0,20,20,21,21,1,30,30,31,31,2});
        auto outlineDraw = ImGui::GetBackgroundDrawList();
        require(!outlineDraw->VtxBuffer.empty(), "Outlines were not drawn");
        for (const auto &vertex : outlineDraw->VtxBuffer) {
            auto rgb = vertex.col & 0xffffff;
            require(rgb == (IM_COL32(120,189,255,255) & 0xffffff) || rgb == (IM_COL32(255,206,102,255) & 0xffffff) || rgb == (IM_COL32(205,168,255,255) & 0xffffff), "Outline contains a dark backing stroke");
        }
        ImGui::Render();
        if (argc == 3) {
            Json preview; std::ifstream(argv[1]) >> preview;
            mapView = MapView{};
            mapView.initialized = true; mapView.seed = preview.value("seed", ""); mapView.requestId = preview.value("id",0LL);
            state["seed"] = mapView.seed; state["map"] = preview;
            state["dimension"] = "overworld"; state["x"] = 128; state["z"] = 96;
            state["catalogs"]["overworld"]["structures"] = Json::array({"minecraft:villages"});
            activeSection = 5;
            preferences = UiPreferences{};
            preferences.detail = 2;
            state["gameVersion"] = "26.2";
            ImGui::SetWindowSize(window,{1160,860}); ImGui::SetWindowPos(window,{30,30});
            io.AddMousePosEvent(1200,900);
            tick(); tick();
            mapView.sentKey = mapView.desiredKey;
            auto startTime = std::chrono::steady_clock::now();
            for (int i = 0; i < 120; i++) tick();
            std::cout << "Map CPU frame ms: " << std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now() - startTime).count() / 120 << " vertices: " << ImGui::GetDrawData()->TotalVtxCount << '\n';
            exportFrame(argv[2]);
        }
        ImGui::DestroyContext(context);
        std::cout << "Passed: collection, rescan, navigation, locate, saving locations, seed entry, copy, dragging, close, loot filters, chest details, outline colors\n";
        return 0;
    } catch (const std::exception &error) {
        if (ImGui::GetCurrentContext()) exportFrame("/tmp/seedy-window-failure.json");
        std::cerr << error.what() << '\n';
        return 1;
    }
}

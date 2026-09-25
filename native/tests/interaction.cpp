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
    if (!labels.count(name)) for (auto *window : context->Windows) { auto id = window->GetID(name); if (bounds.count(id)) { labels[name] = id; break; } }
    require(labels.count(name) != 0, name);
    auto point = bounds.at(labels.at(name)).GetCenter();
    ImGui::GetIO().AddMousePosEvent(point.x, point.y);
    tick();
    ImGui::GetIO().AddMouseButtonEvent(0, true);
    tick();
    ImGui::GetIO().AddMouseButtonEvent(0, false);
    return tick();
}

int main(int argc, char **argv) {
    try {
        context = ImGui::CreateContext();
        context->TestEngineHookItems = true;
        auto &io = ImGui::GetIO();
        io.IniFilename = nullptr;
        io.DisplaySize = {1440, 1000};
        io.DeltaTime = 1.0f / 60;
        configureAppearance(1);
        io.Fonts->Build();
        state = {{"connected",true},{"seed","123"},{"structures",{"minecraft:ancient_cities"}},{"recoveryTypes",{"minecraft:desert_pyramids"}},{"observations",Json::array()}};
        tick(); tick();
        require(click("Pause collection").value("action", "") == "collect", "Collection button did not activate");
        require(click("Rescan").value("action", "") == "rescan", "Rescan button did not activate");
        click("Locate##nav");
        require(activeSection == 1, "Locate tab did not activate");
        ImGui::SetWindowSize(ImGui::FindWindowByName("Seedy"), {640,900});
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
        ImGui::SetWindowSize(window, {640, 900});
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
        ImGui::NewFrame();
        drawOutlines({10,10,11,11,0,20,20,21,21,1,30,30,31,31,2});
        auto outlineDraw = ImGui::GetBackgroundDrawList();
        require(!outlineDraw->VtxBuffer.empty(), "Outlines were not drawn");
        for (const auto &vertex : outlineDraw->VtxBuffer) {
            auto rgb = vertex.col & 0xffffff;
            require(rgb == (IM_COL32(120,189,255,255) & 0xffffff) || rgb == (IM_COL32(255,206,102,255) & 0xffffff) || rgb == (IM_COL32(205,168,255,255) & 0xffffff), "Outline contains a dark backing stroke");
        }
        ImGui::Render();
        ImGui::DestroyContext(context);
        std::cout << "Passed: collection, rescan, navigation, locate, saving locations, seed entry, copy, dragging, close, loot filters, chest details, outline colors\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}

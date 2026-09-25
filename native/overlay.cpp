#include <jni.h>
#include <imgui.h>
#ifndef SEEDY_HEADLESS_TEST
#include "vulkan_renderer.h"
#endif
#include <nlohmann/json.hpp>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <cmath>
#include <map>
#include <string>
#include <vector>
#include "font_data.h"

using Json = nlohmann::json;
static ImGuiContext *context = nullptr;
static JNIEnv *frameEnvironment = nullptr;
static jclass bridgeClass = nullptr;
static std::string clipboard;
static Json state = Json::object();
static std::string previousState;
static float uiScale = 1;
static int activeSection = 0;
static auto previousFrame = std::chrono::steady_clock::now();

static std::string javaString(JNIEnv *env, jstring value) {
    const char *bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) return {};
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}

static std::string label(std::string value) {
    auto prefix = value.find(':');
    if (prefix != std::string::npos) value = value.substr(prefix + 1);
    std::replace(value.begin(), value.end(), '_', ' ');
    if (!value.empty()) value[0] = static_cast<char>(std::toupper(value[0]));
    return value;
}

static void configureAppearance(float scale) {
    uiScale = std::clamp(scale, 1.0f, 2.5f);
    auto &io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.LogFilename = nullptr;
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
#ifdef __APPLE__
    io.ConfigMacOSXBehaviors = true;
#endif
    ImFontConfig fontConfig;
    fontConfig.FontDataOwnedByAtlas = false;
    io.Fonts->AddFontFromMemoryTTF(const_cast<unsigned char *>(font_data), sizeof(font_data), 15 * uiScale, &fontConfig);
    ImGui::StyleColorsDark();
    auto &style = ImGui::GetStyle();
    style.WindowRounding = 12;
    style.FrameRounding = 5;
    style.ChildRounding = 6;
    style.TabRounding = 5;
    style.WindowPadding = {18, 15};
    style.FramePadding = {9, 6};
    style.ItemSpacing = {9, 9};
    style.CellPadding = {8, 5};
    style.WindowBorderSize = 0;
    style.ChildBorderSize = 0;
    style.FrameBorderSize = 0;
    style.PopupBorderSize = 0;
    style.TabBarBorderSize = 0;
    style.GrabRounding = 4;
    style.ScrollbarSize = 10;
    io.ConfigWindowsMoveFromTitleBarOnly = true;
    style.Colors[ImGuiCol_WindowBg] = {0.105f,0.12f,0.145f,1};
    style.Colors[ImGuiCol_ChildBg] = {0.085f,0.10f,0.12f,1};
    style.Colors[ImGuiCol_PopupBg] = {0.15f,0.17f,0.20f,1};
    style.Colors[ImGuiCol_FrameBg] = {0.17f,0.19f,0.225f,1};
    style.Colors[ImGuiCol_FrameBgHovered] = {0.22f,0.25f,0.29f,1};
    style.Colors[ImGuiCol_FrameBgActive] = {0.26f,0.30f,0.36f,1};
    style.Colors[ImGuiCol_Button] = {0.20f,0.235f,0.28f,1};
    style.Colors[ImGuiCol_ButtonHovered] = {0.28f,0.34f,0.42f,1};
    style.Colors[ImGuiCol_ButtonActive] = {0.32f,0.40f,0.51f,1};
    style.Colors[ImGuiCol_Header] = {0.16f,0.19f,0.23f,1};
    style.Colors[ImGuiCol_HeaderHovered] = {0.27f,0.34f,0.43f,1};
    style.Colors[ImGuiCol_HeaderActive] = {0.30f,0.38f,0.48f,1};
    style.Colors[ImGuiCol_CheckMark] = {0.63f,0.76f,0.94f,1};
    style.Colors[ImGuiCol_SliderGrab] = {0.50f,0.64f,0.82f,1};
    style.Colors[ImGuiCol_SliderGrabActive] = {0.68f,0.80f,0.96f,1};
    style.Colors[ImGuiCol_TableHeaderBg] = {0.12f,0.14f,0.17f,1};
    style.Colors[ImGuiCol_TableRowBgAlt] = {1,1,1,0.025f};
    style.Colors[ImGuiCol_Text] = {0.92f,0.94f,0.97f,1};
    style.Colors[ImGuiCol_TextDisabled] = {0.64f,0.69f,0.76f,1};
    style.ScaleAllSizes(uiScale);
}

static void initialize(JNIEnv *env, jclass type, float scale) {
    context = ImGui::CreateContext();
    ImGui::SetCurrentContext(context);
    bridgeClass = static_cast<jclass>(env->NewGlobalRef(type));
    auto &platform = ImGui::GetPlatformIO();
    platform.Platform_GetClipboardTextFn = [](ImGuiContext *) -> const char * {
        auto method = frameEnvironment->GetStaticMethodID(bridgeClass, "readClipboard", "()Ljava/lang/String;");
        auto value = static_cast<jstring>(frameEnvironment->CallStaticObjectMethod(bridgeClass, method));
        clipboard = value ? javaString(frameEnvironment, value) : "";
        if (value) frameEnvironment->DeleteLocalRef(value);
        return clipboard.c_str();
    };
    platform.Platform_SetClipboardTextFn = [](ImGuiContext *, const char *value) {
        auto method = frameEnvironment->GetStaticMethodID(bridgeClass, "writeClipboard", "(Ljava/lang/String;)V");
        auto text = frameEnvironment->NewStringUTF(value);
        frameEnvironment->CallStaticVoidMethod(bridgeClass, method, text);
        frameEnvironment->DeleteLocalRef(text);
    };
    configureAppearance(scale);
}

static std::string selectValues(const char *name, const std::vector<std::string> &choices, int &selected) {
    if (choices.empty()) { ImGui::TextDisabled("No supported targets"); return ""; }
    selected = std::clamp(selected, 0, static_cast<int>(choices.size()) - 1);
    if (ImGui::BeginCombo(name, label(choices[selected]).c_str())) {
        static std::map<std::string, std::string> queries;
        char query[96] = "";
        auto &savedQuery = queries[name];
        std::strncpy(query, savedQuery.c_str(), sizeof(query) - 1);
        ImGui::SetNextItemWidth(-1);
        if (ImGui::InputTextWithHint("##filter", "Filter names", query, sizeof(query))) savedQuery = query;
        for (int i = 0; i < static_cast<int>(choices.size()); i++) {
            std::string candidate = label(choices[i]), filter = query;
            std::transform(candidate.begin(), candidate.end(), candidate.begin(), [](unsigned char c) { return std::tolower(c); });
            std::transform(filter.begin(), filter.end(), filter.begin(), [](unsigned char c) { return std::tolower(c); });
            if (candidate.find(filter) == std::string::npos) continue;
            if (ImGui::Selectable(label(choices[i]).c_str(), selected == i)) selected = i;
            if (selected == i) ImGui::SetItemDefaultFocus();
        }
        ImGui::EndCombo();
    }
    return choices[selected];
}

static std::string select(const char *name, const char *key, int &selected) {
    return selectValues(name, state.value(key, std::vector<std::string>{}), selected);
}

static void recoveryPanel(Json &command) {
    bool running = state.value("recovering", false);
    auto observations = state.value("observations", Json::array());
    if (!state.value("connected", false)) ImGui::TextDisabled("Join a world to collect observations.");
    if (ImGui::Button(state.value("collecting", true) ? "Pause collection" : "Resume collection")) command = {{"action", "collect"}};
    ImGui::SameLine();
    ImGui::BeginDisabled(!running && (observations.size() < 5 || !state.value("connected", false)));
    if (ImGui::Button(running ? "Stop search" : "Recover seed")) command = {{"action", running ? "stopRecovery" : "recover"}};
    ImGui::EndDisabled();
    ImGui::SameLine();
    ImGui::BeginDisabled(!state.value("connected", false));
    if (ImGui::Button("Rescan")) command = {{"action", "rescan"}};
    ImGui::EndDisabled();
    if (ImGui::IsItemHovered(ImGuiHoveredFlags_AllowWhenDisabled)) ImGui::SetTooltip("Check loaded chunks again, including structures that loaded in pieces.");
    ImGui::TextDisabled("%zu observations    %.1f evidence bits", observations.size(), state.value("evidence", 0.0));
    int pending = state.value("pendingChunks", 0);
    if (pending > 0) { ImGui::SameLine(); ImGui::TextDisabled("%d chunks queued", pending); }
    if (ImGui::BeginTable("detection-counts", 4, ImGuiTableFlags_SizingStretchSame)) {
        const char *types[] = {"minecraft:desert_pyramids", "minecraft:swamp_huts", "minecraft:shipwrecks", "minecraft:trial_chambers"};
        const char *names[] = {"Pyramids", "Swamp huts", "Shipwrecks", "Trial chambers"};
        for (int i = 0; i < 4; i++) {
            int count = static_cast<int>(std::count_if(observations.begin(), observations.end(), [&](const Json &entry) { return entry.value("structure", "") == types[i]; }));
            ImGui::TableNextColumn(); ImGui::TextDisabled("%s", names[i]); ImGui::Text("%d", count);
        }
        ImGui::EndTable();
    }
    ImGui::PushTextWrapPos();
    ImGui::TextUnformatted(state.value("recoveryStatus", "Idle").c_str());
    ImGui::PopTextWrapPos();
    if (running) ImGui::ProgressBar(static_cast<float>(state.value("progress", 0.0)), {-1, 5 * uiScale}, "");
    if (ImGui::BeginChild("observations", {0, 145 * uiScale}, ImGuiChildFlags_None)) {
        if (observations.empty()) ImGui::TextWrapped("Explore shipwrecks, trial chambers, desert pyramids, or swamp huts. Detected start chunks appear here. For trial chambers, load the starting room as well as the corridors.");
        else if (ImGui::BeginTable("observation-list", 4, ImGuiTableFlags_RowBg | ImGuiTableFlags_SizingStretchProp)) {
            ImGui::TableSetupColumn("Structure", 0, 2);
            ImGui::TableSetupColumn("Start chunk", 0, 1.4f);
            ImGui::TableSetupColumn("Source");
            ImGui::TableSetupColumn("", ImGuiTableColumnFlags_WidthFixed, 68 * uiScale);
            ImGui::TableHeadersRow();
            for (auto &entry : observations) {
                ImGui::PushID(entry.at("id").get<std::string>().c_str());
                ImGui::TableNextRow(); ImGui::TableNextColumn(); ImGui::TextUnformatted(label(entry.at("structure")).c_str());
                ImGui::TableNextColumn(); ImGui::Text("%d, %d", entry.at("chunkX").get<int>(), entry.at("chunkZ").get<int>());
                ImGui::TableNextColumn(); ImGui::TextDisabled("%s", entry.at("source").get<std::string>().c_str());
                ImGui::TableNextColumn(); if (ImGui::SmallButton("Remove")) command = {{"action", "remove"}, {"id", entry.at("id")}};
                ImGui::PopID();
            }
            ImGui::EndTable();
        }
    }
    ImGui::EndChild();
    if (ImGui::CollapsingHeader("Add observation")) {
        static int type = 0, chunks[2] = {0,0};
        ImGui::SetNextItemWidth(230 * uiScale);
        auto structure = select("Structure##observation", "recoveryTypes", type);
        ImGui::SetNextItemWidth(230 * uiScale);
        ImGui::InputInt2("Chunk X / Z", chunks);
        if (ImGui::Button("Add") && !structure.empty()) command = {{"action", "observe"}, {"structure", structure}, {"x", chunks[0]}, {"z", chunks[1]}};
        ImGui::SameLine(); ImGui::TextDisabled("Use the structure's start chunk.");
    }
}

static std::string coordinatesOf(const Json &entry) {
    return std::to_string(entry.at("x").get<int>()) + " " + (entry.contains("y") && !entry["y"].is_null() ? std::to_string(entry["y"].get<int>()) + " " : "") + std::to_string(entry.at("z").get<int>());
}

static void chestDetails(const Json &entry, Json &command) {
    auto loot = entry.at("loot");
    ImGui::Text("%s at %s", label(entry.value("name", "")).c_str(), coordinatesOf(entry).c_str());
    ImGui::TextWrapped("Unopened vanilla chests, with normal luck. Totals are combined across the structure.");
    if (ImGui::TreeNode("Item totals")) {
        for (auto &total : loot.at("totals").items()) ImGui::Text("%d  %s", total.value().get<int>(), label(total.key()).c_str());
        ImGui::TreePop();
    }
    auto chests = loot.value("chests", Json::array());
    ImGui::TextDisabled("%zu chests", chests.size());
    for (size_t i = 0; i < chests.size(); i++) {
        const auto &chest = chests[i];
        ImGui::PushID(static_cast<int>(i));
        bool wanted = chest.value("wanted", false);
        std::string heading = coordinatesOf(chest) + (wanted ? "   Matching items" : "");
        if (wanted) ImGui::PushStyleColor(ImGuiCol_Text, {0.80f,0.66f,1.0f,1});
        bool expanded = ImGui::TreeNodeEx(heading.c_str(), wanted ? ImGuiTreeNodeFlags_DefaultOpen : ImGuiTreeNodeFlags_None);
        if (wanted) ImGui::PopStyleColor();
        if (expanded) {
            if (ImGui::SmallButton("Copy chest")) command = {{"action","copy"},{"text",coordinatesOf(chest)}};
            if (!chest.contains("y") || chest["y"].is_null()) { ImGui::SameLine(); ImGui::TextDisabled("X / Z; height is resolved when loaded"); }
            auto warning = chest.value("warning", "");
            if (!warning.empty()) ImGui::TextWrapped("%s", warning.c_str());
            else if (ImGui::BeginTable("items", 2, ImGuiTableFlags_RowBg | ImGuiTableFlags_SizingStretchProp)) {
                ImGui::TableSetupColumn("Count", ImGuiTableColumnFlags_WidthFixed, 48 * uiScale);
                ImGui::TableSetupColumn("Item");
                for (const auto &item : chest.at("items")) {
                    ImGui::TableNextRow(); ImGui::TableNextColumn(); ImGui::Text("%d", item.value("count", 0));
                    ImGui::TableNextColumn(); ImGui::TextUnformatted(label(item.value("id", "")).c_str());
                    auto details = item.value("details", "");
                    if (!details.empty()) { ImGui::PushStyleColor(ImGuiCol_Text, ImGui::GetStyleColorVec4(ImGuiCol_TextDisabled)); ImGui::TextWrapped("%s", details.c_str()); ImGui::PopStyleColor(); }
                }
                ImGui::EndTable();
            }
            ImGui::TreePop();
        }
        ImGui::PopID();
    }
}

static void locatePanel(Json &command) {
    static int kind = 0, target = 0, origin[3] = {0,64,0}, radius = 2048, dimensionIndex = 0, cityFilter = 0;
    const char *dimensions[] = {"overworld", "the_nether", "the_end"};
    const char *dimensionNames[] = {"Overworld", "Nether", "End"};
    ImGui::SetNextItemWidth(245 * uiScale);
    if (ImGui::Combo("Dimension", &dimensionIndex, dimensionNames, 3)) { target = 0; cityFilter = 0; command = {{"action","cancelSearch"}}; }
    std::string dimension = dimensions[dimensionIndex];
    auto catalogs = state.value("catalogs", Json::object());
    auto catalog = catalogs.value(dimension, Json::object());
    if (ImGui::RadioButton("Structures", kind == 0)) { kind = 0; target = 0; command = {{"action","cancelSearch"}}; }
    ImGui::SameLine();
    if (ImGui::RadioButton("Biomes", kind == 1)) { kind = 1; target = 0; command = {{"action","cancelSearch"}}; }
    ImGui::SetNextItemWidth(245 * uiScale);
    std::string selected = selectValues("Target", catalog.value(kind == 0 ? "structures" : "biomes", state.value(kind == 0 ? "structures" : "biomes", std::vector<std::string>{})), target);
    static std::string previousTarget;
    if (selected != previousTarget) { command = {{"action","cancelSearch"}}; previousTarget = selected; }
    if (state.value("seed", "").empty()) ImGui::TextDisabled("Enter a seed in the Seed tab to locate features.");
    ImGui::SetNextItemWidth(245 * uiScale);
    if (ImGui::InputInt3("X / Y / Z", origin)) command = {{"action","cancelSearch"}};
    ImGui::SameLine();
    ImGui::BeginDisabled(state.value("dimension", "overworld") != dimension);
    if (ImGui::Button("Use position")) { origin[0] = state.value("x",0); origin[1] = state.value("y",64); origin[2] = state.value("z",0); command = {{"action","cancelSearch"}}; }
    ImGui::EndDisabled();
    if (ImGui::IsItemHovered(ImGuiHoveredFlags_AllowWhenDisabled) && state.value("dimension", "overworld") != dimension) ImGui::SetTooltip("Enter coordinates in the selected dimension. Coordinates are not portal-scaled.");
    ImGui::SetNextItemWidth(245 * uiScale);
    if (ImGui::SliderInt("Radius", &radius, 128, 8192, "%d blocks")) command = {{"action","cancelSearch"}};
    const char *cityFilters[] = {"any", "with_elytra", "without_elytra"};
    bool isEndCity = kind == 0 && selected == "minecraft:end_cities";
    if (isEndCity) {
        const char *names[] = {"All cities", "With elytra ship", "Without elytra ship"};
        ImGui::SetNextItemWidth(245 * uiScale);
        if (ImGui::Combo("End cities", &cityFilter, names, 3)) command = {{"action","cancelSearch"}};
        ImGui::TextWrapped("Checks generated ship pieces. An elytra may already have been taken.");
    }
    std::string selectedCityFilter = isEndCity ? cityFilters[cityFilter] : "any";
    static bool predictLoot = false;
    static std::vector<int> lootItems{0}, lootAmounts{3};
    bool supportsLoot = kind == 0 && (selected == "minecraft:ancient_cities" || selected == "minecraft:desert_pyramids");
    Json requirements = Json::array();
    if (supportsLoot) {
        if (ImGui::Checkbox("Predict chest loot", &predictLoot)) command = {{"action","cancelSearch"}};
        if (predictLoot) {
            if (ImGui::CollapsingHeader("Item filters", ImGuiTreeNodeFlags_DefaultOpen)) {
                ImGui::TextDisabled("Minimum total per structure. All filters must match.");
                for (size_t i = 0; i < lootItems.size(); i++) {
                    ImGui::PushID(static_cast<int>(i));
                    ImGui::SetNextItemWidth(std::max(160.0f * uiScale, ImGui::GetContentRegionAvail().x - 185 * uiScale));
                    int before = lootItems[i];
                    select("##loot-item", "lootItems", lootItems[i]);
                    if (before != lootItems[i]) command = {{"action","cancelSearch"}};
                    ImGui::SameLine(); ImGui::SetNextItemWidth(92 * uiScale);
                    if (ImGui::InputInt("##minimum", &lootAmounts[i], 1, 10)) { lootAmounts[i] = std::clamp(lootAmounts[i],1,99999); command = {{"action","cancelSearch"}}; }
                    ImGui::SameLine();
                    bool remove = ImGui::SmallButton("Remove");
                    ImGui::PopID();
                    if (remove) { lootItems.erase(lootItems.begin() + i); lootAmounts.erase(lootAmounts.begin() + i); command = {{"action","cancelSearch"}}; break; }
                }
                ImGui::BeginDisabled(lootItems.size() >= 8);
                if (ImGui::SmallButton("Add item")) { int next = 0; while (std::find(lootItems.begin(),lootItems.end(),next) != lootItems.end()) next++; lootItems.push_back(next); lootAmounts.push_back(1); command = {{"action","cancelSearch"}}; }
                ImGui::EndDisabled();
                if (lootItems.empty()) { ImGui::SameLine(); ImGui::TextDisabled("Showing all structures with chest contents"); }
            }
            ImGui::TextDisabled("Unavailable chests are excluded from item totals.");
            auto choices = state.value("lootItems", std::vector<std::string>{});
            for (size_t i = 0; i < lootItems.size(); i++) if (!choices.empty()) requirements.push_back({{"item",choices[std::clamp(lootItems[i],0,static_cast<int>(choices.size())-1)]},{"minimum",lootAmounts[i]}});
        }
    }
    bool running = state.value("searchStatus", "Idle") == "Searching";
    ImGui::BeginDisabled(state.value("seed", "").empty() || selected.empty() || running);
    if (ImGui::Button("Locate")) command = {{"action","locate"},{"kind",kind == 0 ? "structure" : "biome"},{"target",selected},{"x",origin[0]},{"y",origin[1]},{"z",origin[2]},{"radius",radius},{"dimension",dimension},{"cityFilter",selectedCityFilter},{"lootEnabled",supportsLoot && predictLoot},{"lootRequirements",requirements}};
    ImGui::EndDisabled();
    ImGui::SameLine();
    if (running && ImGui::Button("Cancel")) command = {{"action","cancelSearch"}};
    else ImGui::TextDisabled("%s", state.value("searchStatus", "Idle").c_str());
    ImGui::PushStyleColor(ImGuiCol_Text, ImGui::GetStyleColorVec4(ImGuiCol_TextDisabled));
    ImGui::TextWrapped(kind == 0 ? "Vanilla generation checked. Coordinates point to a generated piece; height is shown when available." : "Vanilla biomes in the selected dimension at the selected Y, sampled every 64 blocks.");
    ImGui::PopStyleColor();
    auto results = state.value("results", Json::array());
    if (state.value("searchDimension", "overworld") != dimension || state.value("searchCityFilter", "any") != selectedCityFilter || state.value("searchTarget", "") != selected || state.value("searchKind", "") != (kind == 0 ? "structure" : "biome") || command.value("action", "") == "cancelSearch") results = Json::array();
    if (!results.empty()) ImGui::TextDisabled("%zu results in %s", results.size(), label(results[0].value("dimension", "overworld")).c_str());
    int detailIndex = state.value("chestDetailIndex", -1);
    if (command.value("action", "") == "cancelSearch") detailIndex = -1;
    if (ImGui::BeginTable("locations", 3, ImGuiTableFlags_RowBg | ImGuiTableFlags_ScrollY, {0, 145 * uiScale})) {
        ImGui::TableSetupColumn("Coordinates", ImGuiTableColumnFlags_WidthStretch);
        ImGui::TableSetupColumn("Distance", ImGuiTableColumnFlags_WidthFixed, 105 * uiScale);
        ImGui::TableSetupColumn("", ImGuiTableColumnFlags_WidthFixed, 116 * uiScale);
        ImGui::TableHeadersRow();
        for (size_t i = 0; i < results.size(); i++) {
            auto &entry = results[i];
            std::string coordinates = std::to_string(entry.at("x").get<int>()) + " " + (entry.contains("y") && !entry["y"].is_null() ? std::to_string(entry["y"].get<int>()) + " " : "") + std::to_string(entry.at("z").get<int>());
            ImGui::PushID(static_cast<int>(i));
            ImGui::TableNextRow(); ImGui::TableNextColumn();
            bool hasLoot = entry.contains("loot") && !entry["loot"].is_null();
            if (hasLoot) {
                if (ImGui::Selectable((coordinates + "##chests").c_str(), detailIndex == static_cast<int>(i))) command = {{"action","chests"},{"index",i},{"generation",state.value("searchGeneration",0LL)}};
                if (ImGui::IsItemHovered()) ImGui::SetTooltip("View this structure's chests");
                auto totals = entry["loot"]["totals"];
                for (const auto &requirement : requirements) ImGui::TextDisabled("%d %s", totals.value(requirement.at("item").get<std::string>(),0), label(requirement.at("item").get<std::string>()).c_str());
                if (entry["loot"].value("uncertainCount",0) > 0) ImGui::TextDisabled("Incomplete total");
                if (requirements.empty()) ImGui::TextDisabled("%zu chests - select to view", entry["loot"].value("chestCount",0UL));
            } else { ImGui::TextUnformatted(coordinates.c_str()); if (ImGui::IsItemHovered()) ImGui::SetTooltip("%s", entry.value("confidence", "").c_str()); }
            if (selected == "minecraft:nether_complexes") ImGui::TextDisabled("%s", label(entry.value("name", "")).c_str());
            if (entry.value("name", "") == "minecraft:end_city") {
                bool elytra = entry.contains("elytra") && !entry["elytra"].is_null();
                ImGui::TextDisabled("%s", elytra ? "With elytra ship" : "Without elytra ship");
                if (elytra && ImGui::SmallButton("Copy elytra")) command = {{"action","copy"},{"text",coordinatesOf(entry["elytra"])}};
            }
            ImGui::TableNextColumn(); ImGui::Text("%lld blocks", entry.at("distance").get<long long>());
            ImGui::TableNextColumn(); if (ImGui::SmallButton("Copy")) command = {{"action","copy"},{"text",coordinates}};
            ImGui::SameLine();
            auto saved = state.value("savedLocations", Json::array());
            bool exists = std::any_of(saved.begin(), saved.end(), [&](const Json &location) { return location.value("name", "") == entry.value("name", "") && location.value("dimension", "") == entry.value("dimension", "") && location.value("x", 0) == entry.value("x", 0) && location.value("z", 0) == entry.value("z", 0) && location.value("y", Json()) == entry.value("y", Json()); });
            ImGui::BeginDisabled(exists || !state.value("connected", false));
            if (ImGui::SmallButton(exists ? "Saved" : "Save")) command = {{"action","pin"},{"index",i},{"generation",state.value("searchGeneration",0LL)}};
            ImGui::EndDisabled();
            ImGui::PopID();
        }
        ImGui::EndTable();
    }
    if (results.empty() && state.value("searchStatus", "Idle") == "Complete") ImGui::TextWrapped("No matches in this area. Adjust the filters or search radius.");
    if (detailIndex >= 0 && detailIndex < static_cast<int>(results.size()) && state.contains("chestDetails")) {
        ImGui::Dummy({0,8 * uiScale});
        if (ImGui::SmallButton("Close chest details")) { detailIndex = -1; command = {{"action","closeChests"}}; }
        if (detailIndex >= 0) chestDetails(state["chestDetails"], command);
    }
}

static void savedPanel(Json &command) {
    auto locations = state.value("savedLocations", Json::array());
    ImGui::TextUnformatted("Saved locations");
    ImGui::TextDisabled("%zu saved for the current seed", locations.size());
    if (locations.empty()) ImGui::TextWrapped("Save a search result to keep its coordinates here. Locations and observations are restored when you return to this world.");
    for (auto &entry : locations) {
        ImGui::PushID(entry.at("id").get<std::string>().c_str());
        ImGui::Dummy({0,6 * uiScale});
        ImGui::TextUnformatted(label(entry.value("name", "")).c_str());
        ImGui::SameLine(); ImGui::TextDisabled("%s", label(entry.value("dimension", "")).c_str());
        std::string coordinates = std::to_string(entry.at("x").get<int>()) + " " + (entry.contains("y") && !entry["y"].is_null() ? std::to_string(entry["y"].get<int>()) + " " : "") + std::to_string(entry.at("z").get<int>());
        ImGui::TextUnformatted(coordinates.c_str());
        if (entry.value("dimension", "") == state.value("dimension", "overworld") && state.value("connected", false)) {
            double dx = entry.at("x").get<double>() - state.value("x", 0), dz = entry.at("z").get<double>() - state.value("z", 0);
            const char *directions[] = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
            int direction = (static_cast<int>(std::round(std::atan2(dz, dx) * 4 / 3.141592653589793)) + 8) % 8;
            ImGui::SameLine(); ImGui::TextDisabled("%.0f blocks %s", std::hypot(dx,dz), directions[direction]);
        }
        if (ImGui::SmallButton("Copy")) command = {{"action","copy"},{"text",coordinates}};
        ImGui::SameLine();
        if (ImGui::SmallButton("Remove")) command = {{"action","unpin"},{"id",entry.at("id")}};
        ImGui::PopID();
    }
}

static void drawOutlines(const std::vector<double> &lines) {
    auto draw = ImGui::GetBackgroundDrawList();
    for (int i = 0; i + 4 < static_cast<int>(lines.size()); i += 5) {
        ImVec2 a{static_cast<float>(lines[i]),static_cast<float>(lines[i+1])}, b{static_cast<float>(lines[i+2]),static_cast<float>(lines[i+3])};
        draw->AddLine(a, b, lines[i+4] == 2 ? IM_COL32(205,168,255,255) : lines[i+4] == 1 ? IM_COL32(255,206,102,255) : IM_COL32(120,189,255,255), 1.5f * uiScale);
    }
}

static Json drawPanel(int width, int height) {
    ImGui::SetNextWindowSize({std::min(560 * uiScale, width - 24.0f), std::min(490 * uiScale, height - 24.0f)}, ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos({width / 2.0f, height / 2.0f}, ImGuiCond_FirstUseEver, {0.5f,0.5f});
    ImGui::SetNextWindowSizeConstraints({std::min(440 * uiScale, width - 24.0f),std::min(350 * uiScale,height - 24.0f)}, {static_cast<float>(width - 12),static_cast<float>(height - 12)});
    Json command = Json::object();
    bool resetContentScroll = false;
    if (ImGui::Begin("Seedy", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoSavedSettings)) {
        auto position = ImGui::GetWindowPos();
        auto size = ImGui::GetWindowSize();
        ImVec2 visiblePosition = {std::clamp(position.x, 0.0f, std::max(0.0f, width - size.x)),std::clamp(position.y, 0.0f, std::max(0.0f, height - size.y))};
        if (position.x != visiblePosition.x || position.y != visiblePosition.y) ImGui::SetWindowPos(visiblePosition);
        ImVec2 header = ImGui::GetCursorScreenPos();
        float headerWidth = ImGui::GetContentRegionAvail().x - 42 * uiScale;
        ImGui::InvisibleButton("Move panel", {headerWidth,32 * uiScale});
        if (ImGui::IsItemActive() && ImGui::IsMouseDragging(ImGuiMouseButton_Left, 0)) {
            auto position = ImGui::GetWindowPos();
            auto size = ImGui::GetWindowSize();
            auto delta = ImGui::GetIO().MouseDelta;
            ImGui::SetWindowPos({std::clamp(position.x + delta.x, 0.0f, std::max(0.0f, width - size.x)),std::clamp(position.y + delta.y, 0.0f, std::max(0.0f, height - size.y))});
        }
        if (ImGui::IsItemHovered()) ImGui::SetMouseCursor(ImGuiMouseCursor_ResizeAll);
        auto draw = ImGui::GetWindowDrawList();
        draw->AddText(nullptr, 20 * uiScale, {header.x,header.y + 3 * uiScale}, IM_COL32(235,240,248,255), "Seedy");
        std::string subtitle = state.value("connected", false) ? label(state.value("dimension", "overworld")) + "  /  " + state.value("gameVersion", "") : "No world connected";
        draw->AddText({header.x + 76 * uiScale,header.y + 7 * uiScale}, IM_COL32(164,177,194,255), subtitle.c_str());
        ImGui::SameLine();
        if (ImGui::Button("X##close", {32 * uiScale,30 * uiScale})) command = {{"action","close"}};
        const char *sections[] = {"Recovery##nav", "Locate##nav", "Seed##nav", "Saved##nav", "World##nav"};
        for (int i = 0; i < 5; i++) {
            if (i) ImGui::SameLine();
            ImGui::PushStyleColor(ImGuiCol_Button, i == activeSection ? ImVec4{0.29f,0.37f,0.48f,1} : ImVec4{0.15f,0.17f,0.20f,1});
            if (ImGui::Button(sections[i], {(ImGui::GetWindowWidth() - 36 * uiScale - 36 * uiScale) / 5,31 * uiScale})) { activeSection = i; resetContentScroll = true; }
            ImGui::PopStyleColor();
        }
        ImGui::Dummy({0,3 * uiScale});
        ImGui::PushStyleColor(ImGuiCol_ChildBg, {0,0,0,0});
        ImGui::BeginChild("panel-content", {0,-28 * uiScale}, ImGuiChildFlags_None);
        if (resetContentScroll) ImGui::SetScrollY(0);
        if (activeSection == 0) recoveryPanel(command);
        if (activeSection == 1) locatePanel(command);
        if (activeSection == 2) {
            std::string seed = state.value("seed", "");
            ImGui::TextUnformatted(seed.empty() ? "No seed set" : seed.c_str());
            if (!seed.empty()) {
                ImGui::TextDisabled("%s", state.value("verified",false) ? "Verified against this world's seed hash" : "Entered manually");
                if (ImGui::Button("Copy seed")) command = {{"action","copy"},{"text",seed}};
                ImGui::SameLine();
                if (ImGui::Button("Clear seed")) command = {{"action","clearSeed"}};
            }
            ImGui::Dummy({0,12 * uiScale});
            ImGui::TextUnformatted("Enter a seed");
            static char seedInput[32] = "";
            ImGui::SetNextItemWidth(-1);
            ImGui::InputTextWithHint("##seed", "World seed", seedInput, sizeof(seedInput), ImGuiInputTextFlags_CharsDecimal);
            if (ImGui::Button("Use seed")) command = {{"action","seed"},{"seed",seedInput}};
            ImGui::TextWrapped("Use the seed and game version that generated the area. Custom world generation can change the results.");
        }
        if (activeSection == 3) savedPanel(command);
        if (activeSection == 4) {
            bool treasure = state.value("treasureOutlines", true), spawners = state.value("spawnerOutlines", true), loot = state.value("lootOutlines", true);
            bool changed = ImGui::Checkbox("Buried treasure outlines", &treasure);
            changed |= ImGui::Checkbox("Mob spawner outlines", &spawners);
            changed |= ImGui::Checkbox("Matching loot chest outlines", &loot);
            if (changed) command = {{"action","outlines"},{"treasure",treasure},{"spawners",spawners},{"loot",loot}};
            ImGui::TextWrapped("Gold: treasure candidates. Blue: mob spawners. Purple: matching loot chests from the current search. Outlines show through blocks within 512 blocks.");
            ImGui::TextWrapped("Treasure is identified by chest position, biome and nearby blocks. Placed chests can match these checks.");
            auto objects = state.value("loadedObjects", Json::array());
            ImGui::TextDisabled("%zu objects in loaded chunks", objects.size());
            for (size_t i = 0; i < objects.size(); i++) {
                const auto &object = objects[i];
                ImGui::PushID(static_cast<int>(i));
                std::string coordinates = std::to_string(object.value("x",0)) + " " + std::to_string(object.value("y",0)) + " " + std::to_string(object.value("z",0));
                ImGui::TextUnformatted(object.value("kind", "").c_str());
                ImGui::SameLine();
                if (ImGui::SmallButton("Copy")) command = {{"action","copy"},{"text",coordinates}};
                ImGui::TextDisabled("%s", coordinates.c_str());
                ImGui::PopID();
            }
        }
        auto error = state.value("error", "");
        if (!error.empty()) { ImGui::PushStyleColor(ImGuiCol_Text,{1,0.71f,0.65f,1}); ImGui::TextWrapped("%s",error.c_str()); ImGui::PopStyleColor(); }
        ImGui::EndChild();
        ImGui::PopStyleColor();
        ImGui::TextDisabled("%s", state.value("storageStatus", "Progress saves locally").c_str());
        if (!state.value("seed", "").empty()) { ImGui::SameLine(); ImGui::TextDisabled("%s", state.value("verified", false) ? "Seed verified" : "Manual seed"); }
    }
    ImGui::End();
    return command;
}

#ifndef SEEDY_HEADLESS_TEST
extern "C" JNIEXPORT jstring JNICALL Java_dev_seedy_NativeGui_frame(JNIEnv *env, jclass type, jstring snapshot, jint width, jint height, jfloat density, jdoubleArray events, jlongArray graphics, jdoubleArray outlineLines, jboolean panel) {
    try {
        frameEnvironment = env;
        if (!context) initialize(env, type, density);
        ImGui::SetCurrentContext(context);
        std::string incoming = javaString(env, snapshot);
        if (incoming != previousState) { state = Json::parse(incoming); previousState = incoming; }
        auto &io = ImGui::GetIO();
        io.DisplaySize = {static_cast<float>(width), static_cast<float>(height)};
        auto now = std::chrono::steady_clock::now();
        io.DeltaTime = std::clamp(std::chrono::duration<float>(now - previousFrame).count(), 0.001f, 0.1f);
        previousFrame = now;
        int count = env->GetArrayLength(events);
        std::vector<double> input(count);
        env->GetDoubleArrayRegion(events, 0, count, input.data());
        const ImGuiKey keys[] = {ImGuiKey_None,ImGuiKey_Tab,ImGuiKey_LeftArrow,ImGuiKey_RightArrow,ImGuiKey_UpArrow,ImGuiKey_DownArrow,ImGuiKey_Backspace,ImGuiKey_Delete,ImGuiKey_Enter,ImGuiKey_Home,ImGuiKey_End,ImGuiMod_Ctrl,ImGuiMod_Shift,ImGuiKey_A,ImGuiKey_C,ImGuiKey_V,ImGuiKey_X,ImGuiKey_Z,ImGuiMod_Super,ImGuiMod_Alt,ImGuiKey_Y};
        for (int i = 0; i + 3 < count; i += 4) {
            int kind = static_cast<int>(input[i]);
            if (kind == 0) io.AddMousePosEvent(static_cast<float>(input[i+1]),static_cast<float>(input[i+2]));
            if (kind == 1 && input[i+1] >= 0 && input[i+1] < 5) io.AddMouseButtonEvent(static_cast<int>(input[i+1]),input[i+2] != 0);
            if (kind == 2) io.AddMouseWheelEvent(static_cast<float>(input[i+1]),static_cast<float>(input[i+2]));
            if (kind == 3 && input[i+1] > 0 && input[i+1] < 21) io.AddKeyEvent(keys[static_cast<int>(input[i+1])], input[i+2] != 0);
            if (kind == 4) io.AddInputCharacter(static_cast<unsigned int>(input[i+1]));
            if (kind == 5) io.AddFocusEvent(input[i+1] != 0);
        }
        jlong handles[9];
        if (env->GetArrayLength(graphics) != 9) throw std::runtime_error("Invalid Vulkan context");
        env->GetLongArrayRegion(graphics, 0, 9, handles);
        VulkanRenderer::prepare(handles);
        ImGui_ImplVulkan_NewFrame();
        ImGui::NewFrame();
        int lineCount = env->GetArrayLength(outlineLines);
        std::vector<double> lines(lineCount);
        env->GetDoubleArrayRegion(outlineLines, 0, lineCount, lines.data());
        drawOutlines(lines);
        Json command = panel ? drawPanel(width, height) : Json::object();
        ImGui::Render();
        VulkanRenderer::render(ImGui::GetDrawData(), handles, width, height);
        return env->NewStringUTF(command.empty() ? "" : command.dump().c_str());
    } catch (const std::exception &e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_dev_seedy_NativeGui_dispose(JNIEnv *env, jclass) {
    if (!context) return;
    ImGui::SetCurrentContext(context);
    VulkanRenderer::shutdown();
    ImGui::DestroyContext(context);
    context = nullptr;
    if (bridgeClass) env->DeleteGlobalRef(bridgeClass);
    bridgeClass = nullptr;
}

#endif

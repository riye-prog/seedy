#include <jni.h>
#include <imgui.h>
#ifndef SEEDY_HEADLESS_TEST
#include "vulkan_renderer.h"
#ifdef __APPLE__
#include "metal_renderer.h"
#endif
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
static ImFont *headingFont = nullptr;
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

static std::string timeLabel(long seconds) {
    if (seconds < 60) return std::to_string(seconds) + "s";
    return std::to_string(seconds / 60) + "m " + std::to_string(seconds % 60) + "s";
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
    headingFont = io.Fonts->AddFontFromMemoryTTF(const_cast<unsigned char *>(font_data), sizeof(font_data), 22 * uiScale, &fontConfig);
    ImGui::StyleColorsDark();
    auto &style = ImGui::GetStyle();
    style.WindowRounding = 14;
    style.FrameRounding = 9;
    style.ChildRounding = 12;
    style.PopupRounding = 10;
    style.TabRounding = 7;
    style.ScrollbarRounding = 8;
    style.WindowPadding = {22, 20};
    style.FramePadding = {13, 9};
    style.ItemSpacing = {11, 11};
    style.ItemInnerSpacing = {9, 7};
    style.CellPadding = {12, 10};
    style.WindowBorderSize = 0;
    style.ChildBorderSize = 0;
    style.FrameBorderSize = 0;
    style.PopupBorderSize = 0;
    style.TabBarBorderSize = 0;
    style.GrabRounding = 4;
    style.ScrollbarSize = 10;
    io.ConfigWindowsMoveFromTitleBarOnly = true;
    io.ConfigWindowsResizeFromEdges = true;
    style.Colors[ImGuiCol_WindowBg] = {0.102f,0.102f,0.110f,1.000f};
    style.Colors[ImGuiCol_ChildBg] = {0.068f,0.068f,0.075f,1.000f};
    style.Colors[ImGuiCol_PopupBg] = {0.095f,0.095f,0.105f,1.000f};
    style.Colors[ImGuiCol_FrameBg] = {0.055f,0.055f,0.062f,1.000f};
    style.Colors[ImGuiCol_FrameBgHovered] = {0.160f,0.160f,0.175f,1.000f};
    style.Colors[ImGuiCol_FrameBgActive] = {0.200f,0.200f,0.215f,1.000f};
    style.Colors[ImGuiCol_Button] = {0.035f,0.035f,0.042f,1.000f};
    style.Colors[ImGuiCol_ButtonHovered] = {0.145f,0.145f,0.160f,1.000f};
    style.Colors[ImGuiCol_ButtonActive] = {0.015f,0.015f,0.020f,1.000f};
    style.Colors[ImGuiCol_Header] = {0.040f,0.040f,0.047f,1.000f};
    style.Colors[ImGuiCol_HeaderHovered] = {0.160f,0.160f,0.175f,1.000f};
    style.Colors[ImGuiCol_HeaderActive] = {0.025f,0.025f,0.030f,1.000f};
    style.Colors[ImGuiCol_CheckMark] = {0.840f,0.840f,0.860f,1.000f};
    style.Colors[ImGuiCol_SliderGrab] = {0.460f,0.460f,0.490f,1.000f};
    style.Colors[ImGuiCol_SliderGrabActive] = {0.780f,0.780f,0.800f,1.000f};
    style.Colors[ImGuiCol_TableHeaderBg] = {0.035f,0.035f,0.042f,1.000f};
    style.Colors[ImGuiCol_TableRowBgAlt] = {1.000f,1.000f,1.000f,0.025f};
    style.Colors[ImGuiCol_Text] = {0.930f,0.930f,0.940f,1.000f};
    style.Colors[ImGuiCol_TextDisabled] = {0.640f,0.640f,0.680f,1.000f};
    style.Colors[ImGuiCol_Separator] = {0.400f,0.400f,0.440f,0.300f};
    style.Colors[ImGuiCol_ScrollbarBg] = {0.000f,0.000f,0.000f,0.000f};
    style.Colors[ImGuiCol_ScrollbarGrab] = {0.400f,0.400f,0.440f,0.550f};
    style.Colors[ImGuiCol_ScrollbarGrabHovered] = {0.550f,0.550f,0.600f,0.800f};
    style.Colors[ImGuiCol_ScrollbarGrabActive] = {0.650f,0.650f,0.700f,1.000f};
    style.Colors[ImGuiCol_ResizeGrip] = {0.600f,0.600f,0.650f,0.180f};
    style.Colors[ImGuiCol_ResizeGripHovered] = {0.600f,0.600f,0.650f,0.600f};
    style.Colors[ImGuiCol_ResizeGripActive] = {0.600f,0.600f,0.650f,1.000f};
    style.Colors[ImGuiCol_NavHighlight] = {0.780f,0.780f,0.830f,1.000f};
    style.Colors[ImGuiCol_TextSelectedBg] = {0.480f,0.480f,0.540f,0.450f};
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

static void sectionHeading(const char *title, const char *description = nullptr) {
    auto position = ImGui::GetCursorScreenPos();
    ImGui::GetWindowDrawList()->AddText(headingFont,22 * uiScale,position,ImGui::GetColorU32(ImGuiCol_Text),title);
    ImGui::Dummy({0,28 * uiScale});
    if (description) { ImGui::PushStyleColor(ImGuiCol_Text,ImGui::GetStyleColorVec4(ImGuiCol_TextDisabled)); ImGui::TextWrapped("%s",description); ImGui::PopStyleColor(); }
    ImGui::Dummy({0,5 * uiScale});
}

static bool compactButton(const char *name) {
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, {ImGui::GetStyle().FramePadding.x,4 * uiScale});
    bool pressed = ImGui::Button(name);
    ImGui::PopStyleVar();
    return pressed;
}

static bool beginDataTable(const char *name, int columns, ImGuiTableFlags flags, ImVec2 size = {0,0}) {
    auto position = ImGui::GetCursorScreenPos();
    float width = size.x > 0 ? size.x : ImGui::GetContentRegionAvail().x;
    float headerHeight = ImGui::GetTextLineHeight() + ImGui::GetStyle().CellPadding.y * 2;
    ImGui::GetWindowDrawList()->AddRectFilled(position, {position.x + width,position.y + headerHeight}, ImGui::GetColorU32(ImGuiCol_TableHeaderBg), 8 * uiScale);
    ImGui::PushStyleColor(ImGuiCol_ChildBg, {0,0,0,0});
    bool visible = ImGui::BeginTable(name, columns, flags | ImGuiTableFlags_PadOuterX, size);
    ImGui::PopStyleColor();
    if (visible && (flags & ImGuiTableFlags_ScrollY)) ImGui::TableSetupScrollFreeze(0, 1);
    return visible;
}

static void dataTableHeadersRow() {
    ImGui::PushStyleColor(ImGuiCol_TableHeaderBg, {0,0,0,0});
    ImGui::PushStyleColor(ImGuiCol_HeaderHovered, {0,0,0,0});
    ImGui::PushStyleColor(ImGuiCol_HeaderActive, {0,0,0,0});
    ImGui::TableHeadersRow();
    ImGui::PopStyleColor(3);
}

static void recoveryPanel(Json &command) {
    sectionHeading("Seed recovery", "Collect loaded chunks, then recover the seed. Evidence is selected automatically.");
    bool running = state.value("recovering", false) || state.value("patternRunning", false) || state.value("netherPatternRunning", false) || state.value("paperRunning", false);
    auto observations = state.value("observations", Json::array());
    if (!state.value("connected", false)) ImGui::TextDisabled("Join a world to collect observations.");
    std::string generationVersion = state.value("generationVersion", state.value("gameVersion", "26.2"));
    ImGui::SetNextItemWidth(198 * uiScale);
    if (ImGui::BeginCombo("World generation version", generationVersion.c_str())) {
        for (const char *version : {"26.2", "26.3"}) {
            bool selected = generationVersion == version;
            if (ImGui::Selectable(version, selected)) command = {{"action", "generationVersion"}, {"version", version}};
            if (selected) ImGui::SetItemDefaultFocus();
        }
        ImGui::EndCombo();
    }
    ImGui::TextDisabled("Client %s    ·    Server reports %s", state.value("gameVersion", "").c_str(), state.value("serverVersion", "Not reported").c_str());
    auto protocolTarget = state.value("protocolTargetVersion", "");
    if (!protocolTarget.empty()) ImGui::TextDisabled("Protocol target: %s", protocolTarget.c_str());
    ImGui::TextDisabled("Choose the version that generated these chunks; protocol translation may hide it.");
    auto generationWarning = state.value("generationWarning", "");
    if (!generationWarning.empty()) ImGui::TextWrapped("%s", generationWarning.c_str());
    bool versionMatches = generationVersion == state.value("gameVersion", "");
    if (!versionMatches) ImGui::TextWrapped("Use the Seedy jar for this world generation version before recovering from structures or locating them.");
    ImGui::Separator();
    if (ImGui::Button(state.value("collecting", true) ? "Pause collection" : "Resume collection")) command = {{"action", "collect"}};
    ImGui::SameLine();
    bool structureReady = state.value("structureEvidenceCount", 0) >= 5 && state.value("evidence", 0.0) >= 40 && versionMatches;
    bool evidenceReady = state.value("patternCanTry", false) || state.value("netherPatternCanTry", false) || structureReady;
    bool canStart = state.value("recoveryCanStart", evidenceReady && state.value("connected", false) && !state.value("verified", false));
    ImGui::BeginDisabled(!running && !canStart);
    if (ImGui::Button(running ? "Stop search" : "Recover seed")) command = {{"action", running ? "stopRecovery" : "recover"}};
    ImGui::EndDisabled();
    ImGui::SameLine();
    ImGui::BeginDisabled(!state.value("connected", false));
    if (ImGui::Button("Rescan")) command = {{"action", "rescan"}};
    ImGui::EndDisabled();
    if (ImGui::IsItemHovered(ImGuiHoveredFlags_AllowWhenDisabled)) ImGui::SetTooltip("Check loaded chunks again, including structures that loaded in pieces.");
    auto recoverySource = state.value("recoverySource", "");
    if (!running && !recoverySource.empty()) ImGui::TextDisabled("Uses collected %s", recoverySource.c_str());
    ImGui::Dummy({0,10 * uiScale});
    ImGui::Separator();
    ImGui::Dummy({0,5 * uiScale});
    ImGui::TextUnformatted("Loaded block patterns");
    ImGui::SameLine();
    ImGui::TextDisabled("%d chunks  ·  %d raw iron/copper blocks in %d chunks  ·  %d ore candidates", state.value("patternChunks", 0), state.value("patternRawBlocks", 0), state.value("patternRawChunks", 0), state.value("patternOreCandidates", 0));
    if (state.value("patternDataConflict", false)) {
        ImGui::TextWrapped("%d raw blocks in %d chunks fall outside vanilla vein heights. This sample cannot be trusted for block recovery.", state.value("patternConflictingRawBlocks", 0), state.value("patternConflictingRawChunks", 0));
    }
    if (state.value("patternRunning", false)) {
        ImGui::TextDisabled("Pass %d / %d  ·  %s", state.value("patternPass", 1), state.value("patternPassCount", 4), state.value("patternPassSample", "").c_str());
        auto previousPass = state.value("patternPreviousPass", "");
        if (state.value("patternPass", 1) > 1 && !previousPass.empty()) ImGui::TextWrapped("Previous pass: %s", previousPass.c_str());
    }
    ImGui::TextWrapped("%s", state.value("patternStatus", "Waiting for a world").c_str());
    bool blockRunning = state.value("patternRunning", false);
    double blockProgress = state.value("patternProgress", -1.0);
    if (state.value("connected", false) && !state.value("verified", false) && !blockRunning && state.value("patternPass", 0) == 0) {
        double collection = state.value("patternCollectionProgress", 0.0);
        ImGui::TextDisabled("Nearby evidence  %.0f%%", collection * 100);
        ImGui::ProgressBar(static_cast<float>(collection), {-1, 7 * uiScale}, "");
    }
    if (blockRunning && blockProgress >= 0) {
        ImGui::TextDisabled("Seed candidates  %.1f%%", blockProgress * 100);
        ImGui::ProgressBar(static_cast<float>(blockProgress), {-1, 7 * uiScale}, "");
    }
    if (blockRunning) {
        long elapsed = state.value("patternElapsedSeconds", 0L);
        long eta = state.value("patternEtaSeconds", -1L);
        if (eta >= 0) ImGui::TextDisabled("%s elapsed  ·  estimated full scan: %s left", timeLabel(elapsed).c_str(), timeLabel(eta).c_str());
        else ImGui::TextDisabled("%s elapsed", timeLabel(elapsed).c_str());
        ImGui::TextDisabled("Stage %d / 8  ·  %s in this stage", state.value("patternStage", 1), timeLabel(state.value("patternStageSeconds", 0L)).c_str());
        ImGui::TextDisabled("Random-state limit: 3 minutes per pass  ·  Total search limit: 10 minutes");
        if (state.value("patternRejectedStates", 0) > 0) ImGui::TextDisabled("%d random states rejected", state.value("patternRejectedStates", 0));
    } else if (state.value("patternPass", 0) > 0) {
        int passes = state.value("patternPass", 0);
        ImGui::TextDisabled("Last check: %s  ·  %d %s", timeLabel(state.value("patternElapsedSeconds", 0L)).c_str(), passes, passes == 1 ? "pass" : "passes");
    }
    ImGui::BeginDisabled(running || !state.value("patternCanTry", false));
    if (ImGui::Button("Try current data")) command = {{"action", "tryBlockRecovery"}};
    ImGui::EndDisabled();
    if (blockRunning) {
        ImGui::SameLine();
        if (ImGui::Button("Stop block check")) command = {{"action", "stopBlockRecovery"}};
    }
    ImGui::Dummy({0,10 * uiScale});
    ImGui::Separator();
    ImGui::Dummy({0,5 * uiScale});
    ImGui::TextUnformatted("Nether bedrock");
    ImGui::SameLine();
    ImGui::TextDisabled("%d chunks  ·  %d floor  ·  %d roof", state.value("netherPatternChunks", 0), state.value("netherPatternFloor", 0), state.value("netherPatternRoof", 0));
    ImGui::TextWrapped("%s", state.value("netherPatternStatus", "Enter the Nether to scan bedrock").c_str());
    bool netherRunning = state.value("netherPatternRunning", false);
    double netherProgress = state.value("netherPatternProgress", -1.0);
    if (netherRunning) {
        ImGui::TextDisabled("%s elapsed", timeLabel(state.value("netherPatternElapsedSeconds", 0L)).c_str());
        if (netherProgress >= 0) ImGui::ProgressBar(static_cast<float>(netherProgress), {-1, 7 * uiScale}, "");
        if (ImGui::Button("Stop Nether check")) command = {{"action", "stopNetherRecovery"}};
    } else {
        ImGui::BeginDisabled(running || !state.value("netherPatternCanTry", false));
        if (ImGui::Button("Check Nether data")) command = {{"action", "tryNetherRecovery"}};
        ImGui::EndDisabled();
    }
    ImGui::Dummy({0,10 * uiScale});
    ImGui::Separator();
    ImGui::Dummy({0,5 * uiScale});
    ImGui::TextUnformatted("Structure observations");
    ImGui::TextDisabled("%d of %zu selected    %.1f evidence bits", state.value("selectedObservations", 0), observations.size(), state.value("evidence", 0.0));
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
    if (state.value("recovering", false)) ImGui::ProgressBar(static_cast<float>(state.value("progress", 0.0)), {-1, 5 * uiScale}, "");
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, {12 * uiScale,12 * uiScale});
    if (ImGui::BeginChild("observations", {0, 169 * uiScale}, ImGuiChildFlags_AlwaysUseWindowPadding)) {
        if (observations.empty()) ImGui::TextWrapped("Explore shipwrecks, trial chambers, desert pyramids, or swamp huts. Detected start chunks appear here. For trial chambers, load the starting room as well as the corridors.");
        else if (beginDataTable("observation-list", 5, ImGuiTableFlags_RowBg | ImGuiTableFlags_SizingStretchProp)) {
            ImGui::TableSetupColumn("Use", ImGuiTableColumnFlags_WidthFixed, 50 * uiScale);
            ImGui::TableSetupColumn("Structure", 0, 2);
            ImGui::TableSetupColumn("Start chunk", 0, 1.4f);
            ImGui::TableSetupColumn("Source");
            ImGui::TableSetupColumn("", ImGuiTableColumnFlags_WidthFixed, ImGui::CalcTextSize("Remove").x + ImGui::GetStyle().FramePadding.x * 2);
            dataTableHeadersRow();
            for (auto &entry : observations) {
                ImGui::PushID(entry.at("id").get<std::string>().c_str());
                ImGui::TableNextRow(); ImGui::TableNextColumn();
                bool selected = entry.value("selected", true);
                if (ImGui::Checkbox("##selected", &selected)) command = {{"action", "selectObservation"}, {"id", entry.at("id")}, {"selected", selected}};
                ImGui::TableNextColumn(); ImGui::TextUnformatted(label(entry.at("structure")).c_str());
                ImGui::TableNextColumn(); ImGui::Text("%d, %d", entry.at("chunkX").get<int>(), entry.at("chunkZ").get<int>());
                ImGui::TableNextColumn(); ImGui::TextDisabled("%s", entry.at("source").get<std::string>().c_str());
                ImGui::TableNextColumn(); if (compactButton("Remove")) command = {{"action", "remove"}, {"id", entry.at("id")}};
                ImGui::PopID();
            }
            ImGui::EndTable();
        }
    }
    ImGui::EndChild();
    ImGui::PopStyleVar();
    if (ImGui::CollapsingHeader("Add observation")) {
        static int type = 0, chunks[2] = {0,0};
        ImGui::SetNextItemWidth(230 * uiScale);
        auto structure = select("Structure##observation", "recoveryTypes", type);
        ImGui::SetNextItemWidth(230 * uiScale);
        ImGui::InputInt2("Chunk X / Z", chunks);
        if (ImGui::Button("Add") && !structure.empty()) command = {{"action", "observe"}, {"structure", structure}, {"x", chunks[0]}, {"z", chunks[1]}};
        ImGui::SameLine(); ImGui::TextDisabled("Use the structure's start chunk.");
    }
    ImGui::Dummy({0,10 * uiScale});
    ImGui::Separator();
    ImGui::Dummy({0,5 * uiScale});
    ImGui::TextUnformatted("Paper placement");
    ImGui::TextWrapped("A verified world seed lets Seedy check each observed structure against Paper's default placement. Changed salts need several starts of the same structure.");
    auto checks = state.value("placementChecks", Json::array());
    if (!state.value("verified", false)) ImGui::TextDisabled("Verify the world seed first to check Paper settings.");
    else if (checks.empty()) ImGui::TextDisabled("No observed structure starts to compare yet.");
    else if (beginDataTable("paper-checks", 3, ImGuiTableFlags_RowBg | ImGuiTableFlags_SizingStretchProp)) {
        ImGui::TableSetupColumn("Structure", 0, 2);
        ImGui::TableSetupColumn("Default matches", 0, 1);
        ImGui::TableSetupColumn("Status", 0, 2);
        dataTableHeadersRow();
        for (const auto &check : checks) {
            ImGui::TableNextRow();
            ImGui::TableNextColumn(); ImGui::TextUnformatted(label(check.value("structure", "")).c_str());
            ImGui::TableNextColumn(); ImGui::Text("%d", check.value("defaultMatches", 0));
            ImGui::TableNextColumn();
            if (check.value("recoveredConflicts", 0) > 0) ImGui::TextColored({1,0.71f,0.65f,1}, "Recovered salt conflicts with a new start");
            else if (check.contains("recoveredSalt")) ImGui::Text("Salt %d", check.value("recoveredSalt", 0));
            else if (check.value("defaultConflicts", 0) > 0) ImGui::TextColored({1,0.71f,0.65f,1}, "Placement differs; check version or custom salt");
            else ImGui::TextDisabled("Default fits; salt unconfirmed");
        }
        ImGui::EndTable();
    }
    static int paperType = 0;
    ImGui::SetNextItemWidth(250 * uiScale);
    std::string paperStructure = select("Structure##paper", "paperTypes", paperType);
    bool paperRunning = state.value("paperRunning", false);
    ImGui::BeginDisabled(running || !state.value("verified", false) || paperStructure.empty() || !versionMatches);
    if (ImGui::Button("Find placement salt")) command = {{"action", "recoverPaperSalt"}, {"structure", paperStructure}};
    ImGui::EndDisabled();
    if (paperRunning) {
        ImGui::SameLine();
        if (ImGui::Button("Stop salt search")) command = {{"action", "stopPaperSalt"}};
        ImGui::ProgressBar(static_cast<float>(state.value("paperProgress", 0.0)), {-1, 7 * uiScale}, "");
    }
    ImGui::TextDisabled("%s", state.value("paperStatus", "Idle").c_str());
    ImGui::TextWrapped("Buried treasure, mineshafts, strongholds, slime chunks, and feature seeds use different rules. This search checks random-spread structure placement only.");
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
            if (compactButton("Copy chest")) command = {{"action","copy"},{"text",coordinatesOf(chest)}};
            if (!chest.contains("y") || chest["y"].is_null()) { ImGui::SameLine(); ImGui::TextDisabled("X / Z; height is resolved when loaded"); }
            auto warning = chest.value("warning", "");
            if (!warning.empty()) ImGui::TextWrapped("%s", warning.c_str());
            else if (ImGui::BeginTable("items", 2, ImGuiTableFlags_RowBg | ImGuiTableFlags_SizingStretchProp | ImGuiTableFlags_PadOuterX)) {
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
    sectionHeading("Find a location", "Search structures, biomes and chest loot in any dimension.");
    static int kind = 0, target = 0, origin[3] = {0,64,0}, radius = 32768, dimensionIndex = 0, cityFilter = 0;
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
    if (ImGui::SliderInt("Radius", &radius, 128, 1048576, "%d blocks", ImGuiSliderFlags_Logarithmic)) command = {{"action","cancelSearch"}};
    const int ranges[] = {8192,32768,131072,524288,1048576};
    const char *rangeNames[] = {"8k", "32k", "128k", "512k", "1m"};
    for (int i = 0; i < 5; i++) { if (i) ImGui::SameLine(); if (compactButton(rangeNames[i])) { radius = ranges[i]; command = {{"action","cancelSearch"}}; } }
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
                    bool remove = compactButton("Remove");
                    ImGui::PopID();
                    if (remove) { lootItems.erase(lootItems.begin() + i); lootAmounts.erase(lootAmounts.begin() + i); command = {{"action","cancelSearch"}}; break; }
                }
                ImGui::BeginDisabled(lootItems.size() >= 8);
                if (compactButton("Add item")) { int next = 0; while (std::find(lootItems.begin(),lootItems.end(),next) != lootItems.end()) next++; lootItems.push_back(next); lootAmounts.push_back(1); command = {{"action","cancelSearch"}}; }
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
    if (running) ImGui::TextDisabled("%d candidates checked   %d matches   %d blocks searched",state.value("searchChecked",0),state.value("searchFound",0),state.value("searchRange",0));
    ImGui::PushStyleColor(ImGuiCol_Text, ImGui::GetStyleColorVec4(ImGuiCol_TextDisabled));
    ImGui::TextWrapped(kind == 0 ? "Generated with this version's world rules. Paper settings can change placements; check Recovery for conflicts." : "Vanilla biomes in the selected dimension at the selected Y, sampled every 64 blocks nearby; wider searches adapt the sampling.");
    ImGui::PopStyleColor();
    auto results = state.value("results", Json::array());
    if (state.value("searchDimension", "overworld") != dimension || state.value("searchCityFilter", "any") != selectedCityFilter || state.value("searchTarget", "") != selected || state.value("searchKind", "") != (kind == 0 ? "structure" : "biome") || command.value("action", "") == "cancelSearch") results = Json::array();
    if (!results.empty()) ImGui::TextDisabled("%zu results in %s", results.size(), label(results[0].value("dimension", "overworld")).c_str());
    int detailIndex = state.value("chestDetailIndex", -1);
    if (command.value("action", "") == "cancelSearch") detailIndex = -1;
    ImGui::Dummy({0,5 * uiScale});
    if (beginDataTable("locations", 3, ImGuiTableFlags_RowBg | ImGuiTableFlags_ScrollY, {0, 180 * uiScale})) {
        ImGui::TableSetupColumn("Coordinates", ImGuiTableColumnFlags_WidthStretch);
        ImGui::TableSetupColumn("Distance", ImGuiTableColumnFlags_WidthFixed, 105 * uiScale);
        float actionsWidth = ImGui::CalcTextSize("Copy").x + ImGui::CalcTextSize("Saved").x + ImGui::GetStyle().FramePadding.x * 4 + ImGui::GetStyle().ItemSpacing.x;
        ImGui::TableSetupColumn("", ImGuiTableColumnFlags_WidthFixed, actionsWidth);
        dataTableHeadersRow();
        ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, {ImGui::GetStyle().FramePadding.x,4 * uiScale});
        for (size_t i = 0; i < results.size(); i++) {
            auto &entry = results[i];
            std::string coordinates = std::to_string(entry.at("x").get<int>()) + " " + (entry.contains("y") && !entry["y"].is_null() ? std::to_string(entry["y"].get<int>()) + " " : "") + std::to_string(entry.at("z").get<int>());
            ImGui::PushID(static_cast<int>(i));
            ImGui::TableNextRow(); ImGui::TableNextColumn();
            ImGui::AlignTextToFramePadding();
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
                if (elytra && compactButton("Copy elytra")) command = {{"action","copy"},{"text",coordinatesOf(entry["elytra"])}};
            }
            ImGui::TableNextColumn(); ImGui::AlignTextToFramePadding(); ImGui::Text("%lld blocks", entry.at("distance").get<long long>());
            ImGui::TableNextColumn(); if (compactButton("Copy")) command = {{"action","copy"},{"text",coordinates}};
            ImGui::SameLine();
            auto saved = state.value("savedLocations", Json::array());
            bool exists = std::any_of(saved.begin(), saved.end(), [&](const Json &location) { return location.value("name", "") == entry.value("name", "") && location.value("dimension", "") == entry.value("dimension", "") && location.value("x", 0) == entry.value("x", 0) && location.value("z", 0) == entry.value("z", 0) && location.value("y", Json()) == entry.value("y", Json()); });
            ImGui::BeginDisabled(exists || !state.value("connected", false));
            if (compactButton(exists ? "Saved" : "Save")) command = {{"action","pin"},{"index",i},{"generation",state.value("searchGeneration",0LL)}};
            ImGui::EndDisabled();
            if (compactButton("Track")) command = {{"action","track"},{"name",entry.value("name","")},{"x",entry.value("x",0)},{"z",entry.value("z",0)},{"dimension",entry.value("dimension","overworld")}};
            ImGui::PopID();
        }
        ImGui::PopStyleVar();
        ImGui::EndTable();
    }
    if (results.empty() && state.value("searchStatus", "Idle") == "Complete") ImGui::TextWrapped("No matches in this area. Adjust the filters or search radius.");
    if (detailIndex >= 0 && detailIndex < static_cast<int>(results.size()) && state.contains("chestDetails")) {
        ImGui::Dummy({0,8 * uiScale});
        if (compactButton("Close chest details")) { detailIndex = -1; command = {{"action","closeChests"}}; }
        if (detailIndex >= 0) chestDetails(state["chestDetails"], command);
    }
}

static void savedPanel(Json &command) {
    sectionHeading("Saved locations", "Places you want to return to.");
    auto locations = state.value("savedLocations", Json::array());
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
        if (compactButton("Copy")) command = {{"action","copy"},{"text",coordinates}};
        ImGui::SameLine();
        if (compactButton("Remove")) command = {{"action","unpin"},{"id",entry.at("id")}};
        ImGui::PopID();
    }
}

#include "ui_settings.h"
#include "seed_map.h"
#include "gameplay_hud.h"

static void drawOutlines(const std::vector<double> &lines) {
    auto draw = ImGui::GetBackgroundDrawList();
    for (int i = 0; i + 4 < static_cast<int>(lines.size()); i += 5) {
        if (lines[i+4] != 2) continue;
        ImVec2 a{static_cast<float>(lines[i]),static_cast<float>(lines[i+1])}, b{static_cast<float>(lines[i+2]),static_cast<float>(lines[i+3])};
        draw->AddLine(a, b, IM_COL32(205,168,255,255), 1.5f * uiScale);
    }
}

static Json drawPanel(int width, int height) {
    loadPreferences();
    ImGui::SetNextWindowSize({std::min(preferences.width * uiScale, width - 24.0f), std::min(preferences.height * uiScale, height - 24.0f)}, ImGuiCond_FirstUseEver);
    if (preferences.x < 0 || preferences.y < 0) ImGui::SetNextWindowPos({width / 2.0f,height / 2.0f}, ImGuiCond_FirstUseEver,{0.5f,0.5f});
    else ImGui::SetNextWindowPos({preferences.x * uiScale,preferences.y * uiScale},ImGuiCond_FirstUseEver);
    if (preferences.maximized) { ImGui::SetNextWindowPos({6,6}); ImGui::SetNextWindowSize({width - 12.0f,height - 12.0f}); }
    else if (restoreWindow) {
        ImGui::SetNextWindowSize({preferences.width * uiScale,preferences.height * uiScale});
        ImGui::SetNextWindowPos({std::max(0.0f,preferences.x) * uiScale,std::max(0.0f,preferences.y) * uiScale});
        restoreWindow = false;
    }
    if (requestedWindowSize.x > 0) { ImGui::SetNextWindowSize({requestedWindowSize.x * uiScale,requestedWindowSize.y * uiScale}); requestedWindowSize = {-1,-1}; }
    ImGui::SetNextWindowSizeConstraints({std::min(520 * uiScale, width - 24.0f),std::min(420 * uiScale,height - 24.0f)}, {static_cast<float>(width - 12),static_cast<float>(height - 12)});
    ImGui::SetNextWindowBgAlpha(preferences.opacity);
    Json command = Json::object();
    bool resetContentScroll = false;
    bool stopMap = false;
    if (ImGui::Begin("Seedy", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoSavedSettings | (preferences.maximized ? ImGuiWindowFlags_NoResize : ImGuiWindowFlags_None))) {
        auto position = ImGui::GetWindowPos();
        auto size = ImGui::GetWindowSize();
        ImVec2 visiblePosition = {std::clamp(position.x, 0.0f, std::max(0.0f, width - size.x)),std::clamp(position.y, 0.0f, std::max(0.0f, height - size.y))};
        if (position.x != visiblePosition.x || position.y != visiblePosition.y) ImGui::SetWindowPos(visiblePosition);
        if (!preferences.maximized) { preferences.width = size.x / uiScale; preferences.height = size.y / uiScale; preferences.x = visiblePosition.x / uiScale; preferences.y = visiblePosition.y / uiScale; }
        auto gridDraw = ImGui::GetWindowDrawList();
        auto gridOrigin = ImGui::GetWindowPos();
        ImVec2 gridEnd{gridOrigin.x + size.x - 12 * uiScale,gridOrigin.y + size.y - 12 * uiScale};
        ImVec2 gridStart{gridOrigin.x + 12 * uiScale,gridOrigin.y + 12 * uiScale};
        float gridSpacing = 32 * uiScale;
        ImU32 gridColor = IM_COL32(255,255,255,static_cast<int>(7 * preferences.opacity));
        gridDraw->PushClipRect(gridStart,gridEnd,true);
        for (float x = gridOrigin.x + gridSpacing; x < gridEnd.x; x += gridSpacing) gridDraw->AddLine({x,gridStart.y},{x,gridEnd.y},gridColor);
        for (float y = gridOrigin.y + gridSpacing; y < gridEnd.y; y += gridSpacing) gridDraw->AddLine({gridStart.x,y},{gridEnd.x,y},gridColor);
        gridDraw->PopClipRect();
        ImVec2 header = ImGui::GetCursorScreenPos();
        float headerWidth = ImGui::GetContentRegionAvail().x - 136 * uiScale;
        ImGui::InvisibleButton("Move panel", {headerWidth,32 * uiScale});
        if (ImGui::IsItemHovered() && ImGui::IsMouseDoubleClicked(ImGuiMouseButton_Left)) toggleMaximized();
        if (!preferences.maximized && ImGui::IsItemActive() && ImGui::IsMouseDragging(ImGuiMouseButton_Left, 0)) {
            auto position = ImGui::GetWindowPos();
            auto size = ImGui::GetWindowSize();
            auto delta = ImGui::GetIO().MouseDelta;
            ImGui::SetWindowPos({std::clamp(position.x + delta.x, 0.0f, std::max(0.0f, width - size.x)),std::clamp(position.y + delta.y, 0.0f, std::max(0.0f, height - size.y))});
        }
        if (ImGui::IsItemHovered()) ImGui::SetMouseCursor(ImGuiMouseCursor_ResizeAll);
        auto draw = ImGui::GetWindowDrawList();
        draw->AddText(headingFont, 22 * uiScale, {header.x,header.y + 3 * uiScale}, IM_COL32(240,240,243,255), "Seedy");
        std::string subtitle = state.value("connected", false) ? label(state.value("dimension", "overworld")) + "   " + state.value("gameVersion", "") : "No world connected";
        draw->AddText({header.x + 79 * uiScale,header.y + 7 * uiScale}, IM_COL32(168,168,180,255), subtitle.c_str());
        ImGui::SameLine();
        if (ImGui::Button(preferences.maximized ? "Restore##window" : "Expand##window", {84 * uiScale,30 * uiScale})) toggleMaximized();
        ImGui::SameLine();
        if (ImGui::Button("X##close", {32 * uiScale,30 * uiScale})) command = {{"action","close"}};
        const char *sections[] = {"Recovery##nav", "Locate##nav", "Seed##nav", "Saved##nav", "World##nav", "Map##nav", "Settings##nav"};
        int columns = ImGui::GetContentRegionAvail().x < 7 * 96 * uiScale + 6 * ImGui::GetStyle().ItemSpacing.x ? 4 : 7;
        float navWidth = (ImGui::GetContentRegionAvail().x - (columns - 1) * ImGui::GetStyle().ItemSpacing.x) / columns;
        const int navigationOrder[] = {5,1,0,2,3,4,6};
        for (int slot = 0; slot < 7; slot++) {
            int i = navigationOrder[slot];
            if (slot % columns) ImGui::SameLine();
            ImGui::PushStyleColor(ImGuiCol_Button, i == activeSection ? ImVec4{0.015f,0.015f,0.02f,1} : ImVec4{0,0,0,0});
            ImGui::PushStyleColor(ImGuiCol_Text,ImGui::GetStyleColorVec4(i == activeSection ? ImGuiCol_Text : ImGuiCol_TextDisabled));
            if (ImGui::Button(sections[i], {navWidth,36 * uiScale})) {
                if (activeSection == 5 && i != 5) { stopMap = true; command = {{"action","mapStop"}}; mapView.sentKey.clear(); }
                activeSection = i; resetContentScroll = true;
            }
            if (i == activeSection) {
                auto a = ImGui::GetItemRectMin(), b = ImGui::GetItemRectMax();
                draw->AddRectFilled({a.x + 16 * uiScale,b.y - 2 * uiScale},{b.x - 16 * uiScale,b.y},IM_COL32(112,112,122,255),uiScale);
            }
            ImGui::PopStyleColor(2);
        }
        ImGui::Dummy({0,3 * uiScale});
        ImGui::PushStyleColor(ImGuiCol_ChildBg, {0,0,0,0});
        ImGui::BeginChild("panel-content", {0,-28 * uiScale}, ImGuiChildFlags_None, activeSection == 5 ? ImGuiWindowFlags_NoScrollWithMouse : ImGuiWindowFlags_None);
        if (resetContentScroll) ImGui::SetScrollY(0);
        if (activeSection == 0) recoveryPanel(command);
        if (activeSection == 1) locatePanel(command);
        if (activeSection == 5) mapPanel(command);
        if (activeSection == 6) settingsPanel();
        if (activeSection == 2) {
            sectionHeading("World seed", "Use the seed that generated this world.");
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
            sectionHeading("Loaded chunks", "Highlight useful objects while you explore.");
            bool loot = state.value("lootOutlines", true);
            if (ImGui::Checkbox("Matching loot chest outlines", &loot)) command = {{"action","outlines"},{"loot",loot}};
            ImGui::TextWrapped("Purple outlines mark matching loot chests from the current search within 512 blocks.");
            ImGui::TextWrapped("Mob spawners and buried treasure candidates are listed here without outlines. Treasure candidates can include placed chests.");
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
    if (state.value("clearHudWaypoint",false)) { state.erase("clearHudWaypoint"); command = {{"action","clearWaypoint"}}; }
    if (stopMap) command["stopMap"] = true;
    persistPreferences(command);
    return command;
}

static bool panelWasVisible = false;

static Json drawOverlayFrame(int width, int height, bool panel, bool hud) {
    loadPreferences();
    Json command = panel ? drawPanel(width,height) : Json::object();
    if (hud || panel && hudEditing) drawGameplayHud(width,height,panel && hudEditing);
    if (!panel && panelWasVisible && preferencesLoaded) persistPreferences(command,true);
    panelWasVisible = panel;
    return command;
}

#ifndef SEEDY_HEADLESS_TEST
extern "C" JNIEXPORT jstring JNICALL Java_dev_seedy_NativeGui_frame(JNIEnv *env, jclass type, jstring snapshot, jint width, jint height, jfloat density, jdoubleArray events, jlongArray graphics, jdoubleArray outlineLines, jboolean panel, jboolean hud) {
    try {
        frameEnvironment = env;
        if (!context) initialize(env, type, density);
        ImGui::SetCurrentContext(context);
        if (snapshot) {
            std::string incoming = javaString(env, snapshot);
            if (incoming != previousState) { state = Json::parse(incoming); previousState = std::move(incoming); }
        }
        auto &io = ImGui::GetIO();
        io.DisplaySize = {static_cast<float>(width), static_cast<float>(height)};
        auto now = std::chrono::steady_clock::now();
        io.DeltaTime = std::clamp(std::chrono::duration<float>(now - previousFrame).count(), 0.001f, 0.1f);
        previousFrame = now;
        int count = env->GetArrayLength(events);
        std::vector<double> input(count);
        if (count) env->GetDoubleArrayRegion(events, 0, count, input.data());
        const ImGuiKey keys[] = {ImGuiKey_None,ImGuiKey_Tab,ImGuiKey_LeftArrow,ImGuiKey_RightArrow,ImGuiKey_UpArrow,ImGuiKey_DownArrow,ImGuiKey_Backspace,ImGuiKey_Delete,ImGuiKey_Enter,ImGuiKey_Home,ImGuiKey_End,ImGuiMod_Ctrl,ImGuiMod_Shift,ImGuiKey_A,ImGuiKey_C,ImGuiKey_V,ImGuiKey_X,ImGuiKey_Z,ImGuiMod_Super,ImGuiMod_Alt,ImGuiKey_Y};
        for (int i = 0; i + 3 < count; i += 4) {
            int kind = static_cast<int>(input[i]);
            if (kind == 0) io.AddMousePosEvent(static_cast<float>(input[i+1]),static_cast<float>(input[i+2]));
            if (kind == 1 && input[i+1] >= 0 && input[i+1] < 5) io.AddMouseButtonEvent(static_cast<int>(input[i+1]),input[i+2] != 0);
            if (kind == 2) io.AddMouseWheelEvent(static_cast<float>(input[i+1]),static_cast<float>(input[i+2]));
            if (kind == 3 && input[i+1] > 0 && input[i+1] < 21) io.AddKeyEvent(keys[static_cast<int>(input[i+1])], input[i+2] != 0);
            if (kind == 4) io.AddInputCharacter(static_cast<unsigned int>(input[i+1]));
            if (kind == 5) { io.AddFocusEvent(input[i+1] != 0); mapView.sentKey.clear(); }
        }
        jlong handles[10]{};
        int handleCount = env->GetArrayLength(graphics);
        if (handleCount != 10 && handleCount != 3) throw std::runtime_error("Invalid renderer context");
        env->GetLongArrayRegion(graphics, 0, handleCount, handles);
        if (handleCount == 3) {
#ifdef __APPLE__
            VulkanRenderer::shutdown();
            MetalRenderer::prepare(handles);
#else
            throw std::runtime_error("The mcopt Metal overlay requires macOS");
#endif
        } else {
#ifdef __APPLE__
            MetalRenderer::shutdown();
#endif
            VulkanRenderer::prepare(handles);
            ImGui_ImplVulkan_NewFrame();
        }
        ImGui::NewFrame();
        int lineCount = env->GetArrayLength(outlineLines);
        std::vector<double> lines(lineCount);
        if (lineCount) env->GetDoubleArrayRegion(outlineLines, 0, lineCount, lines.data());
        drawOutlines(lines);
        Json command = drawOverlayFrame(width,height,panel,hud);
        ImGui::Render();
        if (handleCount == 3) {
#ifdef __APPLE__
            MetalRenderer::render(ImGui::GetDrawData(),width,height);
#endif
        } else VulkanRenderer::render(ImGui::GetDrawData(), handles, width, height);
        return command.empty() ? nullptr : env->NewStringUTF(command.dump().c_str());
    } catch (const std::exception &e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_dev_seedy_NativeGui_dispose(JNIEnv *env, jclass) {
    if (!context) return;
    ImGui::SetCurrentContext(context);
    VulkanRenderer::shutdown();
#ifdef __APPLE__
    MetalRenderer::shutdown();
#endif
    ImGui::DestroyContext(context);
    context = nullptr;
    if (bridgeClass) env->DeleteGlobalRef(bridgeClass);
    bridgeClass = nullptr;
}

#endif

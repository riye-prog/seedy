#include <cstdio>

static std::string hudDistance(double distance) {
    if (distance < 1000) return std::to_string(static_cast<int>(std::round(distance))) + " blocks";
    char buffer[32]; std::snprintf(buffer,sizeof(buffer),"%.1f km",distance / 1000);
    return buffer;
}

static void hudSettings() {
    ImGui::Checkbox("Enable overlay (F9)", &preferences.hudEnabled);
    ImGui::TextDisabled("The key can be changed in Minecraft controls.");
    ImGui::SetNextItemWidth(240 * uiScale);
    ImGui::SliderFloat("Overlay size", &preferences.hudScale,0.7f,1.6f,"%.2fx");
    ImGui::SetNextItemWidth(240 * uiScale);
    ImGui::SliderFloat("Overlay opacity", &preferences.hudOpacity,0.2f,1,"%.2f");
    const char *anchors[] = {"Top left", "Top right", "Bottom left", "Bottom right", "Custom position"};
    ImGui::SetNextItemWidth(240 * uiScale);
    ImGui::Combo("Position", &preferences.hudAnchor,anchors,5);
    if (ImGui::Button(hudEditing ? "Finish moving overlay" : "Move overlay")) { hudEditing = !hudEditing; preferences.hudEnabled = true; }
    ImGui::Checkbox("Coordinates and facing", &preferences.hudCoordinates);
    ImGui::Checkbox("Current biome", &preferences.hudBiome);
    ImGui::Checkbox("Dimension", &preferences.hudDimension);
    ImGui::Checkbox("Frame rate", &preferences.hudFps);
    ImGui::Checkbox("Search and recovery progress", &preferences.hudRecovery);
    ImGui::Checkbox("Collected evidence", &preferences.hudEvidence);
    ImGui::Checkbox("Tracked location", &preferences.hudWaypoint);
    ImGui::Checkbox("Use nearest search result automatically", &preferences.hudAutoTarget);
    ImGui::Checkbox("Seed value", &preferences.hudSeed);
    if (state.contains("waypoint") && ImGui::Button("Clear tracked location")) state["clearHudWaypoint"] = true;
    ImGui::TextWrapped("Use Track on a map selection or search result to keep its distance and direction on screen.");
}

static std::string hudLabel(const std::string &value) {
    auto text = label(value);
    bool first = true;
    for (char &character : text) {
        if (first) character = static_cast<char>(std::toupper(static_cast<unsigned char>(character)));
        first = character == ' ';
    }
    return text;
}

static void drawGameplayHud(int width, int height, bool interactive) {
    if (!preferences.hudEnabled || !state.value("connected",false)) return;
    float scale = uiScale * preferences.hudScale;
    static ImVec2 previousSize{300,180};
    float left = 18 * uiScale, top = 18 * uiScale;
    if (preferences.hudAnchor == 1 || preferences.hudAnchor == 3) left = std::max(0.0f,width - previousSize.x - 18 * uiScale);
    if (preferences.hudAnchor == 2 || preferences.hudAnchor == 3) top = std::max(0.0f,height - previousSize.y - 18 * uiScale - 48 * uiScale);
    if (preferences.hudAnchor == 4) { left = preferences.hudX * std::max(0.0f,width - previousSize.x); top = preferences.hudY * std::max(0.0f,height - previousSize.y); }
    ImGui::SetNextWindowPos({left,top});
    ImGui::SetNextWindowSize({300 * scale,0});
    ImGui::SetNextWindowBgAlpha(preferences.hudOpacity);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,{16 * scale,13 * scale});
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding,12 * scale);
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing,{8 * scale,7 * scale});
    ImGui::PushStyleColor(ImGuiCol_WindowBg,{0.035f,0.035f,0.042f,1});
    auto flags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoFocusOnAppearing;
    if (!interactive) flags |= ImGuiWindowFlags_NoInputs;
    if (ImGui::Begin("Seedy gameplay overlay",nullptr,flags)) {
        ImGui::SetWindowFontScale(preferences.hudScale);
        ImVec2 header = ImGui::GetCursorScreenPos();
        ImGui::InvisibleButton("Move gameplay overlay",{ImGui::GetContentRegionAvail().x,20 * scale});
        if (interactive && ImGui::IsItemActive() && ImGui::IsMouseDragging(ImGuiMouseButton_Left,0)) {
            auto pos = ImGui::GetWindowPos(), size = ImGui::GetWindowSize(), delta = ImGui::GetIO().MouseDelta;
            pos.x = std::clamp(pos.x + delta.x,0.0f,std::max(0.0f,width - size.x));
            pos.y = std::clamp(pos.y + delta.y,0.0f,std::max(0.0f,height - size.y));
            preferences.hudAnchor = 4;
            preferences.hudX = pos.x / std::max(1.0f,width - size.x); preferences.hudY = pos.y / std::max(1.0f,height - size.y);
            ImGui::SetWindowPos(pos);
        }
        auto draw = ImGui::GetWindowDrawList();
        draw->AddText(header,IM_COL32(234,234,240,255),interactive ? "Seedy   /   Drag to move" : "Seedy");
        std::string status = state.value("verified",false) ? "Verified" : state.value("seed","").empty() ? "No Seed" : "Manual Seed";
        if (!interactive) {
            float at = ImGui::GetWindowPos().x + ImGui::GetWindowSize().x - 16 * scale - ImGui::CalcTextSize(status.c_str()).x;
            draw->AddCircleFilled({at - 9 * scale,header.y + 8 * scale},2.5f * scale,state.value("verified",false) ? IM_COL32(155,193,175,255) : IM_COL32(159,159,170,255),12);
            draw->AddText({at,header.y},IM_COL32(170,170,181,255),status.c_str());
        }
        ImGui::Separator();
        auto row = [&](const char *name, std::string value) {
            float widthAvailable = ImGui::GetContentRegionAvail().x;
            float valueWidth = widthAvailable - 82 * scale;
            while (value.size() > 3 && ImGui::CalcTextSize(value.c_str()).x > valueWidth) value.erase(value.size() - 1);
            ImGui::TextDisabled("%s",name); ImGui::SameLine();
            ImGui::SetCursorPosX(ImGui::GetWindowSize().x - 16 * scale - ImGui::CalcTextSize(value.c_str()).x);
            ImGui::TextUnformatted(value.c_str());
        };
        if (preferences.hudCoordinates) {
            row("Position",std::to_string(state.value("x",0)) + "  " + std::to_string(state.value("y",0)) + "  " + std::to_string(state.value("z",0)));
            double yaw = std::remainder(state.value("yaw",0.0),360.0);
            const char *directions[] = {"South", "West", "North", "East"};
            int direction = (static_cast<int>(std::floor(yaw / 90 + 0.5)) % 4 + 4) % 4;
            row("Facing",directions[direction]);
        }
        if (preferences.hudBiome) row("Biome",hudLabel(state.value("biome","Unknown")));
        if (preferences.hudDimension) row("Dimension",hudLabel(state.value("dimension","overworld")));
        if (preferences.hudFps) row("Frame Rate",std::to_string(state.value("fps",0)) + " FPS");
        if (preferences.hudSeed && !state.value("seed","").empty()) { ImGui::TextDisabled("Seed"); ImGui::TextUnformatted(state.value("seed","").c_str()); }
        bool locating = state.value("searchStatus", "") == "Searching";
        bool recovering = locating || state.value("patternRunning",false) || state.value("netherPatternRunning",false) || state.value("paperRunning",false) || state.value("recovering",false);
        if (preferences.hudRecovery && recovering) {
            ImGui::Separator();
            std::string phase = locating ? "Finding locations" : state.value("patternRunning",false) ? state.value("patternStatus","") : state.value("netherPatternRunning",false) ? state.value("netherPatternStatus","") : state.value("paperRunning",false) ? state.value("paperStatus","") : state.value("recoveryStatus","");
            ImGui::PushTextWrapPos(ImGui::GetCursorPosX() + 267 * scale); ImGui::TextUnformatted(phase.c_str()); ImGui::PopTextWrapPos();
            float fraction = state.value("patternRunning",false) ? state.value("patternProgress",-1.0f) : state.value("netherPatternRunning",false) ? state.value("netherPatternProgress",0.0f) : state.value("paperRunning",false) ? state.value("paperProgress",0.0f) : state.value("progress",0.0f);
            if (locating) row("Checked",std::to_string(state.value("searchChecked",0)) + " candidates");
            if (fraction >= 0 && !locating) ImGui::ProgressBar(fraction,{-1,4 * scale},"");
            if (state.value("patternRunning",false)) row("Elapsed",timeLabel(state.value("patternElapsedSeconds",0L)));
        }
        if (preferences.hudEvidence) {
            row("Chunks",std::to_string(state.value("patternEvidenceChunks",0)));
            row("Raw Ore",std::to_string(state.value("patternRawBlocks",0)));
        }
        if (preferences.hudWaypoint && state.contains("waypoint")) {
            const auto &target = state["waypoint"];
            ImGui::Separator();
            ImGui::PushTextWrapPos(ImGui::GetCursorPosX() + 267 * scale); ImGui::TextUnformatted(hudLabel(target.value("name","Waypoint")).c_str()); ImGui::PopTextWrapPos();
            bool sameDimension = target.value("dimension","") == state.value("dimension","");
            double dx = target.value("x",0) - state.value("x",0), dz = target.value("z",0) - state.value("z",0);
            if (sameDimension) {
                row("Distance",hudDistance(std::hypot(dx,dz)));
                double angle = std::remainder(std::atan2(-dx,dz) - state.value("yaw",0.0) * 0.017453292519943295,6.283185307179586);
                ImVec2 pos = ImGui::GetCursorScreenPos(); ImVec2 center{pos.x + 11 * scale,pos.y + 10 * scale};
                auto rotate = [&](float x, float y) { return ImVec2{center.x + static_cast<float>(x * std::cos(angle) - y * std::sin(angle)) * scale,center.y + static_cast<float>(x * std::sin(angle) + y * std::cos(angle)) * scale}; };
                draw->AddTriangleFilled(rotate(0,-8),rotate(6,6),rotate(0,3),IM_COL32(195,218,230,255));
                draw->AddTriangleFilled(rotate(0,-8),rotate(0,3),rotate(-6,6),IM_COL32(195,218,230,255));
                ImGui::Dummy({28 * scale,20 * scale}); ImGui::SameLine();
            }
            ImGui::TextDisabled("%d / %d",target.value("x",0),target.value("z",0));
            if (!sameDimension) row("Dimension",hudLabel(target.value("dimension","")));
        }
    }
    previousSize = ImGui::GetWindowSize();
    ImGui::End();
    ImGui::PopStyleColor(); ImGui::PopStyleVar(3);
}

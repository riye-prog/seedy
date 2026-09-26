struct UiPreferences {
    float width = 1040, height = 760, x = -1, y = -1, sidebarWidth = 240;
    float opacity = 1, markerSize = 4, zoomSpeed = 0.5f;
    int detail = 1, grid = 1;
    bool maximized = false, controls = true, biomeLabels = true, structureLabels = true, showPlayer = true;
};

static UiPreferences preferences;
static bool preferencesLoaded = false, restoreWindow = false;
static std::string savedPreferences, pendingPreferences;
static double preferencesChangedAt = 0;
static ImVec2 requestedWindowSize{-1,-1};

static Json preferenceValues() {
    return {{"width",preferences.width},{"height",preferences.height},{"x",preferences.x},{"y",preferences.y},{"sidebarWidth",preferences.sidebarWidth},{"opacity",preferences.opacity},{"markerSize",preferences.markerSize},{"zoomSpeed",preferences.zoomSpeed},{"detail",preferences.detail},{"grid",preferences.grid},{"maximized",preferences.maximized},{"controls",preferences.controls},{"biomeLabels",preferences.biomeLabels},{"structureLabels",preferences.structureLabels},{"showPlayer",preferences.showPlayer},{"section",activeSection}};
}

static void loadPreferences() {
    if (preferencesLoaded) return;
    preferencesLoaded = true;
    const auto settings = state.value("uiSettings", Json::object());
    preferences.width = settings.value("width",1040.0f); preferences.height = settings.value("height",760.0f);
    preferences.x = settings.value("x",-1.0f); preferences.y = settings.value("y",-1.0f);
    preferences.sidebarWidth = settings.value("sidebarWidth",240.0f); preferences.opacity = settings.value("opacity",1.0f);
    preferences.markerSize = settings.value("markerSize",4.0f); preferences.zoomSpeed = settings.value("zoomSpeed",0.5f);
    preferences.detail = settings.value("detail",1); preferences.grid = settings.value("grid",1);
    preferences.maximized = settings.value("maximized",false); preferences.controls = settings.value("controls",true);
    preferences.biomeLabels = settings.value("biomeLabels",true); preferences.structureLabels = settings.value("structureLabels",true); preferences.showPlayer = settings.value("showPlayer",true);
    activeSection = settings.value("section",activeSection);
    savedPreferences = preferenceValues().dump();
}

static void persistPreferences(Json &command, bool force = false) {
    std::string current = preferenceValues().dump();
    if (current != pendingPreferences) { pendingPreferences = current; preferencesChangedAt = ImGui::GetTime(); }
    if (current != savedPreferences && (force || (!ImGui::IsMouseDown(0) && ImGui::GetTime() - preferencesChangedAt > 0.5) || command.value("action", "") == "close")) {
        if (command.empty()) command["action"] = "preferences";
        command["preferences"] = preferenceValues();
        savedPreferences = current;
    }
}

static void toggleMaximized() { preferences.maximized = !preferences.maximized; restoreWindow = !preferences.maximized; }

static void mapAppearanceSettings() {
    const char *details[] = {"Fast", "Balanced", "Detailed"};
    ImGui::SetNextItemWidth(-1);
    ImGui::Combo("Detail##map", &preferences.detail, details, 3);
    if (ImGui::IsItemHovered()) ImGui::SetTooltip("Detailed samples more densely. Resolution also adapts to the visible area.");
    const char *grids[] = {"No grid", "Coordinate grid", "Chunk grid"};
    ImGui::SetNextItemWidth(-1);
    ImGui::Combo("Grid##map", &preferences.grid, grids, 3);
    ImGui::Checkbox("Biome names", &preferences.biomeLabels);
    ImGui::Checkbox("Structure names", &preferences.structureLabels);
    ImGui::Checkbox("Player marker", &preferences.showPlayer);
    ImGui::TextDisabled("Marker size"); ImGui::SetNextItemWidth(-1);
    ImGui::SliderFloat("##map-marker-size", &preferences.markerSize, 2, 9, "%.0f px");
    ImGui::TextDisabled("Scroll zoom speed"); ImGui::SetNextItemWidth(-1);
    ImGui::SliderFloat("##map-zoom-speed", &preferences.zoomSpeed, 0.125f, 1, "%.2f");
}

static void settingsPanel() {
    ImGui::TextUnformatted("Window");
    ImGui::TextWrapped("Resize from any edge or corner. Drag the header to move the window, or double-click it to maximize and restore.");
    if (ImGui::Button("Compact")) { preferences.maximized = false; requestedWindowSize = {720,560}; }
    ImGui::SameLine();
    if (ImGui::Button("Large")) { preferences.maximized = false; requestedWindowSize = {1040,760}; }
    ImGui::SameLine();
    if (ImGui::Button(preferences.maximized ? "Restore window" : "Maximize window")) toggleMaximized();
    ImGui::SetNextItemWidth(240 * uiScale);
    ImGui::SliderFloat("Window opacity", &preferences.opacity, 0.65f, 1, "%.2f");
    ImGui::Checkbox("Map controls", &preferences.controls);
    ImGui::SetNextItemWidth(240 * uiScale);
    ImGui::SliderFloat("Sidebar width", &preferences.sidebarWidth,180,400,"%.0f px");
    ImGui::Spacing(); ImGui::TextUnformatted("Map defaults");
    ImGui::BeginChild("settings-map", {std::min(360 * uiScale,ImGui::GetContentRegionAvail().x),290 * uiScale});
    mapAppearanceSettings(); ImGui::EndChild();
    if (ImGui::Button("Reset settings")) { preferences = UiPreferences{}; requestedWindowSize = {1040,760}; restoreWindow = true; }
    ImGui::TextDisabled("Window and map settings save automatically.");
}

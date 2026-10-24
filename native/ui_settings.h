static void hudSettings();
static void minimapSettings();

struct UiPreferences {
    float width = 1040, height = 760, x = -1, y = -1, sidebarWidth = 240;
    float opacity = 1, markerSize = 4, zoomSpeed = 0.5f;
    int detail = 2, grid = 1, hudAnchor = 1;
    float miniSize = 224, miniZoom = 1;
    int miniMode = 0, miniTarget = 0, miniAnchor = 0;
    bool miniEnabled = true, miniRotate = false, miniStructures = true;
    float hudScale = 1, hudOpacity = 0.86f, hudX = 0.02f, hudY = 0.1f;
    bool relief = true, coastlines = true, hudEnabled = false;
    bool hudCoordinates = true, hudBiome = true, hudDimension = true, hudFps = true, hudRecovery = true, hudEvidence = false, hudWaypoint = true, hudSeed = false, hudAutoTarget = true;
    bool maximized = false, controls = true, biomeLabels = true, structureLabels = true, showPlayer = true;
};

static UiPreferences preferences;
static bool preferencesLoaded = false, restoreWindow = false;
static std::string savedPreferences, pendingPreferences;
static double preferencesChangedAt = 0;
static int hudRevisionSeen = -1;
static bool hudEditing = false;
static ImVec2 requestedWindowSize{-1,-1};

static Json preferenceValues() {
    return {{"miniSize",preferences.miniSize},{"miniZoom",preferences.miniZoom},{"miniMode",preferences.miniMode},{"miniTarget",preferences.miniTarget},{"miniAnchor",preferences.miniAnchor},{"miniEnabled",preferences.miniEnabled},{"miniRotate",preferences.miniRotate},{"miniStructures",preferences.miniStructures},{"width",preferences.width},{"height",preferences.height},{"x",preferences.x},{"y",preferences.y},{"sidebarWidth",preferences.sidebarWidth},{"opacity",preferences.opacity},{"markerSize",preferences.markerSize},{"zoomSpeed",preferences.zoomSpeed},{"detail",preferences.detail},{"grid",preferences.grid},{"maximized",preferences.maximized},{"controls",preferences.controls},{"biomeLabels",preferences.biomeLabels},{"structureLabels",preferences.structureLabels},{"showPlayer",preferences.showPlayer},{"section",activeSection},{"relief",preferences.relief},{"coastlines",preferences.coastlines},{"hudEnabled",preferences.hudEnabled},{"hudScale",preferences.hudScale},{"hudOpacity",preferences.hudOpacity},{"hudAnchor",preferences.hudAnchor},{"hudX",preferences.hudX},{"hudY",preferences.hudY},{"hudCoordinates",preferences.hudCoordinates},{"hudBiome",preferences.hudBiome},{"hudDimension",preferences.hudDimension},{"hudFps",preferences.hudFps},{"hudRecovery",preferences.hudRecovery},{"hudEvidence",preferences.hudEvidence},{"hudWaypoint",preferences.hudWaypoint},{"hudSeed",preferences.hudSeed},{"hudAutoTarget",preferences.hudAutoTarget}};
}

static void loadPreferences() {
    if (preferencesLoaded) {
        int revision = state.value("hudRevision",0);
        if (revision != hudRevisionSeen) {
            hudRevisionSeen = revision;
            preferences.hudEnabled = state.value("uiSettings",Json::object()).value("hudEnabled",false);
            preferences.miniEnabled = state.value("uiSettings",Json::object()).value("miniEnabled",true);
            if (!savedPreferences.empty()) { auto saved = Json::parse(savedPreferences); saved["hudEnabled"] = preferences.hudEnabled; saved["miniEnabled"] = preferences.miniEnabled; savedPreferences = saved.dump(); }
        }
        return;
    }
    preferencesLoaded = true;
    const auto settings = state.value("uiSettings", Json::object());
    preferences.width = settings.value("width",1040.0f); preferences.height = settings.value("height",760.0f);
    preferences.x = settings.value("x",-1.0f); preferences.y = settings.value("y",-1.0f);
    preferences.sidebarWidth = settings.value("sidebarWidth",240.0f); preferences.opacity = settings.value("opacity",1.0f);
    preferences.markerSize = settings.value("markerSize",4.0f); preferences.zoomSpeed = settings.value("zoomSpeed",0.5f);
    preferences.detail = settings.value("detail",2); preferences.grid = settings.value("grid",1);
    preferences.maximized = settings.value("maximized",false); preferences.controls = settings.value("controls",true);
    preferences.biomeLabels = settings.value("biomeLabels",true); preferences.structureLabels = settings.value("structureLabels",true); preferences.showPlayer = settings.value("showPlayer",true);
    preferences.relief = settings.value("relief",true); preferences.coastlines = settings.value("coastlines",true);
    preferences.hudEnabled = settings.value("hudEnabled",false); preferences.hudAnchor = settings.value("hudAnchor",1);
    preferences.hudScale = settings.value("hudScale",1.0f); preferences.hudOpacity = settings.value("hudOpacity",0.86f);
    preferences.hudX = settings.value("hudX",0.02f); preferences.hudY = settings.value("hudY",0.1f);
    preferences.hudCoordinates = settings.value("hudCoordinates",true); preferences.hudBiome = settings.value("hudBiome",true);
    preferences.hudDimension = settings.value("hudDimension",true); preferences.hudFps = settings.value("hudFps",true);
    preferences.hudRecovery = settings.value("hudRecovery",true); preferences.hudEvidence = settings.value("hudEvidence",false);
    preferences.hudWaypoint = settings.value("hudWaypoint",true); preferences.hudSeed = settings.value("hudSeed",false); preferences.hudAutoTarget = settings.value("hudAutoTarget",true);
    preferences.miniEnabled = settings.value("miniEnabled",true); preferences.miniRotate = settings.value("miniRotate",false); preferences.miniStructures = settings.value("miniStructures",true);
    preferences.miniSize = settings.value("miniSize",224.0f); preferences.miniZoom = settings.value("miniZoom",1.0f);
    preferences.miniMode = settings.value("miniMode",0); preferences.miniTarget = settings.value("miniTarget",0); preferences.miniAnchor = settings.value("miniAnchor",0);
    hudRevisionSeen = state.value("hudRevision",0);
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
    ImGui::Combo("##map-detail", &preferences.detail, details, 3);
    if (ImGui::IsItemHovered()) ImGui::SetTooltip("Detailed samples more densely. Resolution also adapts to the visible area.");
    const char *grids[] = {"No grid", "Coordinate grid", "Chunk grid"};
    ImGui::SetNextItemWidth(-1);
    ImGui::Combo("##map-grid", &preferences.grid, grids, 3);
    ImGui::Checkbox("Terrain shading", &preferences.relief);
    ImGui::Checkbox("Coastlines", &preferences.coastlines);
    ImGui::Checkbox("Biome names", &preferences.biomeLabels);
    ImGui::Checkbox("Structure names", &preferences.structureLabels);
    ImGui::Checkbox("Player marker", &preferences.showPlayer);
    ImGui::TextDisabled("Marker size"); ImGui::SetNextItemWidth(-1);
    ImGui::SliderFloat("##map-marker-size", &preferences.markerSize, 2, 9, "%.0f px");
    ImGui::TextDisabled("Scroll zoom speed"); ImGui::SetNextItemWidth(-1);
    ImGui::SliderFloat("##map-zoom-speed", &preferences.zoomSpeed, 0.125f, 1, "%.2f");
}

static void settingsPanel() {
    sectionHeading("Settings", "Your window and map preferences save automatically.");
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
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,{12 * uiScale,12 * uiScale});
    ImGui::BeginChild("settings-map", {std::min(360 * uiScale,ImGui::GetContentRegionAvail().x),390 * uiScale},ImGuiChildFlags_AlwaysUseWindowPadding);
    mapAppearanceSettings(); ImGui::EndChild();
    ImGui::PopStyleVar();
    if (ImGui::CollapsingHeader("Minimap",ImGuiTreeNodeFlags_DefaultOpen)) minimapSettings();
    if (ImGui::CollapsingHeader("Gameplay overlay",ImGuiTreeNodeFlags_DefaultOpen)) hudSettings();
    if (ImGui::Button("Reset settings")) { preferences = UiPreferences{}; requestedWindowSize = {1040,760}; restoreWindow = true; }
    ImGui::TextDisabled("Window and map settings save automatically.");
}

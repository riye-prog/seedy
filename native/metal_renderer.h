#pragma once
#include <jni.h>
#include <imgui.h>

namespace MetalRenderer {
    void prepare(const jlong *handles);
    void render(ImDrawData *data, int width, int height);
    void shutdown();
}

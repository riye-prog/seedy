#pragma once
#include <jni.h>
#include <imgui_impl_vulkan.h>

namespace VulkanRenderer {
    void prepare(const jlong *handles);
    void render(ImDrawData *data, const jlong *handles, int width, int height);
    void shutdown();
}

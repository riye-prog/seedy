#include "vulkan_renderer.h"
#include "draw_region.h"
#include <stdexcept>
#include <string>
#include <cstdint>

namespace VulkanRenderer {
    static VkDevice device = VK_NULL_HANDLE;
    static VkFormat format = VK_FORMAT_UNDEFINED;
    static bool initialized = false;

    static void check(VkResult result) {
        if (result != VK_SUCCESS) throw std::runtime_error("Vulkan overlay error " + std::to_string(result));
    }

    void shutdown() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        if (initialized) ImGui_ImplVulkan_Shutdown();
        initialized = false;
        device = VK_NULL_HANDLE;
        format = VK_FORMAT_UNDEFINED;
    }

    void prepare(const jlong *handles) {
        auto incoming = reinterpret_cast<VkDevice>(handles[3]);
        auto incomingFormat = static_cast<VkFormat>(handles[8]);
        if (initialized && device == incoming && format == incomingFormat) return;
        shutdown();
        volkInitializeCustom(reinterpret_cast<PFN_vkGetInstanceProcAddr>(handles[0]));
        auto instance = reinterpret_cast<VkInstance>(handles[1]);
        volkLoadInstance(instance);
        volkLoadDevice(incoming);
        device = incoming;
        format = incomingFormat;
        try {
            if (!vkCmdBeginRenderingKHR || !vkCmdEndRenderingKHR) throw std::runtime_error("Vulkan dynamic rendering is unavailable");
            ImGui_ImplVulkan_InitInfo info{};
            info.ApiVersion = VK_API_VERSION_1_2;
            info.Instance = instance;
            info.PhysicalDevice = reinterpret_cast<VkPhysicalDevice>(handles[2]);
            info.Device = device;
            info.QueueFamily = static_cast<uint32_t>(handles[5]);
            info.Queue = reinterpret_cast<VkQueue>(handles[4]);
            info.DescriptorPoolSize = 8;
            info.MinImageCount = 3;
            info.ImageCount = 3;
            info.UseDynamicRendering = true;
            info.PipelineRenderingCreateInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR;
            info.PipelineRenderingCreateInfo.colorAttachmentCount = 1;
            info.PipelineRenderingCreateInfo.pColorAttachmentFormats = &format;
            info.CheckVkResultFn = check;
            if (!ImGui_ImplVulkan_Init(&info)) throw std::runtime_error("Could not initialize the Vulkan panel");
            initialized = true;
        } catch (...) { shutdown(); throw; }
    }

    void render(ImDrawData *data, const jlong *handles, int width, int height) {
        auto area = drawRegion(data,width,height);
        if (area.width == 0 || area.height == 0) return;
        auto commands = reinterpret_cast<VkCommandBuffer>(handles[9]);
        if (!commands) throw std::runtime_error("Missing Minecraft command buffer");
        VkMemoryBarrier before{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        before.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        before.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        vkCmdPipelineBarrier(commands,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,0,1,&before,0,nullptr,0,nullptr);
        VkRenderingAttachmentInfoKHR color{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR};
        color.imageView = reinterpret_cast<VkImageView>(handles[7]);
        color.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        color.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
        color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        VkRenderingInfoKHR rendering{VK_STRUCTURE_TYPE_RENDERING_INFO_KHR};
        rendering.renderArea = {{area.x,area.y},{static_cast<uint32_t>(area.width),static_cast<uint32_t>(area.height)}};
        rendering.layerCount = 1;
        rendering.colorAttachmentCount = 1;
        rendering.pColorAttachments = &color;
        flipDrawData(data,height);
        vkCmdBeginRenderingKHR(commands,&rendering);
        try {
            ImGui_ImplVulkan_RenderDrawData(data,commands);
        } catch (...) {
            vkCmdEndRenderingKHR(commands);
            flipDrawData(data,height);
            throw;
        }
        vkCmdEndRenderingKHR(commands);
        flipDrawData(data,height);
        VkMemoryBarrier after{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        after.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        after.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        vkCmdPipelineBarrier(commands,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,1,&after,0,nullptr,0,nullptr);
    }
}

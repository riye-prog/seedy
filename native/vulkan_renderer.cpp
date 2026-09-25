#include "vulkan_renderer.h"
#include <array>
#include <stdexcept>
#include <string>
#include <cstdint>
#include <utility>

namespace VulkanRenderer {
    struct Frame {
        VkCommandPool pool = VK_NULL_HANDLE;
        VkCommandBuffer commands = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        bool submitted = false;
    };
    static VkDevice device = VK_NULL_HANDLE;
    static VkQueue queue = VK_NULL_HANDLE;
    static VkRenderPass pass = VK_NULL_HANDLE;
    static VkFormat format = VK_FORMAT_UNDEFINED;
    static std::array<Frame, 3> frames;
    static size_t nextFrame = 0;
    static bool initialized = false;

    static void check(VkResult result) {
        if (result != VK_SUCCESS) throw std::runtime_error("Vulkan overlay error " + std::to_string(result));
    }

    void shutdown() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        if (initialized) ImGui_ImplVulkan_Shutdown();
        initialized = false;
        for (auto &frame : frames) {
            if (frame.framebuffer) vkDestroyFramebuffer(device, frame.framebuffer, nullptr);
            if (frame.fence) vkDestroyFence(device, frame.fence, nullptr);
            if (frame.pool) vkDestroyCommandPool(device, frame.pool, nullptr);
            frame = {};
        }
        if (pass) vkDestroyRenderPass(device, pass, nullptr);
        pass = VK_NULL_HANDLE;
        device = VK_NULL_HANDLE;
        queue = VK_NULL_HANDLE;
        nextFrame = 0;
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
        queue = reinterpret_cast<VkQueue>(handles[4]);
        format = incomingFormat;
        try {
            VkAttachmentDescription attachment{};
            attachment.format = format;
            attachment.samples = VK_SAMPLE_COUNT_1_BIT;
            attachment.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
            attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            attachment.initialLayout = VK_IMAGE_LAYOUT_GENERAL;
            attachment.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_GENERAL};
            VkSubpassDescription subpass{};
            subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
            subpass.colorAttachmentCount = 1;
            subpass.pColorAttachments = &reference;
            VkSubpassDependency dependencies[2]{};
            dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
            dependencies[0].dstSubpass = 0;
            dependencies[0].srcStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependencies[0].srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
            dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dependencies[1].srcSubpass = 0;
            dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
            dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependencies[1].dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dependencies[1].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
            VkRenderPassCreateInfo passInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
            passInfo.attachmentCount = 1;
            passInfo.pAttachments = &attachment;
            passInfo.subpassCount = 1;
            passInfo.pSubpasses = &subpass;
            passInfo.dependencyCount = 2;
            passInfo.pDependencies = dependencies;
            check(vkCreateRenderPass(device, &passInfo, nullptr, &pass));
            for (auto &frame : frames) {
                VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
                poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
                poolInfo.queueFamilyIndex = static_cast<uint32_t>(handles[5]);
                check(vkCreateCommandPool(device, &poolInfo, nullptr, &frame.pool));
                VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
                allocation.commandPool = frame.pool;
                allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
                allocation.commandBufferCount = 1;
                check(vkAllocateCommandBuffers(device, &allocation, &frame.commands));
                VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
                check(vkCreateFence(device, &fenceInfo, nullptr, &frame.fence));
            }
            ImGui_ImplVulkan_InitInfo info{};
            info.ApiVersion = VK_API_VERSION_1_2;
            info.Instance = instance;
            info.PhysicalDevice = reinterpret_cast<VkPhysicalDevice>(handles[2]);
            info.Device = device;
            info.QueueFamily = static_cast<uint32_t>(handles[5]);
            info.Queue = queue;
            info.DescriptorPoolSize = 8;
            info.RenderPass = pass;
            info.MinImageCount = 3;
            info.ImageCount = 3;
            info.CheckVkResultFn = check;
            if (!ImGui_ImplVulkan_Init(&info)) throw std::runtime_error("Could not initialize the Vulkan panel");
            initialized = true;
        } catch (...) { shutdown(); throw; }
    }

    void render(ImDrawData *data, const jlong *handles, int width, int height) {
        if (data->TotalVtxCount == 0 || width <= 0 || height <= 0) return;
        auto &frame = frames[nextFrame];
        if (frame.submitted) check(vkWaitForFences(device, 1, &frame.fence, VK_TRUE, UINT64_MAX));
        frame.submitted = false;
        if (frame.framebuffer) { vkDestroyFramebuffer(device, frame.framebuffer, nullptr); frame.framebuffer = VK_NULL_HANDLE; }
        check(vkResetCommandPool(device, frame.pool, 0));
        VkImageView view = reinterpret_cast<VkImageView>(handles[7]);
        VkFramebufferCreateInfo framebufferInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        framebufferInfo.renderPass = pass;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = &view;
        framebufferInfo.width = width;
        framebufferInfo.height = height;
        framebufferInfo.layers = 1;
        check(vkCreateFramebuffer(device, &framebufferInfo, nullptr, &frame.framebuffer));
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(frame.commands, &begin));
        VkRenderPassBeginInfo renderPass{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        renderPass.renderPass = pass;
        renderPass.framebuffer = frame.framebuffer;
        renderPass.renderArea.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
        vkCmdBeginRenderPass(frame.commands, &renderPass, VK_SUBPASS_CONTENTS_INLINE);
        for (auto list : data->CmdLists) {
            for (auto &vertex : list->VtxBuffer) vertex.pos.y = height - vertex.pos.y;
            for (auto &command : list->CmdBuffer) {
                float top = command.ClipRect.y;
                command.ClipRect.y = height - command.ClipRect.w;
                command.ClipRect.w = height - top;
            }
        }
        ImGui_ImplVulkan_RenderDrawData(data, frame.commands);
        vkCmdEndRenderPass(frame.commands);
        check(vkEndCommandBuffer(frame.commands));
        check(vkResetFences(device, 1, &frame.fence));
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &frame.commands;
        check(vkQueueSubmit(queue, 1, &submit, frame.fence));
        frame.submitted = true;
        nextFrame = (nextFrame + 1) % frames.size();
    }
}

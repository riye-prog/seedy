#include "../vulkan_renderer.h"
#include <dlfcn.h>
#include <chrono>
#include <fstream>
#include <iostream>
#include <vector>
#include <numeric>
#include <algorithm>
#include <cstring>
#define SEEDY_HEADLESS_TEST
#include "../overlay.cpp"

static PFN_vkQueueSubmit realSubmit;
static PFN_vkWaitForFences realWait;
static PFN_vkCreateFramebuffer realFramebuffer;
static PFN_vkCmdBeginRenderPass realBeginPass;
static PFN_vkCmdBeginRenderingKHR realBeginRendering;
static uint64_t extraSubmits = 0, extraWaits = 0, framebuffers = 0, renderedPixels = 0;

static VKAPI_ATTR VkResult VKAPI_CALL countSubmit(VkQueue queue, uint32_t count, const VkSubmitInfo *info, VkFence fence) {
    extraSubmits++;
    return realSubmit(queue,count,info,fence);
}
static VKAPI_ATTR VkResult VKAPI_CALL countWait(VkDevice device, uint32_t count, const VkFence *fences, VkBool32 all, uint64_t timeout) {
    extraWaits++;
    return realWait(device,count,fences,all,timeout);
}
static VKAPI_ATTR VkResult VKAPI_CALL countFramebuffer(VkDevice device, const VkFramebufferCreateInfo *info, const VkAllocationCallbacks *allocator, VkFramebuffer *framebuffer) {
    framebuffers++;
    return realFramebuffer(device,info,allocator,framebuffer);
}
static VKAPI_ATTR void VKAPI_CALL countPass(VkCommandBuffer commands, const VkRenderPassBeginInfo *info, VkSubpassContents contents) {
    renderedPixels += static_cast<uint64_t>(info->renderArea.extent.width) * info->renderArea.extent.height;
    realBeginPass(commands,info,contents);
}
static VKAPI_ATTR void VKAPI_CALL countRendering(VkCommandBuffer commands, const VkRenderingInfoKHR *info) {
    renderedPixels += static_cast<uint64_t>(info->renderArea.extent.width) * info->renderArea.extent.height;
    realBeginRendering(commands,info);
}
static void requireVk(VkResult result) {
    if (result != VK_SUCCESS) throw std::runtime_error("Vulkan test error " + std::to_string(result));
}
static uint32_t memoryType(VkPhysicalDevice physical, uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties memory;
    vkGetPhysicalDeviceMemoryProperties(physical,&memory);
    for (uint32_t i = 0; i < memory.memoryTypeCount; i++) if ((bits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & flags) == flags) return i;
    throw std::runtime_error("Required GPU memory type is unavailable");
}
static double percentile(std::vector<double> values, double fraction) {
    std::sort(values.begin(),values.end());
    return values[static_cast<size_t>((values.size() - 1) * fraction)];
}

int main(int argc, char **argv) {
    try {
        int width = 3024, height = 1964, count = argc > 1 ? std::stoi(argv[1]) : 240;
        if (count < 10 || count > 2000) throw std::runtime_error("Use 10 to 2000 frames");
        auto *library = dlopen("/opt/homebrew/lib/libMoltenVK.dylib",RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
        auto getInstance = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library,"vkGetInstanceProcAddr"));
        volkInitializeCustom(getInstance);
        uint32_t extensionCount = 0;
        requireVk(vkEnumerateInstanceExtensionProperties(nullptr,&extensionCount,nullptr));
        std::vector<VkExtensionProperties> instanceAvailable(extensionCount);
        requireVk(vkEnumerateInstanceExtensionProperties(nullptr,&extensionCount,instanceAvailable.data()));
        bool portability = std::any_of(instanceAvailable.begin(),instanceAvailable.end(),[](const auto &extension) { return std::strcmp(extension.extensionName,VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME) == 0; });
        const char *instanceExtensions[] = {VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME};
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName = "Seedy command-line GPU check"; app.apiVersion = VK_API_VERSION_1_2;
        VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; instanceInfo.pApplicationInfo = &app; instanceInfo.flags = portability ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0; instanceInfo.enabledExtensionCount = portability ? 1 : 0; instanceInfo.ppEnabledExtensionNames = instanceExtensions;
        VkInstance instance; requireVk(vkCreateInstance(&instanceInfo,nullptr,&instance)); volkLoadInstance(instance);
        uint32_t deviceCount = 0; requireVk(vkEnumeratePhysicalDevices(instance,&deviceCount,nullptr));
        if (!deviceCount) throw std::runtime_error("No Vulkan GPU available");
        std::vector<VkPhysicalDevice> physicals(deviceCount); requireVk(vkEnumeratePhysicalDevices(instance,&deviceCount,physicals.data()));
        VkPhysicalDevice physical = physicals[0]; VkPhysicalDeviceProperties properties; vkGetPhysicalDeviceProperties(physical,&properties);
        uint32_t familyCount = 0; vkGetPhysicalDeviceQueueFamilyProperties(physical,&familyCount,nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount); vkGetPhysicalDeviceQueueFamilyProperties(physical,&familyCount,families.data());
        uint32_t family = 0; while (family < familyCount && !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) family++;
        if (family == familyCount || families[family].timestampValidBits == 0) throw std::runtime_error("GPU timestamp queries unavailable");
        float priority = 1;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; queueInfo.queueFamilyIndex = family; queueInfo.queueCount = 1; queueInfo.pQueuePriorities = &priority;
        const char *deviceExtensions[] = {VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME,"VK_KHR_portability_subset"};
        VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamic{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR}; dynamic.dynamicRendering = VK_TRUE;
        VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; deviceInfo.pNext = &dynamic; deviceInfo.queueCreateInfoCount = 1; deviceInfo.pQueueCreateInfos = &queueInfo; deviceInfo.enabledExtensionCount = 2; deviceInfo.ppEnabledExtensionNames = deviceExtensions;
        VkDevice device; requireVk(vkCreateDevice(physical,&deviceInfo,nullptr,&device)); volkLoadDevice(device);
        VkQueue queue; vkGetDeviceQueue(device,family,0,&queue);
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; imageInfo.imageType = VK_IMAGE_TYPE_2D; imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM; imageInfo.extent = {static_cast<uint32_t>(width),static_cast<uint32_t>(height),1}; imageInfo.mipLevels = 1; imageInfo.arrayLayers = 1; imageInfo.samples = VK_SAMPLE_COUNT_1_BIT; imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL; imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        VkImage image; requireVk(vkCreateImage(device,&imageInfo,nullptr,&image));
        VkMemoryRequirements imageMemory; vkGetImageMemoryRequirements(device,image,&imageMemory);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocation.allocationSize = imageMemory.size; allocation.memoryTypeIndex = memoryType(physical,imageMemory.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VkDeviceMemory memory; requireVk(vkAllocateMemory(device,&allocation,nullptr,&memory)); requireVk(vkBindImageMemory(device,image,memory,0));
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; viewInfo.image = image; viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D; viewInfo.format = imageInfo.format; viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        VkImageView view; requireVk(vkCreateImageView(device,&viewInfo,nullptr,&view));
        VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; poolInfo.queueFamilyIndex = family;
        VkCommandPool pool; requireVk(vkCreateCommandPool(device,&poolInfo,nullptr,&pool));
        VkCommandBufferAllocateInfo commandInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; commandInfo.commandPool = pool; commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; commandInfo.commandBufferCount = 2;
        VkCommandBuffer commands[2]; requireVk(vkAllocateCommandBuffers(device,&commandInfo,commands));
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; VkFence done; requireVk(vkCreateFence(device,&fenceInfo,nullptr,&done));
        VkQueryPoolCreateInfo queryInfo{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO}; queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP; queryInfo.queryCount = 2;
        VkQueryPool queries; requireVk(vkCreateQueryPool(device,&queryInfo,nullptr,&queries));
        context = ImGui::CreateContext(); auto &io = ImGui::GetIO(); io.DisplaySize = {static_cast<float>(width),static_cast<float>(height)}; io.DeltaTime = 1.0f / 144; io.IniFilename = nullptr;
        configureAppearance(2); io.Fonts->Build();
        state = {{"connected",true},{"verified",true},{"dimension","overworld"},{"biome","minecraft:cherry_grove"},{"x",128},{"y",72},{"z",96},{"yaw",45.0},{"fps",144},{"uiSettings",{{"hudEnabled",true},{"miniEnabled",false}}},{"waypoint",{{"name","minecraft:ancient_city"},{"x",-596},{"z",-240},{"dimension","overworld"}}}};
        bool mapTest = argc > 3;
        bool panelTest = argc > 4 && std::string(argv[4]) == "panel";
        if (mapTest) {
            Json fixture; std::ifstream(argv[3]) >> fixture;
            mapDecode(miniView,fixture);
            if (panelTest) {
                state["map"] = fixture; state["catalogs"]["overworld"]["structures"] = Json::array({"minecraft:villages"});
                mapView.initialized = true; mapView.seed = fixture.value("seed",""); mapView.requestId = fixture.value("id",0LL); mapView.x = 128; mapView.z = 96; mapView.blocksPerPixel = 4;
                activeSection = 5;
            }
            state["seed"] = fixture.value("seed",""); state["gameVersion"] = "26.3"; state["generationVersion"] = "26.3";
            state["uiSettings"]["miniEnabled"] = true; state["uiSettings"]["hudEnabled"] = false;
            if (argc > 4 && std::string(argv[4]) == "biomes") state["uiSettings"]["miniMode"] = 2;
            liveMapX = 128; liveMapZ = 96; liveMapYaw = 45;
            receiveTerrain({{"reset",1},{"dimension","overworld"}});
            if (argc > 5) {
                Json terrain; std::ifstream(argv[5]) >> terrain;
                for (const auto &tile : terrain["terrain"]) receiveTerrain(tile);
                liveMapX = terrain.value("x",0); liveMapZ = terrain.value("z",0);
                state["x"] = liveMapX; state["z"] = liveMapZ; state["y"] = terrain.value("y",64); state["biome"] = terrain.value("biome","minecraft:plains");
                state["seed"] = terrain.value("seed",""); miniView.data["seed"] = state["seed"];
                miniView.data["markers"] = terrain.value("markers",Json::array());
                state["uiSettings"]["miniMode"] = 1;
            } else for (int z = 5; z < 9; ++z) for (int x = 7; x < 11; ++x) receiveTerrain({{"epoch",1},{"dimension","overworld"},{"x",x},{"z",z},{"runs",{128,0x75a85a,128,0x376b43}}});
        }
        jlong handles[13] = {reinterpret_cast<jlong>(getInstance),reinterpret_cast<jlong>(instance),reinterpret_cast<jlong>(physical),reinterpret_cast<jlong>(device),reinterpret_cast<jlong>(queue),static_cast<jlong>(family),reinterpret_cast<jlong>(image),reinterpret_cast<jlong>(view),static_cast<jlong>(imageInfo.format),reinterpret_cast<jlong>(commands[1])};
        VulkanRenderer::prepare(handles);
        realSubmit = vkQueueSubmit; realWait = vkWaitForFences; realFramebuffer = vkCreateFramebuffer; realBeginPass = vkCmdBeginRenderPass; realBeginRendering = vkCmdBeginRenderingKHR;
        vkQueueSubmit = countSubmit; vkWaitForFences = countWait; vkCreateFramebuffer = countFramebuffer; vkCmdBeginRenderPass = countPass; vkCmdBeginRenderingKHR = countRendering;
        std::vector<double> cpu, gpu;
        bool first = true;
        int maximumVertices = 0;
        for (int frame = -24; frame < count; frame++) {
            if (frame == 0) { extraSubmits = extraWaits = framebuffers = renderedPixels = 0; }
            auto start = std::chrono::steady_clock::now();
            requireVk(vkResetCommandPool(device,pool,0)); requireVk(vkResetFences(device,1,&done));
            MapAtlas::begin(); handles[10] = frame + 25; handles[11] = frame + 24; handles[12] = 1;
            VulkanRenderer::prepare(handles);
            ImGui_ImplVulkan_NewFrame(); ImGui::NewFrame(); drawOverlayFrame(width,height,panelTest,!panelTest); ImGui::Render();
            maximumVertices = std::max(maximumVertices,ImGui::GetDrawData()->TotalVtxCount);
            if (frame >= 0 && !MapAtlas::uploads.empty()) throw std::runtime_error("Unchanged map tiles were uploaded again");
            VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            requireVk(vkBeginCommandBuffer(commands[0],&begin));
            vkCmdResetQueryPool(commands[0],queries,0,2); vkCmdWriteTimestamp(commands[0],VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0);
            if (first) {
                VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL; barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; barrier.image = image; barrier.subresourceRange = viewInfo.subresourceRange;
                vkCmdPipelineBarrier(commands[0],VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier); first = false;
            }
            VkClearColorValue clear{{0.03f,0.04f,0.05f,1.0f}}; vkCmdClearColorImage(commands[0],image,VK_IMAGE_LAYOUT_GENERAL,&clear,1,&viewInfo.subresourceRange);
            requireVk(vkEndCommandBuffer(commands[0]));
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &commands[0];
#ifdef SEEDY_LEGACY_RENDERER
            requireVk(realSubmit(queue,1,&submit,VK_NULL_HANDLE));
            VulkanRenderer::render(ImGui::GetDrawData(),handles,width,height);
            requireVk(vkBeginCommandBuffer(commands[1],&begin));
#else
            requireVk(vkBeginCommandBuffer(commands[1],&begin));
            VulkanRenderer::render(ImGui::GetDrawData(),handles,width,height);
#endif
            vkCmdWriteTimestamp(commands[1],VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1); requireVk(vkEndCommandBuffer(commands[1]));
#ifdef SEEDY_LEGACY_RENDERER
            submit.pCommandBuffers = &commands[1];
#else
            submit.commandBufferCount = 2; submit.pCommandBuffers = commands;
#endif
            requireVk(realSubmit(queue,1,&submit,done));
            auto recorded = std::chrono::steady_clock::now(); requireVk(realWait(device,1,&done,VK_TRUE,UINT64_MAX));
            uint64_t timestamps[2]; requireVk(vkGetQueryPoolResults(device,queries,0,2,sizeof(timestamps),timestamps,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT));
            if (frame >= 0) { cpu.push_back(std::chrono::duration<double,std::milli>(recorded - start).count()); gpu.push_back((timestamps[1] - timestamps[0]) * properties.limits.timestampPeriod / 1e6); }
        }
        if (argc > 2) {
            VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bufferInfo.size = static_cast<VkDeviceSize>(width) * height * 4; bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            VkBuffer buffer; requireVk(vkCreateBuffer(device,&bufferInfo,nullptr,&buffer));
            VkMemoryRequirements requirements; vkGetBufferMemoryRequirements(device,buffer,&requirements); allocation.allocationSize = requirements.size; allocation.memoryTypeIndex = memoryType(physical,requirements.memoryTypeBits,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            VkDeviceMemory readback; requireVk(vkAllocateMemory(device,&allocation,nullptr,&readback)); requireVk(vkBindBufferMemory(device,buffer,readback,0));
            requireVk(vkResetCommandPool(device,pool,0)); VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; requireVk(vkBeginCommandBuffer(commands[0],&begin));
            VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT; vkCmdPipelineBarrier(commands[0],VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
            VkBufferImageCopy copy{}; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,0,0,1}; copy.imageExtent = imageInfo.extent; vkCmdCopyImageToBuffer(commands[0],image,VK_IMAGE_LAYOUT_GENERAL,buffer,1,&copy); requireVk(vkEndCommandBuffer(commands[0]));
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = commands; requireVk(vkResetFences(device,1,&done)); requireVk(realSubmit(queue,1,&submit,done)); requireVk(realWait(device,1,&done,VK_TRUE,UINT64_MAX));
            void *pixels; requireVk(vkMapMemory(device,readback,0,bufferInfo.size,0,&pixels)); std::ofstream output(argv[2],std::ios::binary); output.write(static_cast<char *>(pixels),bufferInfo.size); output.close(); vkUnmapMemory(device,readback);
            vkDestroyBuffer(device,buffer,nullptr); vkFreeMemory(device,readback,nullptr);
        }
        Json result = {{"gpu",properties.deviceName},{"width",width},{"height",height},{"frames",count},{"cpu_record_median_ms",percentile(cpu,0.5)},{"cpu_record_p95_ms",percentile(cpu,0.95)},{"gpu_median_ms",percentile(gpu,0.5)},{"gpu_p95_ms",percentile(gpu,0.95)},{"extra_queue_submits",extraSubmits},{"extra_fence_waits",extraWaits},{"framebuffer_creations",framebuffers},{"rendered_pixels_per_frame",renderedPixels / count}};
        result["minimap"] = mapTest; result["panel"] = panelTest; result["maximum_vertices"] = maximumVertices; result["atlas_tiles"] = MapAtlas::entries.size();
#ifndef SEEDY_LEGACY_RENDERER
        if (extraSubmits || extraWaits || framebuffers) throw std::runtime_error("HUD reintroduced per-frame GPU submission or resource overhead");
        if (!panelTest && renderedPixels / count > static_cast<uint64_t>(width) * height / 8) throw std::runtime_error("HUD rendering area is too large");
#endif
        std::cout << result.dump(2) << std::endl;
        VulkanRenderer::shutdown(); ImGui::DestroyContext(context); context = nullptr;
        vkDestroyQueryPool(device,queries,nullptr); vkDestroyFence(device,done,nullptr); vkDestroyCommandPool(device,pool,nullptr); vkDestroyImageView(device,view,nullptr); vkDestroyImage(device,image,nullptr); vkFreeMemory(device,memory,nullptr); vkDestroyDevice(device,nullptr); vkDestroyInstance(instance,nullptr);
        return 0;
    } catch (const std::exception &error) { std::cerr << error.what() << std::endl; return 1; }
}

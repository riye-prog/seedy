#pragma once
#include "map_atlas.h"
#include <cstring>
#include <stdexcept>
#include <string>

namespace VulkanAtlas {
    struct Staging { VkBuffer buffer = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; void *mapped = nullptr; uint64_t submit = 0, timeline = 0; };
    inline VkDevice device = VK_NULL_HANDLE;
    inline VkPhysicalDevice physical = VK_NULL_HANDLE;
    inline VkImage image = VK_NULL_HANDLE;
    inline VkDeviceMemory memory = VK_NULL_HANDLE;
    inline VkImageView view = VK_NULL_HANDLE;
    inline VkSampler sampler = VK_NULL_HANDLE;
    inline VkDescriptorSet descriptor = VK_NULL_HANDLE;
    inline VkDescriptorSet nearestDescriptor = VK_NULL_HANDLE;
    inline VkSampler nearestSampler = VK_NULL_HANDLE;
    inline std::array<Staging,4> staging;
    inline Staging *available = nullptr;
    inline bool started = false;
    constexpr size_t bytes = MapAtlas::uploadLimit * sizeof(MapAtlas::Upload::pixels);

    inline void require(VkResult result) { if (result != VK_SUCCESS) throw std::runtime_error("Map texture allocation failed: " + std::to_string(result)); }
    inline uint32_t memoryType(uint32_t mask, VkMemoryPropertyFlags flags) {
        VkPhysicalDeviceMemoryProperties properties;
        vkGetPhysicalDeviceMemoryProperties(physical,&properties);
        for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) if ((mask & (1u << i)) && (properties.memoryTypes[i].propertyFlags & flags) == flags) return i;
        throw std::runtime_error("Map texture memory is unavailable");
    }
    inline void allocate(VkMemoryRequirements requirements, VkMemoryPropertyFlags flags, VkDeviceMemory &allocation) {
        VkMemoryAllocateInfo info{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        info.allocationSize = requirements.size; info.memoryTypeIndex = memoryType(requirements.memoryTypeBits,flags);
        require(vkAllocateMemory(device,&info,nullptr,&allocation));
    }
    inline void shutdown() {
        if (!device) return;
        if (descriptor) ImGui_ImplVulkan_RemoveTexture(descriptor);
        if (nearestDescriptor) ImGui_ImplVulkan_RemoveTexture(nearestDescriptor);
        if (nearestSampler) vkDestroySampler(device,nearestSampler,nullptr);
        nearestDescriptor = VK_NULL_HANDLE; nearestSampler = VK_NULL_HANDLE;
        for (auto &slot : staging) {
            if (slot.mapped) vkUnmapMemory(device,slot.memory);
            if (slot.buffer) vkDestroyBuffer(device,slot.buffer,nullptr);
            if (slot.memory) vkFreeMemory(device,slot.memory,nullptr);
            slot = {};
        }
        if (sampler) vkDestroySampler(device,sampler,nullptr);
        if (view) vkDestroyImageView(device,view,nullptr);
        if (image) vkDestroyImage(device,image,nullptr);
        if (memory) vkFreeMemory(device,memory,nullptr);
        device = VK_NULL_HANDLE; physical = VK_NULL_HANDLE; image = VK_NULL_HANDLE; memory = VK_NULL_HANDLE; view = VK_NULL_HANDLE; sampler = VK_NULL_HANDLE; descriptor = VK_NULL_HANDLE; available = nullptr; started = false;
        MapAtlas::reset();
    }
    inline void prepare(const jlong *handles) {
        if (!device) {
            device = reinterpret_cast<VkDevice>(handles[3]); physical = reinterpret_cast<VkPhysicalDevice>(handles[2]);
            VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            info.imageType = VK_IMAGE_TYPE_2D; info.format = VK_FORMAT_R8G8B8A8_UNORM; info.extent = {MapAtlas::side,MapAtlas::side,1}; info.mipLevels = 1; info.arrayLayers = 1; info.samples = VK_SAMPLE_COUNT_1_BIT; info.tiling = VK_IMAGE_TILING_OPTIMAL; info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            require(vkCreateImage(device,&info,nullptr,&image));
            VkMemoryRequirements requirements; vkGetImageMemoryRequirements(device,image,&requirements);
            allocate(requirements,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,memory); require(vkBindImageMemory(device,image,memory,0));
            VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = image; viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D; viewInfo.format = info.format; viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            require(vkCreateImageView(device,&viewInfo,nullptr,&view));
            VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            samplerInfo.magFilter = VK_FILTER_LINEAR; samplerInfo.minFilter = VK_FILTER_LINEAR; samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST; samplerInfo.addressModeU = samplerInfo.addressModeV = samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE; samplerInfo.maxLod = 0;
            require(vkCreateSampler(device,&samplerInfo,nullptr,&sampler));
            descriptor = ImGui_ImplVulkan_AddTexture(sampler,view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            samplerInfo.magFilter = VK_FILTER_NEAREST; samplerInfo.minFilter = VK_FILTER_LINEAR;
            require(vkCreateSampler(device,&samplerInfo,nullptr,&nearestSampler));
            nearestDescriptor = ImGui_ImplVulkan_AddTexture(nearestSampler,view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        available = nullptr;
        for (auto &slot : staging) if (!slot.submit || (slot.timeline == static_cast<uint64_t>(handles[12]) && slot.submit <= static_cast<uint64_t>(handles[11]))) { available = &slot; break; }
        if (available && !available->buffer) {
            VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; info.size = bytes; info.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
            require(vkCreateBuffer(device,&info,nullptr,&available->buffer));
            VkMemoryRequirements requirements; vkGetBufferMemoryRequirements(device,available->buffer,&requirements);
            allocate(requirements,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,available->memory);
            require(vkBindBufferMemory(device,available->buffer,available->memory,0)); require(vkMapMemory(device,available->memory,0,bytes,0,&available->mapped));
        }
        MapAtlas::texture = reinterpret_cast<ImTextureID>(descriptor);
        MapAtlas::nearestTexture = reinterpret_cast<ImTextureID>(nearestDescriptor);
        MapAtlas::writable = available && handles[12];
    }
    inline void upload(VkCommandBuffer commands, const jlong *handles) {
        if (MapAtlas::uploads.empty() || !available) return;
        std::array<VkBufferImageCopy,MapAtlas::uploadLimit> copies{};
        size_t offset = 0, count = 0;
        for (const auto &update : MapAtlas::uploads) {
            std::memcpy(static_cast<char *>(available->mapped) + offset,update.pixels.data(),sizeof(update.pixels));
            auto &copy = copies[count++]; copy.bufferOffset = offset; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,0,0,1}; copy.imageOffset = {update.x,update.y,0}; copy.imageExtent = {MapAtlas::pitch,MapAtlas::pitch,1}; offset += sizeof(update.pixels);
        }
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = started ? VK_ACCESS_SHADER_READ_BIT : 0; barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.oldLayout = started ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED; barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; barrier.image = image; barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        vkCmdPipelineBarrier(commands,started ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
        vkCmdCopyBufferToImage(commands,available->buffer,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,static_cast<uint32_t>(count),copies.data());
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT; barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        vkCmdPipelineBarrier(commands,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
        available->submit = handles[10]; available->timeline = handles[12]; started = true;
    }
}

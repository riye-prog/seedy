#pragma once
#include "map_atlas.h"
#include <atomic>
#include <memory>
#include <cstring>
#include <stdexcept>

namespace MetalAtlas {
    struct Staging { id<MTLBuffer> buffer; std::shared_ptr<std::atomic<bool>> ready = std::make_shared<std::atomic<bool>>(true); };
    inline id<MTLTexture> texture;
    inline std::array<Staging,4> staging;
    inline Staging *available = nullptr;
    constexpr size_t rowBytes = 256, tileBytes = rowBytes * MapAtlas::pitch;

    inline void shutdown() {
        texture = nil; available = nullptr;
        for (auto &slot : staging) { slot.buffer = nil; slot.ready = std::make_shared<std::atomic<bool>>(true); }
        MapAtlas::reset();
    }
    inline void prepare(id<MTLDevice> device) {
        if (!texture) {
            auto descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:MapAtlas::side height:MapAtlas::side mipmapped:NO];
            descriptor.storageMode = MTLStorageModePrivate; descriptor.usage = MTLTextureUsageShaderRead;
            texture = [device newTextureWithDescriptor:descriptor];
            if (!texture) throw std::runtime_error("Map texture allocation failed");
            texture.label = @"Seedy map atlas";
        }
        available = nullptr;
        for (auto &slot : staging) if (slot.ready->load(std::memory_order_acquire)) { available = &slot; break; }
        if (available && !available->buffer) available->buffer = [device newBufferWithLength:tileBytes * MapAtlas::uploadLimit options:MTLResourceStorageModeShared];
        MapAtlas::texture = reinterpret_cast<ImTextureID>((__bridge void *)texture);
        MapAtlas::writable = available && available->buffer;
    }
    inline void upload(id<MTLCommandBuffer> commands) {
        if (MapAtlas::uploads.empty() || !available) return;
        auto encoder = [commands blitCommandEncoder];
        if (!encoder) throw std::runtime_error("Map upload could not be recorded");
        size_t offset = 0;
        for (const auto &update : MapAtlas::uploads) {
            for (int row = 0; row < MapAtlas::pitch; ++row) std::memcpy(static_cast<char *>(available->buffer.contents) + offset + row * rowBytes,update.pixels.data() + row * MapAtlas::pitch,MapAtlas::pitch * sizeof(ImU32));
            [encoder copyFromBuffer:available->buffer sourceOffset:offset sourceBytesPerRow:rowBytes sourceBytesPerImage:tileBytes sourceSize:MTLSizeMake(MapAtlas::pitch,MapAtlas::pitch,1) toTexture:texture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(update.x,update.y,0)];
            offset += tileBytes;
        }
        [encoder endEncoding];
        auto ready = available->ready; ready->store(false,std::memory_order_release);
        [commands addCompletedHandler:^(id<MTLCommandBuffer>) { ready->store(true,std::memory_order_release); }];
    }
}

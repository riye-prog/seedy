#include "metal_renderer.h"
#include "draw_region.h"
#include <imgui_impl_metal.h>
#include <Metal/Metal.h>
#include <stdexcept>
#include "metal_atlas.h"

namespace MetalRenderer {
    static id<MTLDevice> device;
    static id<MTLCommandBuffer> commands;
    static MTLRenderPassDescriptor *pass;
    static bool initialized = false;

    void shutdown() {
        if (initialized) { MetalAtlas::shutdown(); ImGui_ImplMetal_Shutdown(); }
        initialized = false;
        commands = nil;
        pass = nil;
        device = nil;
    }

    void prepare(const jlong *handles) {
        @autoreleasepool {
            using FrameCommands = id<MTLCommandBuffer> (*)(void *);
            auto frame = reinterpret_cast<FrameCommands>(handles[0]);
            if (!frame || !handles[1] || !handles[2]) throw std::runtime_error("Invalid mcopt Metal context");
            auto incoming = frame(reinterpret_cast<void *>(handles[1]));
            auto texture = (__bridge id<MTLTexture>)reinterpret_cast<void *>(handles[2]);
            if (!incoming || !texture || incoming.device != texture.device) throw std::runtime_error("mcopt returned incompatible Metal resources");
            if (!initialized || device != incoming.device) {
                shutdown();
                device = incoming.device;
                if (!ImGui_ImplMetal_Init(device)) throw std::runtime_error("Could not initialize the Metal overlay");
                initialized = true;
                pass = [MTLRenderPassDescriptor new];
                pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
                pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            }
            commands = incoming;
            pass.colorAttachments[0].texture = texture;
            ImGui_ImplMetal_NewFrame(pass);
            MetalAtlas::prepare(device);
        }
    }

    void render(ImDrawData *data, int width, int height) {
        if (!data || !data->TotalVtxCount || width <= 0 || height <= 0) return;
        @autoreleasepool {
            MetalAtlas::upload(commands);
            auto encoder = [commands renderCommandEncoderWithDescriptor:pass];
            if (!encoder) throw std::runtime_error("Could not record the Metal overlay");
            encoder.label = @"Seedy";
            flipDrawData(data,height);
            try { ImGui_ImplMetal_RenderDrawData(data,commands,encoder); }
            catch (...) { flipDrawData(data,height); [encoder endEncoding]; throw; }
            flipDrawData(data,height);
            [encoder endEncoding];
            commands = nil;
        }
    }
}

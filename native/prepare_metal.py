from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()
patches = [
    ('#pragma mark - Dear ImGui Metal Backend API', 'static ImTextureID seedyNearestTexture = 0;\nextern "C" void seedy_imgui_metal_nearest(ImTextureID texture) { seedyNearestTexture = texture; }\n\n#pragma mark - Dear ImGui Metal Backend API'),
    ('                if (ImTextureID tex_id = pcmd->GetTexID())', '                uint32_t nearest = pcmd->GetTexID() == seedyNearestTexture;\n                [commandEncoder setFragmentBytes:&nearest length:sizeof(nearest) atIndex:0];\n                if (ImTextureID tex_id = pcmd->GetTexID())'),
    ('texture2d<half, access::sample> texture [[texture(0)]])', 'texture2d<half, access::sample> texture [[texture(0)]], constant uint& nearest [[buffer(0)]])'),
    ('    "    half4 texColor = texture.sample(linearSampler, in.texCoords);\\n"', '    "    constexpr sampler nearestSampler(coord::normalized, min_filter::linear, mag_filter::nearest, mip_filter::none);\\n"\n    "    half4 texColor = nearest ? texture.sample(nearestSampler, in.texCoords) : texture.sample(linearSampler, in.texCoords);\\n"'),
]
for old, new in patches:
    if source.count(old) != 1:
        raise SystemExit("Unexpected Metal backend source")
    source = source.replace(old,new,1)
target = Path(sys.argv[2])
if not target.exists() or target.read_text() != source:
    target.write_text(source)

package dev.seedy;

import dev.seedy.mixin.SeedyMixinPlugin;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class McoptCompatibilityTest {
    @Test void absentMcoptLeavesVanillaMixinsEnabled() {
        var plugin = new SeedyMixinPlugin();
        assertTrue(plugin.shouldApplyMixin("vulkan.Target","dev.seedy.mixin.VulkanSurfaceMixin"));
        assertFalse(plugin.shouldApplyMixin("mcopt.metal.MetalEncoder","dev.seedy.mixin.McoptEncoderMixin"));
        assertFalse(plugin.shouldApplyMixin("mcopt.metal.MetalDevice","dev.seedy.mixin.McoptDeviceMixin"));
        assertFalse(plugin.shouldApplyMixin("mcopt.metal.MetalTexture$View","dev.seedy.mixin.McoptTextureAccess"));
    }

    @Test void publishedMcoptPreservesTheFrameIntegrationContract() throws Exception {
        String location = System.getProperty("seedy.mcoptJar");
        assumeTrue(location != null);
        try (var jar = new JarFile(Path.of(location).toFile())) {
            var encoder = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry("mcopt/metal/MetalEncoder.class")).readAllBytes());
            for (String field : List.of("enc","submitIndex")) assertTrue(encoder.fields().stream().anyMatch(value -> value.fieldName().equalsString(field) && value.fieldType().equalsString("J")));
            for (String name : List.of("presentTexture","presentAcquire")) {
                var method = encoder.methods().stream().filter(value -> value.methodName().equalsString(name)).findFirst().orElseThrow();
                assertEquals("(JLcom/mojang/renderpearl/api/textures/GpuTextureView;)V",method.methodType().stringValue());
                var calls = method.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
                var clears = calls.stream().filter(value -> value.owner().asInternalName().equals("mcopt/metal/MetalEncoder") && value.name().equalsString("flushClear") && value.type().equalsString("(Lcom/mojang/renderpearl/api/textures/GpuTexture;)V")).toList();
                assertEquals(1,clears.size());
                int clear = calls.indexOf(clears.getFirst());
                assertTrue(calls.subList(clear + 1,calls.size()).stream().anyMatch(value -> value.owner().asInternalName().equals("mcopt/metal/Native") && value.name().stringValue().startsWith("present")));
            }
            var view = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry("mcopt/metal/MetalTexture$View.class")).readAllBytes());
            assertTrue(view.fields().stream().anyMatch(value -> value.fieldName().equalsString("handle") && value.fieldType().equalsString("J")));
            var nativeType = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry("mcopt/metal/Native.class")).readAllBytes());
            assertTrue(nativeType.fields().stream().anyMatch(value -> value.fieldName().equalsString("LIB") && value.fieldType().equalsString("Ljava/lang/foreign/SymbolLookup;")));
        }
    }
}

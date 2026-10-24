package dev.seedy;

import dev.seedy.mixin.VulkanSurfaceMixin;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverlayPerformanceTest {
    private static List<String> calls(Class<?> type, String name) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.','/') + ".class")) {
            assertNotNull(input);
            var method = ClassFile.of().parse(input.readAllBytes()).methods().stream().filter(value -> value.methodName().equalsString(name)).findFirst().orElseThrow();
            return method.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(value -> value.owner().asInternalName() + "." + value.name().stringValue()).toList();
        }
    }

    @Test void overlayRecordsIntoMinecraftWithoutForcingASubmission() throws Exception {
        var calls = calls(VulkanSurfaceMixin.class,"seedyBeforeBlit");
        assertTrue(calls.stream().anyMatch(value -> value.endsWith(".allocateAndBeginTransientCommandBuffer")));
        assertTrue(calls.stream().anyMatch(value -> value.endsWith("VulkanCommandEncoder.execute")));
        assertTrue(calls.stream().anyMatch(value -> value.endsWith(".seedySubmitIndex")));
        assertFalse(calls.stream().anyMatch(value -> value.endsWith("VulkanCommandEncoder.submit")));
    }

    @Test void engineSubmissionLifetimeProtectsTheThreeNativeBufferSlots() throws Exception {
        Class<?> encoder;
        try { encoder = Class.forName("com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder"); }
        catch (ClassNotFoundException ignored) { encoder = Class.forName("com.mojang.blaze3d.vulkan.VulkanCommandEncoder"); }
        assertTrue(encoder.getDeclaredField("MAX_SUBMITS_IN_FLIGHT").getInt(null) < 3);
        assertEquals(long.class,encoder.getDeclaredField("currentSubmitIndex").getType());
        var execute = calls(encoder,"execute");
        assertFalse(execute.stream().anyMatch(value -> value.endsWith(".submit")));
        assertTrue(execute.stream().anyMatch(value -> value.endsWith(".executeCommands")));
        java.util.Set<?> extensions;
        try {
            var features = Class.forName(encoder.getPackageName() + ".VulkanFeatureSets");
            var required = features.getField("REQUIRED_FEATURESET").get(null);
            extensions = (java.util.Set<?>)required.getClass().getMethod("extensions").invoke(required);
        } catch (ClassNotFoundException ignored) {
            var backend = Class.forName(encoder.getPackageName() + ".VulkanBackend");
            extensions = (java.util.Set<?>)backend.getField("REQUIRED_DEVICE_EXTENSIONS").get(null);
        }
        assertTrue(extensions.contains("VK_KHR_dynamic_rendering"));
    }

    @Test void chunkEventsAndHudSnapshotsAvoidFullPanelWork() throws Exception {
        var loading = calls(ObservationCollector.class,"loaded");
        assertFalse(loading.stream().anyMatch(value -> value.endsWith("ObservationCollector.scan") || value.endsWith("ObservationCollector.scanTemplates")));
        assertTrue(loading.stream().anyMatch(value -> value.endsWith("ObservationCollector.schedule")));
        assertFalse(calls(Workbench.class,"refresh").stream().anyMatch(value -> value.endsWith(".publish")));
        var hud = calls(Workbench.class,"hudState");
        assertFalse(hud.stream().anyMatch(value -> value.endsWith("LootCatalog.items") || value.endsWith(".toJsonTree") || value.endsWith(".publishPanel")));
    }
    @Test void reusableProjectionStoragePreservesReturnedGeometry() {
        var matrix = new org.joml.Matrix4f();
        var object = new LoadedObjects.ObjectPosition("Matching loot chest",0,0,0);
        var expected = new java.util.ArrayList<Double>();
        OutlineProjection.box(matrix,0,0,0,100,100,2,expected);
        var first = OutlineProjection.project(matrix,List.of(object),0,0,0,100,100);
        assertArrayEquals(expected.stream().mapToDouble(Double::doubleValue).toArray(),first);
        var saved = first.clone();
        assertEquals(0,OutlineProjection.project(matrix,List.of(),0,0,0,100,100).length);
        OutlineProjection.project(matrix,List.of(object),1,0,0,100,100);
        assertArrayEquals(saved,first);
    }

}

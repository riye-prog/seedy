package dev.seedy;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlsTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void panelAndOverlayRegisterUnderOneMinecraftCategory() {
        var controls = assertDoesNotThrow(SeedyClient::createControls);
        assertSame(controls.panel().getCategory(),controls.overlay().getCategory());
        assertEquals(InputConstants.KEY_F8,controls.panel().getDefaultKey().getValue());
        assertEquals(InputConstants.KEY_F9,controls.overlay().getDefaultKey().getValue());
        assertEquals(InputConstants.KEY_F10,controls.minimap().getDefaultKey().getValue());
        assertSame(controls.panel().getCategory(),controls.minimap().getCategory());
        assertNotSame(controls.panel(),controls.overlay());
    }
}

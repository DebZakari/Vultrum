package dev.vultrum.cull;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputePathTest {
    @Test void minimumDeviceDoesNotNeedSubgroupArithmetic() {
        assertEquals(new ComputePath(false, 128), ComputePath.choose(128, 128, false));
        assertEquals("occlusion_cull_portable", ComputePath.choose(128, 128, false).cullShader());
        assertEquals("occlusion_hiz_128", ComputePath.choose(128, 128, false).hizShader());
    }

    @Test void subgroupAndWorkgroupCapabilitiesAreIndependent() {
        assertEquals(new ComputePath(false, 256), ComputePath.choose(1024, 1024, false));
        assertEquals(new ComputePath(true, 128), ComputePath.choose(128, 1024, true));
        assertEquals(new ComputePath(true, 128), ComputePath.choose(1024, 128, true));
        assertEquals(new ComputePath(true, 256), ComputePath.choose(1024, 1024, true));
    }

    @Test void unsupportedLimitsDoNotSelectAnOversizedShader() {
        assertNull(ComputePath.choose(127, 1024, true));
        assertNull(ComputePath.choose(1024, 64, false));
    }
}

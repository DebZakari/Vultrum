package dev.vultrum.cull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CullFaultsTest {
    @TempDir Path directory;

    @Test void productionIgnoresEvenInvalidFaultFixtures() throws Exception {
        Path file = directory.resolve("fault");
        Files.writeString(file, "after-phase1-draw:invalid");
        var faults = CullFaults.read(file, false);
        assertDoesNotThrow(() -> faults.hit("after-phase1-draw"));
        assertFalse(faults.checkRecovery());
    }

    @Test void benchmarkFaultIsDelayedAndFiresOnlyOnceAtItsPoint() throws Exception {
        Path file = directory.resolve("fault");
        Files.writeString(file, "after-phase1-draw:2");
        var faults = CullFaults.read(file, true);
        faults.hit("different");
        assertDoesNotThrow(() -> faults.hit("after-phase1-draw"));
        assertThrows(IllegalStateException.class, () -> faults.hit("after-phase1-draw"));
        assertDoesNotThrow(() -> faults.hit("after-phase1-draw"));
    }
}

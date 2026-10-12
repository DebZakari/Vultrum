package dev.vultrum.cull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CullPipelinesTest {
    static class Driver implements CullPipelines.Driver {
        final Set<Long> live = new HashSet<>();
        int created, fail;
        Runnable retirement;
        long create() {
            if (++created == fail) throw new IllegalStateException("creation " + fail);
            live.add((long) created); return created;
        }
        public long descriptorLayout() { return create(); }
        public long pipelineLayout(long descriptors) { assertTrue(live.contains(descriptors)); return create(); }
        public long shader(String name) { return create(); }
        public long pipeline(long layout, long shader) {
            assertTrue(live.contains(layout)); assertTrue(live.contains(shader)); return create();
        }
        void destroy(long handle) { assertTrue(live.remove(handle), "exactly one destruction of " + handle); }
        public void destroyShader(long handle) { destroy(handle); }
        public void destroyPipeline(long handle) { destroy(handle); }
        public void destroyPipelineLayout(long handle) { destroy(handle); }
        public void destroyDescriptorLayout(long handle) { destroy(handle); }
        public void retire(Runnable destroy) { assertNull(retirement); retirement = destroy; }
    }

    @TestFactory Stream<DynamicTest> everyPartialConstructionCleansAllOwnedHandles() {
        return IntStream.rangeClosed(1, 6).mapToObj(fail -> DynamicTest.dynamicTest("creation " + fail, () -> {
            var driver = new Driver(); driver.fail = fail;
            assertThrows(IllegalStateException.class, () -> CullPipelines.create(driver, new ComputePath(true, 256)));
            assertTrue(driver.live.isEmpty());
            assertNull(driver.retirement); // unpublished handles need no submit wait
        }));
    }

    @Test void publishedPipelinesRetireOnceAndSurviveUntilSubmitCompletion() {
        var driver = new Driver();
        var pipelines = CullPipelines.create(driver, new ComputePath(false, 128));
        assertEquals(Set.of(1L, 2L, 4L, 6L), driver.live); // shader modules already gone
        pipelines.close(); pipelines.close();
        assertEquals(4, driver.live.size());
        driver.retirement.run(); // encoder's submit-completion callback
        assertTrue(driver.live.isEmpty());
    }
}

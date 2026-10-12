package dev.vultrum.cull;

/** Owns raw Vulkan handles, including failed construction. Published handles retire after their submit. */
final class CullPipelines {
    interface Driver {
        long descriptorLayout();
        long pipelineLayout(long descriptors);
        long shader(String name);
        long pipeline(long layout, long shader);
        void destroyShader(long shader);
        void destroyPipeline(long pipeline);
        void destroyPipelineLayout(long layout);
        void destroyDescriptorLayout(long layout);
        void retire(Runnable destroy);
    }

    final long descriptors, layout, hiz, cull;
    private final Driver driver;
    private boolean closed;

    private CullPipelines(Driver driver, long descriptors, long layout, long hiz, long cull) {
        this.driver = driver;
        this.descriptors = descriptors;
        this.layout = layout;
        this.hiz = hiz;
        this.cull = cull;
    }

    static CullPipelines create(Driver driver, ComputePath path) {
        long descriptors = 0, layout = 0, hiz = 0;
        try {
            descriptors = driver.descriptorLayout();
            layout = driver.pipelineLayout(descriptors);
            hiz = compute(driver, layout, path.hizShader());
            long cull = compute(driver, layout, path.cullShader());
            return new CullPipelines(driver, descriptors, layout, hiz, cull);
        } catch (RuntimeException | Error failure) {
            // None of these handles has been recorded in a command buffer yet.
            if (hiz != 0) driver.destroyPipeline(hiz);
            if (layout != 0) driver.destroyPipelineLayout(layout);
            if (descriptors != 0) driver.destroyDescriptorLayout(descriptors);
            throw failure;
        }
    }

    private static long compute(Driver driver, long layout, String name) {
        long shader = driver.shader(name);
        try { return driver.pipeline(layout, shader); }
        finally { driver.destroyShader(shader); }
    }

    void close() {
        if (closed) return;
        closed = true;
        driver.retire(() -> {
            driver.destroyPipeline(cull);
            driver.destroyPipeline(hiz);
            driver.destroyPipelineLayout(layout);
            driver.destroyDescriptorLayout(descriptors);
        });
    }
}

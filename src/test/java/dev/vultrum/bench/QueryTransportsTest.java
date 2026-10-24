package dev.vultrum.bench;

import com.mojang.renderpearl.api.commands.RenderPass;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryTransportsTest {
    static final class Timestamps implements GpuTimer.Queries {
        final long[] values = new long[8 * 32];
        final boolean[] ready = new boolean[8];
        int writes;
        long tick;
        public void write(int query) { values[query] = tick++; writes++; }
        public OptionalLong[] read(int first, int count) {
            var result = new OptionalLong[count];
            Arrays.setAll(result, i -> ready[first / 32] ? OptionalLong.of(values[first + i]) : OptionalLong.empty());
            return result;
        }
    }
    @Test
    void timestampBusySlotDropsNewSampleWithoutResettingOldQueries() {
        var queries = new Timestamps(); var timer = new GpuTimer(queries, 1);
        var received = new ArrayList<GpuTimer.Sample>();
        timer.beginFrame(1, 10, received::add); timer.endFrame();
        assertEquals(2, queries.writes);
        timer.beginFrame(9, 11, received::add); timer.endFrame();
        assertEquals(2, queries.writes);
        assertEquals(9, received.getFirst().frame()); assertEquals(1, received.getFirst().dropped());
        queries.ready[1] = true; timer.collect(received::add);
        assertEquals(1, received.getLast().frame()); assertEquals(10, received.getLast().window());
        timer.beginFrame(17, 12, received::add); timer.endFrame();
        assertEquals(4, queries.writes);
    }
    @Test
    void timestampSlotOrderAndOverflowAreExplicitInSamples() {
        var queries = new Timestamps(); var timer = new GpuTimer(queries, .5);
        var received = new ArrayList<GpuTimer.Sample>();
        timer.beginFrame(7, 1, received::add);
        for (int i = 0; i < 16; i++) { timer.beginSpan(); timer.endSpan(); }
        timer.endFrame();
        timer.beginFrame(8, 1, received::add); timer.endFrame();
        Arrays.fill(queries.ready, true); timer.collect(received::add);
        assertEquals(List.of(8L, 7L), received.stream().map(GpuTimer.Sample::frame).toList());
        assertEquals(1, received.getLast().overflowed());
        assertEquals(15.5, received.getLast().frameMs());
        assertEquals(7.5, received.getLast().terrainMs());
    }

    static final class Counters implements PipelineStats.Queries {
        boolean ready;
        int resets, begins;
        public boolean begin(int query, RenderPass pass) { begins++; return true; }
        public void end(int query) {}
        public long[] read(int first, int count) {
            if (!ready) return null;
            var values = new long[count * 5]; Arrays.fill(values, 12); return values;
        }
        public void reset(int first, int count) { resets++; }
    }
    @Test
    void pipelineBusySlotRetainsOriginalIdentityAndWaitsBeforeReset() {
        var queries = new Counters(); var stats = new PipelineStats(queries);
        var received = new ArrayList<PipelineStats.Sample>();
        stats.beginFrame(1, 10, received::add); stats.begin(null, "SOLID"); stats.endFrame(received::add);
        stats.collect(received::add); assertEquals(0, queries.resets);
        stats.beginFrame(9, 11, received::add); stats.begin(null, "SOLID"); stats.endFrame(received::add);
        assertEquals(1, queries.begins); assertEquals(0, queries.resets);
        assertEquals(9, received.getFirst().frame()); assertEquals(1, received.getFirst().dropped());
        queries.ready = true; stats.collect(received::add);
        assertEquals(1, queries.resets); assertEquals(1, received.getLast().frame()); assertEquals(10, received.getLast().window());
        assertEquals(12, received.getLast().calls().getFirst().values()[0]);
    }
    @Test
    void zeroDrawPipelineFrameCompletesAndOverflowIsReported() {
        var queries = new Counters(); var stats = new PipelineStats(queries);
        var received = new ArrayList<PipelineStats.Sample>();
        stats.beginFrame(1, 1, received::add); stats.endFrame(received::add);
        assertEquals(List.of(), received.getFirst().calls()); assertEquals(0, received.getFirst().dropped());
        stats.beginFrame(2, 1, received::add);
        for (int i = 0; i < 17; i++) { stats.begin(null, "SOLID"); stats.end(); }
        stats.endFrame(received::add); queries.ready = true; stats.collect(received::add);
        assertEquals(16, received.getLast().calls().size()); assertEquals(1, received.getLast().dropped());
    }
}

package dev.vultrum.upload;

import it.unimi.dsi.fastutil.longs.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationQueueTest {
    private long now;
    private final RegistrationQueue queue = new RegistrationQueue(10, () -> now);
    private final LongArrayList added = new LongArrayList();
    private LongSet set(long... keys) { return new LongLinkedOpenHashSet(keys); }
    private void add(long key) { added.add(key); now += 6; }

    @Test void initialEventsAreTransferredAndBudgetContinuesWithoutDuplicates() {
        LongSet ready=set(1,2,3,4), loads=set(1,2,8);
        queue.seed(ready, loads);
        assertEquals(set(8), loads);
        assertEquals(2, queue.drain(ready, this::add));
        assertEquals(2, queue.pending());
        assertEquals(2, queue.drain(ready, this::add));
        assertEquals(new LongArrayList(new long[]{1,2,3,4}), added);
    }
    @Test void removalBeforeRegistrationIsCancelledWithoutAnUnloadCallback() {
        queue.seed(set(1,2),set());
        LongSet unloads=set(1,9);
        queue.events(set(),unloads);
        assertEquals(set(9),unloads);
        queue.drain(set(2),this::add);
        assertEquals(new LongArrayList(new long[]{2}),added);
    }
    @Test void freshStreamingAddSupersedesPendingInitialRegistration() {
        queue.seed(set(1,2),set());
        LongSet loads=set(1,9);
        queue.events(loads,set());
        assertEquals(set(1,9),loads);
        queue.drain(set(1,2),this::add);
        assertEquals(new LongArrayList(new long[]{2}),added);
    }
    @Test void readinessIsCheckedAtExecutionAndAReplacementDropsOldWork() {
        queue.seed(set(1,2),set());
        queue.drain(set(),this::add);
        assertTrue(added.isEmpty()); assertEquals(0,queue.pending());
        queue.seed(set(1,2),set()); queue.reset();
        queue.seed(set(8),set()); queue.drain(set(8),this::add);
        assertEquals(new LongArrayList(new long[]{8}),added);
    }
    @Test void anExpensiveColumnStillMakesProgress() {
        RegistrationQueue q=new RegistrationQueue(1,()->now);
        q.seed(set(1,2),set());
        assertEquals(1,q.drain(set(1,2),this::add));
        assertEquals(1,q.pending());
    }
}

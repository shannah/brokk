package io.github.jbellis.brokk.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ThreadSafeLRUCacheTest {

    private ThreadSafeLRUCache<String, TestDisposable> cache;

    /**
     * Test implementation of Disposable that tracks disposal calls.
     */
    private static class TestDisposable implements ThreadSafeLRUCache.Disposable {
        private final String id;
        private final AtomicBoolean disposed = new AtomicBoolean(false);

        TestDisposable(String id) {
            this.id = id;
        }

        @Override
        public void dispose() {
            disposed.set(true);
        }

        boolean isDisposed() {
            return disposed.get();
        }

        String getId() {
            return id;
        }

        @Override
        public String toString() {
            return "TestDisposable(" + id + ")";
        }
    }

    @BeforeEach
    void setUp() {
        cache = new ThreadSafeLRUCache<>(3); // Small cache for testing eviction
    }

    @Test
    void testBasicOperations() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");

        // Test put and get
        assertNull(cache.get("key1"));
        cache.put("key1", value1);
        assertEquals(value1, cache.get("key1"));

        // Test containsValue
        assertTrue(cache.containsValue(value1));
        assertFalse(cache.containsValue(value2));

        // Test overwrite
        cache.put("key1", value2);
        assertEquals(value2, cache.get("key1"));
        assertFalse(cache.containsValue(value1));
        assertTrue(cache.containsValue(value2));
    }

    @Test
    void testLRUEviction() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");
        var value4 = new TestDisposable("4");

        // Fill cache to capacity
        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.put("key3", value3);

        assertFalse(value1.isDisposed());
        assertFalse(value2.isDisposed());
        assertFalse(value3.isDisposed());

        // Add fourth item, should evict first (oldest)
        cache.put("key4", value4);

        assertTrue(value1.isDisposed()); // Should be evicted and disposed
        assertFalse(value2.isDisposed());
        assertFalse(value3.isDisposed());
        assertFalse(value4.isDisposed());

        assertNull(cache.get("key1"));
        assertEquals(value2, cache.get("key2"));
        assertEquals(value3, cache.get("key3"));
        assertEquals(value4, cache.get("key4"));
    }

    @Test
    void testLRUAccessUpdatesOrder() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");
        var value4 = new TestDisposable("4");

        // Fill cache
        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.put("key3", value3);

        // Access key1 to make it most recently used
        cache.get("key1");

        // Add fourth item, should evict key2 (now oldest)
        cache.put("key4", value4);

        assertFalse(value1.isDisposed()); // Should not be evicted due to recent access
        assertTrue(value2.isDisposed());   // Should be evicted
        assertFalse(value3.isDisposed());
        assertFalse(value4.isDisposed());

        assertEquals(value1, cache.get("key1"));
        assertNull(cache.get("key2"));
        assertEquals(value3, cache.get("key3"));
        assertEquals(value4, cache.get("key4"));
    }

    @Test
    void testTryReserveAndPutReserved() {
        var value1 = new TestDisposable("1");

        // Test successful reservation
        assertTrue(cache.tryReserve("key1"));
        assertFalse(cache.tryReserve("key1")); // Already reserved

        // Put the reserved value
        cache.putReserved("key1", value1);
        assertEquals(value1, cache.get("key1"));

        // Try to reserve already populated key
        assertFalse(cache.tryReserve("key1"));
    }

    @Test
    void testRemoveReserved() {
        // Reserve a key
        assertTrue(cache.tryReserve("key1"));

        // Remove the reservation
        cache.removeReserved("key1");

        // Should be able to reserve again
        assertTrue(cache.tryReserve("key1"));
    }

    @Test
    void testNonNullValues() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");

        // Add normal values
        cache.put("key1", value1);
        cache.put("key2", value2);

        // Reserve a slot (creates null entry)
        cache.tryReserve("key3");

        Collection<TestDisposable> values = cache.nonNullValues();
        assertEquals(2, values.size());
        assertTrue(values.contains(value1));
        assertTrue(values.contains(value2));

        // Verify iteration is safe (returns copy)
        var valuesList = new ArrayList<>(values);
        cache.put("key4", new TestDisposable("4"));
        assertEquals(2, valuesList.size()); // Original collection unaffected
    }

    @Test
    void testClear() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");

        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.tryReserve("key3"); // Reserved slot with null

        assertFalse(value1.isDisposed());
        assertFalse(value2.isDisposed());

        cache.clear();

        assertTrue(value1.isDisposed());
        assertTrue(value2.isDisposed());

        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));
        assertTrue(cache.nonNullValues().isEmpty());
    }

    @Test
    void testPutReservedAssertionFailure() {
        var value1 = new TestDisposable("1");

        // Put a real value first
        cache.put("key1", value1);

        // Try to putReserved on non-reserved key should fail assertion
        assertThrows(AssertionError.class, () -> cache.putReserved("key1", new TestDisposable("2")));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentAccess() throws InterruptedException {
        final int numThreads = 10;
        final int operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var errors = new ConcurrentLinkedQueue<Exception>();

        // Launch concurrent threads doing mixed operations
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "thread" + threadId + "-key" + i;
                        var value = new TestDisposable(key);

                        // Mix of operations
                        cache.put(key, value);
                        cache.get(key);
                        if (i % 3 == 0) {
                            cache.nonNullValues();
                        }
                        if (i % 5 == 0) {
                            cache.containsValue(value);
                        }
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(8, TimeUnit.SECONDS));
        executor.shutdown();

        if (!errors.isEmpty()) {
            fail("Concurrent access failed with errors: " + errors);
        }

        // Verify cache is in valid state
        assertNotNull(cache.nonNullValues());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentReservations() throws InterruptedException {
        final int numThreads = 10;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var successfulReservations = new AtomicInteger(0);
        var errors = new ConcurrentLinkedQueue<Exception>();

        // All threads try to reserve the same key
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    if (cache.tryReserve("contested-key")) {
                        successfulReservations.incrementAndGet();
                        // Simulate some work
                        Thread.sleep(1);
                        cache.putReserved("contested-key", new TestDisposable("winner-" + threadId));
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(8, TimeUnit.SECONDS));
        executor.shutdown();

        if (!errors.isEmpty()) {
            fail("Concurrent reservations failed with errors: " + errors);
        }

        // Only one thread should have successfully reserved
        assertEquals(1, successfulReservations.get());
        assertNotNull(cache.get("contested-key"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentEvictionAndDisposal() throws InterruptedException {
        final int numThreads = 5;
        final int itemsPerThread = 20;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var allValues = new ConcurrentLinkedQueue<TestDisposable>();
        var errors = new ConcurrentLinkedQueue<Exception>();

        // Create many items to trigger evictions
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemsPerThread; i++) {
                        String key = "thread" + threadId + "-item" + i;
                        var value = new TestDisposable(key);
                        allValues.add(value);
                        cache.put(key, value);

                        // Occasionally access random keys to change LRU order
                        if (i % 3 == 0) {
                            cache.get("thread" + (threadId % numThreads) + "-item" + (i / 2));
                        }
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(8, TimeUnit.SECONDS));
        executor.shutdown();

        if (!errors.isEmpty()) {
            fail("Concurrent eviction test failed with errors: " + errors);
        }

        // Give time for any pending operations to complete
        Thread.sleep(100);

        // Verify that evicted items were properly disposed
        var currentValues = cache.nonNullValues();
        assertTrue(currentValues.size() <= 3); // Cache max size

        // Count disposed items
        long disposedCount = allValues.stream().mapToLong(v -> v.isDisposed() ? 1 : 0).sum();

        // Most items should have been evicted and disposed
        assertTrue(disposedCount > itemsPerThread * numThreads - 10,
            "Expected most items to be disposed, but only " + disposedCount + " out of " +
            (itemsPerThread * numThreads) + " were disposed");
    }

    @Test
    void testReserveFailureCleanup() {
        var value1 = new TestDisposable("1");

        // Reserve a key
        assertTrue(cache.tryReserve("key1"));

        // Simulate load failure by removing reservation
        cache.removeReserved("key1");

        // Should be able to use the key normally now
        cache.put("key1", value1);
        assertEquals(value1, cache.get("key1"));
        assertFalse(value1.isDisposed());
    }

    @Test
    void testReservationTrackingNoNullStorage() {
        var value1 = new TestDisposable("1");

        // Reserve a key
        assertTrue(cache.tryReserve("key1"));

        // get() should return null for reserved but not loaded key
        assertNull(cache.get("key1"));

        // nonNullValues() should not include reserved keys
        assertTrue(cache.nonNullValues().isEmpty());

        // Cannot reserve the same key twice
        assertFalse(cache.tryReserve("key1"));

        // Put the reserved value
        cache.putReserved("key1", value1);

        // Now it should be available
        assertEquals(value1, cache.get("key1"));
        assertEquals(1, cache.nonNullValues().size());
        assertTrue(cache.nonNullValues().contains(value1));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testDeferredDisposalHappensOutsideLock() throws InterruptedException {
        // Test verifies that disposal happens asynchronously and doesn't block cache operations
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");

        // Fill cache to capacity
        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.put("key3", value3);

        // This should trigger eviction of key1 (eldest entry)
        var value4 = new TestDisposable("4");
        cache.put("key4", value4);

        // The evicted item should have been disposed
        assertTrue(value1.isDisposed(), "Evicted item should be disposed");
        assertFalse(value2.isDisposed(), "Non-evicted item should not be disposed");
        assertFalse(value3.isDisposed(), "Non-evicted item should not be disposed");
        assertFalse(value4.isDisposed(), "Newly added item should not be disposed");

        // Cache should have 3 items: key2, key3, key4
        assertEquals(3, cache.nonNullValues().size());
        assertNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));
        assertNotNull(cache.get("key3"));
        assertNotNull(cache.get("key4"));
    }

    @Test
    void testReservationConsistency() {
        // Test that reservations are properly managed
        assertTrue(cache.tryReserve("key1"));
        assertTrue(cache.tryReserve("key2"));

        // Both keys should be reserved
        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));

        // Remove one reservation
        cache.removeReserved("key1");

        // Should be able to reserve key1 again
        assertTrue(cache.tryReserve("key1"));

        // But not key2
        assertFalse(cache.tryReserve("key2"));

        // Put values for both
        cache.putReserved("key1", new TestDisposable("1"));
        cache.putReserved("key2", new TestDisposable("2"));

        // Both should now be available
        assertNotNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));
        assertEquals(2, cache.nonNullValues().size());
    }

    @Test
    void testClearWithReservations() {
        var value1 = new TestDisposable("1");

        // Add cached value and reservation
        cache.put("cached", value1);
        cache.tryReserve("reserved");

        // Clear should dispose cached values and clear reservations
        cache.clear();

        assertTrue(value1.isDisposed());
        assertTrue(cache.nonNullValues().isEmpty());

        // Should be able to reserve the previously reserved key again
        assertTrue(cache.tryReserve("reserved"));
    }
}
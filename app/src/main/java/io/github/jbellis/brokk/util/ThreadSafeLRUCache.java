package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe LRU cache implementation with proper synchronization and disposal callbacks.
 * Uses ReentrantReadWriteLock for fine-grained concurrency control.
 *
 * Features:
 * - Deferred disposal pattern: dispose() calls happen outside of locks
 * - Reservation tracking: eliminates temporary null storage for better type safety
 * - Non-blocking disposal: other cache operations can proceed during disposal
 *
 * @param <K> the key type
 * @param <V> the value type, must implement Disposable interface
 */
public class ThreadSafeLRUCache<K, V extends ThreadSafeLRUCache.Disposable> {
    private static final Logger logger = LogManager.getLogger(ThreadSafeLRUCache.class);

    /**
     * Interface for objects that can be disposed when evicted from cache.
     */
    public interface Disposable {
        void dispose();
    }

    private final int maxSize;
    private final LinkedHashMap<K, V> cache;
    private final Set<K> reservedKeys = new HashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public ThreadSafeLRUCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(maxSize + 1, 0.75f, true);
    }

    @Nullable
    public V get(K key) {
        readLock.lock();
        try {
            // Return null if key is reserved but not yet loaded
            if (reservedKeys.contains(key)) {
                return null;
            }
            return cache.get(key);
        } finally {
            readLock.unlock();
        }
    }

    @Nullable
    public V put(K key, V value) {
        // Collect items to dispose outside of lock
        var toDispose = new ArrayList<V>();
        @Nullable V previousValue;

        writeLock.lock();
        try {
            // Check if we need to evict before adding
            if (cache.size() >= maxSize && !cache.containsKey(key) && !reservedKeys.contains(key)) {
                var eldest = cache.entrySet().iterator().next();
                logger.debug("Evicting entry from cache: key {}", eldest.getKey());
                var evictedValue = cache.remove(eldest.getKey());
                if (evictedValue != null) {
                    toDispose.add(evictedValue);
                }
            }

            // Remove from reserved keys if this was a reservation
            reservedKeys.remove(key);

            previousValue = cache.put(key, value);
        } finally {
            writeLock.unlock();
        }

        // Dispose outside of lock to avoid blocking other operations
        disposeDeferred(toDispose);

        return previousValue;
    }

    public boolean containsValue(V value) {
        readLock.lock();
        try {
            return cache.containsValue(value);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Atomic check-and-reserve operation to prevent double-loading.
     * Returns true if the key was not present and has been reserved.
     * Returns false if the key was already present (cached value exists) or already reserved.
     */
    public boolean tryReserve(K key) {
        writeLock.lock();
        try {
            if (cache.containsKey(key) || reservedKeys.contains(key)) {
                return false; // Already cached or reserved
            }
            // Reserve by adding to reservation set
            reservedKeys.add(key);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Replaces a reserved entry with the actual value.
     * Should only be called after successful tryReserve().
     */
    @Nullable
    public V putReserved(K key, V value) {
        writeLock.lock();
        try {
            // Ensure we're replacing a reserved entry
            assert reservedKeys.contains(key) : "putReserved called on non-reserved key";
            reservedKeys.remove(key);
            return cache.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes a reserved entry (used when loading fails).
     */
    public void removeReserved(K key) {
        writeLock.lock();
        try {
            reservedKeys.remove(key);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns all cached values, excluding reserved (not yet loaded) entries.
     */
    public java.util.Collection<V> nonNullValues() {
        readLock.lock();
        try {
            // Since we no longer store nulls, all cache values are non-null
            return new ArrayList<>(cache.values());
        } finally {
            readLock.unlock();
        }
    }

    public void clear() {
        // Collect items to dispose outside of lock
        var toDispose = new ArrayList<V>();

        writeLock.lock();
        try {
            // Collect all values for disposal
            toDispose.addAll(cache.values());

            // Clear both cache and reservations
            cache.clear();
            reservedKeys.clear();
        } finally {
            writeLock.unlock();
        }

        // Dispose outside of lock to avoid blocking other operations
        disposeDeferred(toDispose);
    }

    /**
     * Helper method to dispose items outside of locks to prevent blocking.
     * This allows other cache operations to proceed while disposal is happening.
     */
    private void disposeDeferred(java.util.Collection<V> items) {
        for (var item : items) {
            try {
                item.dispose();
            } catch (Exception e) {
                logger.warn("Exception during disposal of cache item", e);
            }
        }
    }
}

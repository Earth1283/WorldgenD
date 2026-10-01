package io.github.eath1283.worldgend;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

// Process-wide preliminary surface levels, one direct-mapped table per RandomState.
public final class DensitySurfaceCache {
    public static final int MISS = Integer.MIN_VALUE;
    private static final int BITS = 10;
    private static final int MASK = (1 << BITS) - 1;
    private static final long EMPTY = 0x8000L;
    private static final int COORD_LIMIT = 1 << 25;
    private static final boolean STATS = Boolean.getBoolean("orion.noiseCell.stats");
    private static final LongAdder hits = new LongAdder();
    private static final LongAdder misses = new LongAdder();
    private static final Map<Object, DensitySurfaceCache> caches = new WeakHashMap<>();
    private static volatile Last last;

    private record Last(Object owner, DensitySurfaceCache cache) {}

    private final AtomicLongArray table = new AtomicLongArray(1 << (2 * BITS));

    private DensitySurfaceCache() {
        for (int i = 0; i < table.length(); i++) table.setPlain(i, EMPTY);
    }

    public static DensitySurfaceCache forOwner(Object owner) {
        Last seen = last;
        if (seen != null && seen.owner == owner) return seen.cache;
        synchronized (caches) {
            DensitySurfaceCache cache = caches.computeIfAbsent(owner, key -> new DensitySurfaceCache());
            last = new Last(owner, cache);
            return cache;
        }
    }

    public int get(int blockX, int blockZ) {
        if (blockX < -COORD_LIMIT || blockX >= COORD_LIMIT || blockZ < -COORD_LIMIT || blockZ >= COORD_LIMIT) return MISS;
        int qx = blockX >> 2;
        int qz = blockZ >> 2;
        long entry = table.get((qx & MASK) | ((qz & MASK) << BITS));
        if ((entry >>> 16) == key(qx, qz) && (short) entry != (short) 0x8000) {
            if (STATS) hits.increment();
            return (short) entry;
        }
        if (STATS) misses.increment();
        return MISS;
    }

    public void put(int blockX, int blockZ, int level) {
        if (level <= Short.MIN_VALUE || level > Short.MAX_VALUE) return;
        if (blockX < -COORD_LIMIT || blockX >= COORD_LIMIT || blockZ < -COORD_LIMIT || blockZ >= COORD_LIMIT) return;
        int qx = blockX >> 2;
        int qz = blockZ >> 2;
        table.set((qx & MASK) | ((qz & MASK) << BITS), (key(qx, qz) << 16) | (level & 0xFFFFL));
    }

    private static long key(int qx, int qz) {
        return ((qx & 0xFFFFFFL) << 24) | (qz & 0xFFFFFFL);
    }

    public static String report() {
        return "hits=" + hits.sum() + " misses=" + misses.sum();
    }
}

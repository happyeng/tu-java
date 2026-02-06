package org.sngroup.verifier.npbdd;

import java.util.concurrent.ConcurrentHashMap;

public class BDDPredicateCache {

    private static final BDDPredicateCache INSTANCE = new BDDPredicateCache();

    private final ConcurrentHashMap<L1CacheKey, L1CacheValue> l1Cache;
    private final ConcurrentHashMap<L2CacheKey, Integer> l2EncodeCache;
    private final ConcurrentHashMap<L2CacheKey, L2CacheKey.L2HitResult> l2HitCache;
    private final ConcurrentHashMap<L2CacheKey, L2CacheKey.L2MergeResult> l2MergeCache;
    private final ConcurrentHashMap<L3CacheKey, Integer> l3Cache;

    private long l1Hits = 0;
    private long l1Misses = 0;
    private long l2EncodeHits = 0;
    private long l2EncodeMisses = 0;
    private long l2HitHits = 0;
    private long l2HitMisses = 0;
    private long l2MergeHits = 0;
    private long l2MergeMisses = 0;
    private long l3Hits = 0;
    private long l3Misses = 0;

    private static final int L1_CAPACITY = 5000;
    private static final int L2_CAPACITY = 20000;
    private static final int L3_CAPACITY = 50000;

    private BDDPredicateCache() {
        this.l1Cache = new ConcurrentHashMap<>(L1_CAPACITY);
        this.l2EncodeCache = new ConcurrentHashMap<>(L2_CAPACITY);
        this.l2HitCache = new ConcurrentHashMap<>(L2_CAPACITY);
        this.l2MergeCache = new ConcurrentHashMap<>(L2_CAPACITY);
        this.l3Cache = new ConcurrentHashMap<>(L3_CAPACITY);
    }

    public static BDDPredicateCache getInstance() {
        return INSTANCE;
    }

    public L1CacheValue getL1(L1CacheKey key) {
        L1CacheValue value = l1Cache.get(key);
        if (value != null) {
            l1Hits++;
            return value;
        }
        l1Misses++;
        return null;
    }

    public void putL1(L1CacheKey key, L1CacheValue value) {
        l1Cache.put(key, value);
    }

    public Integer getL2Encode(L2CacheKey key) {
        Integer value = l2EncodeCache.get(key);
        if (value != null) {
            l2EncodeHits++;
            return value;
        }
        l2EncodeMisses++;
        return null;
    }

    public void putL2Encode(L2CacheKey key, Integer value) {
        l2EncodeCache.put(key, value);
    }

    public L2CacheKey.L2HitResult getL2Hit(L2CacheKey key) {
        L2CacheKey.L2HitResult value = l2HitCache.get(key);
        if (value != null) {
            l2HitHits++;
            return value;
        }
        l2HitMisses++;
        return null;
    }

    public void putL2Hit(L2CacheKey key, L2CacheKey.L2HitResult value) {
        l2HitCache.put(key, value);
    }

    public L2CacheKey.L2MergeResult getL2Merge(L2CacheKey key) {
        L2CacheKey.L2MergeResult value = l2MergeCache.get(key);
        if (value != null) {
            l2MergeHits++;
            return value;
        }
        l2MergeMisses++;
        return null;
    }

    public void putL2Merge(L2CacheKey key, L2CacheKey.L2MergeResult value) {
        l2MergeCache.put(key, value);
    }

    public Integer getL3(L3CacheKey key) {
        Integer value = l3Cache.get(key);
        if (value != null) {
            l3Hits++;
            return value;
        }
        l3Misses++;
        return null;
    }

    public void putL3(L3CacheKey key, Integer value) {
        l3Cache.put(key, value);
    }

    public CacheStats getStats() {
        return new CacheStats(
            l1Hits, l1Misses, l1Cache.size(),
            l2EncodeHits, l2EncodeMisses,
            l2HitHits, l2HitMisses,
            l2MergeHits, l2MergeMisses,
            l2EncodeCache.size() + l2HitCache.size() + l2MergeCache.size(),
            l3Hits, l3Misses, l3Cache.size()
        );
    }

    public void resetStats() {
        l1Hits = 0;
        l1Misses = 0;
        l2EncodeHits = 0;
        l2EncodeMisses = 0;
        l2HitHits = 0;
        l2HitMisses = 0;
        l2MergeHits = 0;
        l2MergeMisses = 0;
        l3Hits = 0;
        l3Misses = 0;
    }

    public void clear() {
        l1Cache.clear();
        l2EncodeCache.clear();
        l2HitCache.clear();
        l2MergeCache.clear();
        l3Cache.clear();
        resetStats();
    }

    public void printStats() {
        CacheStats stats = getStats();
        System.out.println("=== BDD谓词缓存统计 ===");
        System.out.println("L1缓存 (完整规则转换):");
        System.out.println("  命中: " + stats.l1Hits + ", 未命中: " + stats.l1Misses);
        System.out.println("  命中率: " + String.format("%.2f%%", stats.getL1HitRate()));
        System.out.println("  大小: " + stats.l1Size);
        System.out.println("L2缓存 (网络语义操作):");
        System.out.println("  编码 - 命中: " + stats.l2EncodeHits + ", 未命中: " + stats.l2EncodeMisses);
        System.out.println("  Hit - 命中: " + stats.l2HitHits + ", 未命中: " + stats.l2HitMisses);
        System.out.println("  合并 - 命中: " + stats.l2MergeHits + ", 未命中: " + stats.l2MergeMisses);
        System.out.println("  命中率: " + String.format("%.2f%%", stats.getL2HitRate()));
        System.out.println("  大小: " + stats.l2Size);
        System.out.println("L3缓存 (基础BDD操作):");
        System.out.println("  命中: " + stats.l3Hits + ", 未命中: " + stats.l3Misses);
        System.out.println("  命中率: " + String.format("%.2f%%", stats.getL3HitRate()));
        System.out.println("  大小: " + stats.l3Size);
    }

    public static class CacheStats {
        public final long l1Hits;
        public final long l1Misses;
        public final int l1Size;
        public final long l2EncodeHits;
        public final long l2EncodeMisses;
        public final long l2HitHits;
        public final long l2HitMisses;
        public final long l2MergeHits;
        public final long l2MergeMisses;
        public final int l2Size;
        public final long l3Hits;
        public final long l3Misses;
        public final int l3Size;

        public CacheStats(long l1Hits, long l1Misses, int l1Size,
                         long l2EncodeHits, long l2EncodeMisses,
                         long l2HitHits, long l2HitMisses,
                         long l2MergeHits, long l2MergeMisses, int l2Size,
                         long l3Hits, long l3Misses, int l3Size) {
            this.l1Hits = l1Hits;
            this.l1Misses = l1Misses;
            this.l1Size = l1Size;
            this.l2EncodeHits = l2EncodeHits;
            this.l2EncodeMisses = l2EncodeMisses;
            this.l2HitHits = l2HitHits;
            this.l2HitMisses = l2HitMisses;
            this.l2MergeHits = l2MergeHits;
            this.l2MergeMisses = l2MergeMisses;
            this.l2Size = l2Size;
            this.l3Hits = l3Hits;
            this.l3Misses = l3Misses;
            this.l3Size = l3Size;
        }

        public double getL1HitRate() {
            long total = l1Hits + l1Misses;
            return total > 0 ? 100.0 * l1Hits / total : 0.0;
        }

        public double getL2HitRate() {
            long total = l2EncodeHits + l2EncodeMisses + l2HitHits + l2HitMisses + l2MergeHits + l2MergeMisses;
            long hits = l2EncodeHits + l2HitHits + l2MergeHits;
            return total > 0 ? 100.0 * hits / total : 0.0;
        }

        public double getL3HitRate() {
            long total = l3Hits + l3Misses;
            return total > 0 ? 100.0 * l3Hits / total : 0.0;
        }
    }
}
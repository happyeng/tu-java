package org.sngroup.verifier.npbdd;

import org.sngroup.verifier.TSBDD;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BDDPredicateRegistry {

    private static final BDDPredicateRegistry INSTANCE = new BDDPredicateRegistry();

    private final ConcurrentHashMap<BDDPredicate, Integer> predicateToId;
    private final ConcurrentHashMap<Integer, BDDPredicate> idToPredicate;
    private final AtomicInteger idCounter;
    private final AtomicLong totalPredicates = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    private static final int INITIAL_CAPACITY = 10000;
    private static final int STARTING_ID = 2;

    private BDDPredicateRegistry() {
        this.predicateToId = new ConcurrentHashMap<>(INITIAL_CAPACITY);
        this.idToPredicate = new ConcurrentHashMap<>(INITIAL_CAPACITY);
        this.idCounter = new AtomicInteger(STARTING_ID);
    }

    public static BDDPredicateRegistry getInstance() {
        return INSTANCE;
    }

    public int getOrCreateId(BDDPredicate predicate, TSBDD bdd) {
        Integer existingId = predicateToId.get(predicate);
        if (existingId != null) {
            cacheHits.incrementAndGet();
            return existingId;
        }

        synchronized (this) {
            existingId = predicateToId.get(predicate);
            if (existingId != null) {
                cacheHits.incrementAndGet();
                return existingId;
            }

            cacheMisses.incrementAndGet();

            for (java.util.Map.Entry<BDDPredicate, Integer> entry : predicateToId.entrySet()) {
                if (entry.getKey().structurallyEquals(predicate, bdd)) {
                    int id = entry.getValue();
                    predicateToId.put(predicate, id);
                    cacheHits.incrementAndGet();
                    return id;
                }
            }

            int newId = idCounter.getAndIncrement();
            totalPredicates.incrementAndGet();
            predicateToId.put(predicate, newId);
            idToPredicate.put(newId, predicate);
            return newId;
        }
    }

    public int getBDDNode(int predicateId) {
        BDDPredicate predicate = idToPredicate.get(predicateId);
        if (predicate == null) {
            return predicateId;
        }
        return predicate.getBddNode();
    }

    public BDDPredicate getPredicate(int predicateId) {
        return idToPredicate.get(predicateId);
    }

    public RegistryStats getStats() {
        return new RegistryStats(
            totalPredicates.get(),
            predicateToId.size(),
            cacheHits.get(),
            cacheMisses.get()
        );
    }

    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    public void clear() {
        predicateToId.clear();
        idToPredicate.clear();
        idCounter.set(STARTING_ID);
        resetStats();
        totalPredicates.set(0);
    }

    public void printStats() {
        RegistryStats stats = getStats();
        System.out.println("=== BDD谓词注册表统计 ===");
        System.out.println("总谓词数: " + stats.totalPredicates);
        System.out.println("当前注册: " + stats.currentSize);
        System.out.println("查找命中: " + stats.cacheHits);
        System.out.println("查找未命中: " + stats.cacheMisses);
        if (stats.cacheHits + stats.cacheMisses > 0) {
            System.out.println("命中率: " + String.format("%.2f%%", stats.getHitRate()));
        }
    }

    public static class RegistryStats {
        public final long totalPredicates;
        public final int currentSize;
        public final long cacheHits;
        public final long cacheMisses;

        public RegistryStats(long totalPredicates, int currentSize, long cacheHits, long cacheMisses) {
            this.totalPredicates = totalPredicates;
            this.currentSize = currentSize;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
        }

        public double getHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? 100.0 * cacheHits / total : 0.0;
        }
    }
}
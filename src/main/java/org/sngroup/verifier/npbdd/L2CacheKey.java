package org.sngroup.verifier.npbdd;

import java.util.Map;
import java.util.Objects;

public class L2CacheKey {

    private final String operation;
    private final long param1;
    private final int param2;
    private final int param3;

    private L2CacheKey(String operation, long param1, int param2, int param3) {
        this.operation = operation;
        this.param1 = param1;
        this.param2 = param2;
        this.param3 = param3;
    }

    public static L2CacheKey forEncodeRule(long ip, int prefixLen) {
        return new L2CacheKey("encode", ip, prefixLen, 0);
    }

    public static L2CacheKey forCalHit(int matchSpaceId, int usedSpaceId) {
        return new L2CacheKey("cal_hit", 0, matchSpaceId, usedSpaceId);
    }

    public static L2CacheKey forMergePortSpace(int spaceId1, int spaceId2) {
        return new L2CacheKey("merge", 0, spaceId1, spaceId2);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        L2CacheKey that = (L2CacheKey) o;
        return param1 == that.param1 &&
               param2 == that.param2 &&
               param3 == that.param3 &&
               Objects.equals(operation, that.operation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operation, param1, param2, param3);
    }

    @Override
    public String toString() {
        return String.format("L2CacheKey[op=%s, p1=%d, p2=%d, p3=%d]",
                           operation, param1, param2, param3);
    }

    public static class L2HitResult {
        public final int hitSpaceId;
        public final int newUsedSpaceId;

        public L2HitResult(int hitSpaceId, int newUsedSpaceId) {
            this.hitSpaceId = hitSpaceId;
            this.newUsedSpaceId = newUsedSpaceId;
        }
    }

    public static class L2MergeResult {
        public final int mergedSpaceId;
        public final Map<String, Integer> updatedPortMap;

        public L2MergeResult(int mergedSpaceId, Map<String, Integer> updatedPortMap) {
            this.mergedSpaceId = mergedSpaceId;
            this.updatedPortMap = updatedPortMap;
        }
    }
}
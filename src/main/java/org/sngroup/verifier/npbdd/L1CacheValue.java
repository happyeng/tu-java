/**
 * L1CacheValue.java (修复版)
 * 
 * 放置位置: src/main/java/org/sngroup/verifier/npbdd/L1CacheValue.java
 * 
 * 说明: 从 L1CacheKey.java 拆分出来的独立 public class,
 *       解决跨包访问 (org.sngroup.verifier.Device → org.sngroup.verifier.npbdd) 的可见性问题。
 * 
 * 操作: 将此文件放入 src/main/java/org/sngroup/verifier/npbdd/ 目录
 */
package org.sngroup.verifier.npbdd;

import java.util.Map;

/**
 * L1缓存值 - 完整规则转换的结果
 *
 * Value包含：
 * 1. newUsedSpaceId - 更新后的已使用空间
 * 2. updatedPortSpaces - 更新后的端口空间映射
 */
public class L1CacheValue {

    public final int newUsedSpaceId;                      // 更新后的fwded空间
    public final Map<Object, Integer> updatedPortSpaces;  // 更新后的端口谓词

    public L1CacheValue(int newUsedSpaceId, Map<Object, Integer> updatedPortSpaces) {
        this.newUsedSpaceId = newUsedSpaceId;
        this.updatedPortSpaces = updatedPortSpaces;
    }

    @Override
    public String toString() {
        return String.format("L1Value[used=%d, ports=%d]",
                           newUsedSpaceId,
                           updatedPortSpaces != null ? updatedPortSpaces.size() : 0);
    }
}

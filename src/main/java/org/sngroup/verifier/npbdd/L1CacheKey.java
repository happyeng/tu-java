/**
 * L1CacheKey.java (修复版)
 * 
 * 放置位置: src/main/java/org/sngroup/verifier/npbdd/L1CacheKey.java
 * 
 * 修复: 删除了原来文件底部的 package-private class L1CacheValue 定义,
 *       该类已拆分到独立的 L1CacheValue.java 文件中 (public class)。
 * 
 * 操作: 用此文件完整替换 src/main/java/org/sngroup/verifier/npbdd/L1CacheKey.java
 */
package org.sngroup.verifier.npbdd;

import java.util.Map;
import java.util.Objects;

/**
 * L1缓存键 - 完整规则转换的缓存键
 * 
 * Key由以下部分组成：
 * 1. 规则的IP和前缀长度
 * 2. 规则的转发动作
 * 3. 当前已使用空间的BDD谓词ID
 * 4. 当前端口空间映射的哈希值
 */
public class L1CacheKey {
    
    public final long ruleIp;
    public final int rulePrefixLen;
    public final Object ruleAction;       // ForwardAction
    public final int usedSpaceId;         // allBdd (fwded space)
    public final int portSpacesHash;      // hash of portPredicate map
    
    /**
     * 从Rule和当前状态构造L1缓存键
     * 
     * @param rule 当前处理的规则 (需要有 ip, prefixLen, forwardAction 字段)
     * @param usedSpaceId 当前已使用空间的BDD节点ID
     * @param portSpaces 当前端口谓词映射
     */
    public L1CacheKey(Object rule, int usedSpaceId, Map<?, Integer> portSpaces) {
        // 通过反射获取规则属性，避免直接依赖Rule类
        long ip = 0;
        int prefixLen = 0;
        Object action = null;
        
        try {
            java.lang.reflect.Field ipField = rule.getClass().getField("ip");
            ip = ipField.getLong(rule);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field ipField = rule.getClass().getDeclaredField("ip");
                ipField.setAccessible(true);
                ip = ipField.getLong(rule);
            } catch (Exception e2) {
                // fallback
            }
        }
        
        try {
            java.lang.reflect.Field prefixField = rule.getClass().getField("prefixLen");
            prefixLen = prefixField.getInt(rule);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field prefixField = rule.getClass().getDeclaredField("prefixLen");
                prefixField.setAccessible(true);
                prefixLen = prefixField.getInt(rule);
            } catch (Exception e2) {
                // fallback
            }
        }
        
        try {
            java.lang.reflect.Field actionField = rule.getClass().getField("forwardAction");
            action = actionField.get(rule);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field actionField = rule.getClass().getDeclaredField("forwardAction");
                actionField.setAccessible(true);
                action = actionField.get(rule);
            } catch (Exception e2) {
                // fallback
            }
        }
        
        this.ruleIp = ip;
        this.rulePrefixLen = prefixLen;
        this.ruleAction = action;
        this.usedSpaceId = usedSpaceId;
        this.portSpacesHash = portSpaces != null ? portSpaces.hashCode() : 0;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(ruleIp, rulePrefixLen, ruleAction, usedSpaceId, portSpacesHash);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof L1CacheKey)) return false;
        
        L1CacheKey other = (L1CacheKey) obj;
        return this.ruleIp == other.ruleIp &&
               this.rulePrefixLen == other.rulePrefixLen &&
               this.usedSpaceId == other.usedSpaceId &&
               this.portSpacesHash == other.portSpacesHash &&
               Objects.equals(this.ruleAction, other.ruleAction);
    }
    
    @Override
    public String toString() {
        return String.format("L1Key[ip=%d/%d, action=%s, used=%d, ports=0x%08x]",
                           ruleIp, rulePrefixLen, ruleAction, 
                           usedSpaceId, portSpacesHash);
    }
}

// 【重要】L1CacheValue 已移至独立文件 L1CacheValue.java
// 请勿在此文件中再定义 L1CacheValue 类

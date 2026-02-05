package org.sngroup.verifier.npbdd;

import org.sngroup.util.Rule;
import java.util.Map;
import java.util.Objects;

/**
 * L1缓存键 - 完整规则转换工作流
 * 
 * 对应HeTu论文Section IV-B中的L1 Cache
 * 
 * Key包含：
 * 1. rule - 当前要处理的规则
 * 2. usedSpaceId - 已使用的包空间ID（fwded）
 * 3. portSpacesHash - 当前所有端口空间的哈希值
 * 
 * 如果这三者完全相同，说明规则转换的上下文完全一致，
 * 可以直接使用缓存的结果。
 */
public class L1CacheKey {
    
    private final long ruleIp;              // 规则的IP地址
    private final int rulePrefixLen;        // 规则的前缀长度
    private final String ruleAction;        // 规则的转发动作
    private final int usedSpaceId;          // 当前已使用空间的谓词ID
    private final int portSpacesHash;       // 端口空间状态的哈希
    
    private final int hashCode;             // 预计算的哈希值
    
    /**
     * 构造函数
     * 
     * @param rule 当前规则
     * @param usedSpaceId 已使用的包空间ID
     * @param portSpaces 当前端口空间映射（用于计算哈希）
     */
    public L1CacheKey(Rule rule, int usedSpaceId, Map<?, Integer> portSpaces) {
        this.ruleIp = rule.ip;
        this.rulePrefixLen = rule.prefixLen;
        this.ruleAction = rule.forwardAction.toString();
        this.usedSpaceId = usedSpaceId;
        this.portSpacesHash = computePortSpacesHash(portSpaces);
        this.hashCode = computeHashCode();
    }
    
    /**
     * 简化构造函数（用于测试）
     */
    public L1CacheKey(long ruleIp, int rulePrefixLen, String ruleAction,
                     int usedSpaceId, int portSpacesHash) {
        this.ruleIp = ruleIp;
        this.rulePrefixLen = rulePrefixLen;
        this.ruleAction = ruleAction;
        this.usedSpaceId = usedSpaceId;
        this.portSpacesHash = portSpacesHash;
        this.hashCode = computeHashCode();
    }
    
    /**
     * 计算端口空间状态的哈希值
     */
    private int computePortSpacesHash(Map<?, Integer> portSpaces) {
        if (portSpaces == null || portSpaces.isEmpty()) {
            return 0;
        }
        
        // 简单策略：对所有值求异或
        // 注意：这假设端口空间的顺序不重要
        int hash = 0;
        for (Integer value : portSpaces.values()) {
            hash ^= value;
        }
        return hash;
    }
    
    private int computeHashCode() {
        int result = 17;
        result = 31 * result + Long.hashCode(ruleIp);
        result = 31 * result + rulePrefixLen;
        result = 31 * result + Objects.hashCode(ruleAction);
        result = 31 * result + usedSpaceId;
        result = 31 * result + portSpacesHash;
        return result;
    }
    
    @Override
    public int hashCode() {
        return hashCode;
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


/**
 * L1缓存值 - 完整规则转换的结果
 * 
 * Value包含：
 * 1. newUsedSpaceId - 更新后的已使用空间
 * 2. updatedPortSpaces - 更新后的端口空间映射
 */
class L1CacheValue {
    
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

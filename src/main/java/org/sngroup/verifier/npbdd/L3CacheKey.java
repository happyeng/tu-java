package org.sngroup.verifier.npbdd;

import java.util.Objects;

/**
 * L3缓存键 - 基础BDD操作
 * 
 * 对应HeTu论文Section IV-B中的L3 Cache
 * 
 * 缓存基础的BDD操作：
 * - MAKE: 从位向量创建BDD
 * - AND: 两个BDD的交集
 * - OR: 两个BDD的并集
 * - NOT: BDD的补集
 * - DIFF: 差集操作
 * 
 * 论文原文：
 * "L3 Cache operates at the lowest tier, caching the most basic and 
 *  frequently used BDD operations from Algorithm 2."
 */
public class L3CacheKey {
    
    private final String operation;     // 操作类型："MAKE", "AND", "OR", "NOT", "DIFF"
    private final long operand1;        // 第一个操作数
    private final long operand2;        // 第二个操作数（某些操作不需要）
    
    private final int hashCode;         // 预计算的哈希值
    
    /**
     * 构造函数 - MAKE操作
     * 
     * @param ip IP地址
     * @param prefixLen 前缀长度
     */
    public static L3CacheKey forMake(long ip, int prefixLen) {
        return new L3CacheKey("MAKE", ip, prefixLen);
    }
    
    /**
     * 构造函数 - 二元操作（AND, OR）
     * 
     * @param operation 操作名称
     * @param predicateId1 第一个谓词ID
     * @param predicateId2 第二个谓词ID
     */
    public static L3CacheKey forBinary(String operation, int predicateId1, int predicateId2) {
        // 对于交换律操作（AND, OR），标准化操作数顺序
        if (operation.equals("AND") || operation.equals("OR")) {
            if (predicateId1 > predicateId2) {
                int temp = predicateId1;
                predicateId1 = predicateId2;
                predicateId2 = temp;
            }
        }
        return new L3CacheKey(operation, predicateId1, predicateId2);
    }
    
    /**
     * 构造函数 - 一元操作（NOT）
     * 
     * @param predicateId 谓词ID
     */
    public static L3CacheKey forNot(int predicateId) {
        return new L3CacheKey("NOT", predicateId, 0);
    }
    
    /**
     * 构造函数 - DIFF操作（差集）
     * 
     * @param predicateId1 被减数
     * @param predicateId2 减数
     */
    public static L3CacheKey forDiff(int predicateId1, int predicateId2) {
        return new L3CacheKey("DIFF", predicateId1, predicateId2);
    }
    
    /**
     * 通用构造函数
     */
    public L3CacheKey(String operation, long operand1, long operand2) {
        this.operation = operation;
        this.operand1 = operand1;
        this.operand2 = operand2;
        this.hashCode = computeHashCode();
    }
    
    private int computeHashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(operation);
        result = 31 * result + Long.hashCode(operand1);
        result = 31 * result + Long.hashCode(operand2);
        return result;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public long getOperand1() {
        return operand1;
    }
    
    public long getOperand2() {
        return operand2;
    }
    
    @Override
    public int hashCode() {
        return hashCode;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof L3CacheKey)) return false;
        
        L3CacheKey other = (L3CacheKey) obj;
        return Objects.equals(this.operation, other.operation) &&
               this.operand1 == other.operand1 &&
               this.operand2 == other.operand2;
    }
    
    @Override
    public String toString() {
        if (operation.equals("NOT")) {
            return String.format("L3Key[%s(%d)]", operation, operand1);
        } else if (operation.equals("MAKE")) {
            return String.format("L3Key[%s(%d/%d)]", operation, operand1, operand2);
        } else {
            return String.format("L3Key[%s(%d, %d)]", operation, operand1, operand2);
        }
    }
}

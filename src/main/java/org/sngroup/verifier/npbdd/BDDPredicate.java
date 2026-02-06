package org.sngroup.verifier.npbdd;

import jdd.bdd.BDD;
import org.sngroup.verifier.TSBDD;

/**
 * BDD谓词封装类
 * 
 * 在HeTu的NP-BDD设计中，每个BDD谓词被独立存储并通过整数ID引用。
 * 这个类封装了BDD节点ID，并提供结构化的哈希和相等性比较。
 * 
 * 关键特性：
 * 1. 基于BDD结构的哈希，而非对象引用
 * 2. 支持线程安全的全局去重
 * 3. 轻量级，适合大规模存储
 */
public class BDDPredicate {
    
    private final int bddNode;           // JDD库中的BDD节点ID
    private final int structureHash;     // 基于BDD结构的哈希值
    private final String signature;      // BDD结构签名（用于调试）

    /**
     * 构造函数
     * @param bddNode JDD中的BDD节点ID
     * @param tsbdd TSBDD实例，用于访问BDD结构
     */
    public BDDPredicate(int bddNode, TSBDD tsbdd) {
        this.bddNode = bddNode;
        this.structureHash = computeStructureHash(bddNode, tsbdd);
        this.signature = computeSignature(bddNode, tsbdd);
    }
    
    /**
     * 计算BDD结构哈希
     * 
     * HeTu论文中提到，谓词级索引需要识别相同的BDD结构。
     * 这里使用递归哈希算法，基于变量、low子节点、high子节点计算。
     */
    private int computeStructureHash(int node, TSBDD tsbdd) {
        if (node == 0 || node == 1) {
            return node; // 终端节点
        }
        
        BDD bdd = tsbdd.bdd;
        int var = bdd.getVar(node);
        int low = bdd.getLow(node);
        int high = bdd.getHigh(node);
        
        // 使用质数混合哈希，避免碰撞
        int hash = 17;
        hash = 31 * hash + var;
        hash = 31 * hash + low;
        hash = 31 * hash + high;
        
        return hash;
    }
    
    /**
     * 计算BDD结构签名（用于调试和日志）
     */
    private String computeSignature(int node, TSBDD tsbdd) {
        if (node == 0) return "FALSE";
        if (node == 1) return "TRUE";
        
        BDD bdd = tsbdd.bdd;
        int var = bdd.getVar(node);
        int low = bdd.getLow(node);
        int high = bdd.getHigh(node);
        
        return String.format("(v%d->%d:%d)", var, low, high);
    }
    
    /**
     * 深度结构相等性比较
     * 
     * 论文中强调：相同结构的BDD应该映射到同一个ID。
     * 这里需要递归比较整个BDD树的结构。
     */
    public boolean structurallyEquals(BDDPredicate other, TSBDD tsbdd) {
        if (this.bddNode == other.bddNode) return true;
        if (this.structureHash != other.structureHash) return false;
        
        return compareStructure(this.bddNode, other.bddNode, tsbdd);
    }
    
    /**
     * 递归比较两个BDD节点的结构
     */
    private boolean compareStructure(int node1, int node2, TSBDD tsbdd) {
        // 终端节点
        if (node1 < 2 && node2 < 2) {
            return node1 == node2;
        }
        if (node1 < 2 || node2 < 2) {
            return false;
        }
        
        BDD bdd = tsbdd.bdd;
        
        // 比较变量
        if (bdd.getVar(node1) != bdd.getVar(node2)) {
            return false;
        }
        
        // 比较子节点
        int low1 = bdd.getLow(node1);
        int low2 = bdd.getLow(node2);
        int high1 = bdd.getHigh(node1);
        int high2 = bdd.getHigh(node2);
        
        return compareStructure(low1, low2, tsbdd) && 
               compareStructure(high1, high2, tsbdd);
    }
    
    public int getBddNode() {
        return bddNode;
    }
    
    public String getSignature() {
        return signature;
    }
    
    @Override
    public int hashCode() {
        return structureHash;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BDDPredicate)) return false;
        
        BDDPredicate other = (BDDPredicate) obj;
        
        // 快速路径：节点ID相同
        if (this.bddNode == other.bddNode) return true;
        
        // 哈希不同，结构必然不同
        if (this.structureHash != other.structureHash) return false;
        
        // 注意：完整的结构比较需要TSBDD实例
        // 在Registry中会调用structurallyEquals进行完整比较
        return this.signature.equals(other.signature);
    }
    
    @Override
    public String toString() {
        return String.format("BDDPredicate[node=%d, hash=%d, sig=%s]", 
                           bddNode, structureHash, signature);
    }
}

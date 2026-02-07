package org.sngroup.verifier.npbdd;

import org.sngroup.verifier.BDDEngine;
import org.sngroup.verifier.TSBDD;

/**
 * NP-BDD集成示例
 * 
 * 展示如何在现有项目中集成HeTu的NP-BDD设计
 * 
 * === 核心改造流程 ===
 * 
 * 1. BDDEngine改造：
 *    - 原来返回：int bddNode（JDD的节点ID）
 *    - 现在返回：int predicateId（全局谓词ID）
 * 
 * 2. 操作流程改造：
 *    Before: int bddNode = bdd.encodeDstIPPrefix(ip, prefix);
 *    After:  int predId = bdd.encodeDstIPPrefixWithCache(ip, prefix);
 * 
 * 3. 缓存检查流程：
 *    L3 → L2 → L1 逐层检查
 */
public class NPBDDIntegrationGuide {
    
    /**
     * 示例1：改造BDDEngine的编码方法
     * 
     * 这个方法展示如何在encodeDstIPPrefix中集成三层缓存
     */
    public static class BDDEngineEnhanced {
        
        private TSBDD tsbdd;
        private BDDPredicateRegistry registry = BDDPredicateRegistry.getInstance();
        private BDDPredicateCache cache = BDDPredicateCache.getInstance();
        
        /**
         * 增强版的encodeDstIPPrefix - 集成L3缓存
         */
        public int encodeDstIPPrefixWithCache(long ip, int prefixLen) {
            // === L3缓存检查：MAKE操作 ===
            L3CacheKey l3Key = L3CacheKey.forMake(ip, prefixLen);
            Integer cachedPredicateId = cache.getL3(l3Key);
            if (cachedPredicateId != null) {
                // 缓存命中，直接返回
                return cachedPredicateId;
            }
            
            // === 缓存未命中，执行实际计算 ===
            // 1. 执行原有的BDD构建逻辑
            int bddNode = executeMakeOperation(ip, prefixLen);
            
            // 2. 将BDD节点封装为谓词对象
            BDDPredicate predicate = new BDDPredicate(bddNode, tsbdd);
            
            // 3. 注册到全局表，获取谓词ID
            int predicateId = registry.getOrCreateId(predicate, tsbdd);
            
            // 4. 缓存结果
            cache.putL3(l3Key, predicateId);
            
            return predicateId;
        }
        
        /**
         * 原有的MAKE操作实现（简化版）
         */
        private int executeMakeOperation(long ip, int prefixLen) {
            // 这里是原有的BDD构建逻辑
            // 实际代码参考 BDDEngine.encodeDstIPPrefix
            return 0; // 占位符
        }
        
        /**
         * 增强版的AND操作 - 集成L3缓存
         */
        public int andWithCache(int predicateId1, int predicateId2) {
            // === L3缓存检查：AND操作 ===
            L3CacheKey l3Key = L3CacheKey.forBinary("AND", predicateId1, predicateId2);
            Integer cachedResult = cache.getL3(l3Key);
            if (cachedResult != null) {
                return cachedResult;
            }
            
            // === 执行实际计算 ===
            // 1. 获取实际的BDD节点
            int bddNode1 = registry.getBDDNode(predicateId1);
            int bddNode2 = registry.getBDDNode(predicateId2);
            
            // 2. 执行底层BDD操作
            int resultNode = tsbdd.and(bddNode1, bddNode2);
            
            // 3. 封装并注册
            BDDPredicate resultPred = new BDDPredicate(resultNode, tsbdd);
            int resultId = registry.getOrCreateId(resultPred, tsbdd);
            
            // 4. 缓存
            cache.putL3(l3Key, resultId);
            
            return resultId;
        }
    }
    
    /**
     * 示例2：改造Device的规则编码方法
     * 
     * 这个方法展示如何在encodeRuleToLecFromScratchToFinish中集成L1/L2缓存
     */
    public static class DeviceEnhanced {
        
        private BDDPredicateCache cache = BDDPredicateCache.getInstance();
        
        /**
         * 增强版的规则编码 - 集成L1/L2缓存
         * 
         * 伪代码，展示集成思路
         */
        public void encodeRuleWithCache(/* 参数列表 */) {
            // 假设变量：
            // Rule rule - 当前规则
            // int allBdd - 已使用空间
            // Map<ForwardAction, Integer> portPredicate - 端口谓词
            
            // === L1缓存检查：完整规则转换 ===
            // L1CacheKey l1Key = new L1CacheKey(rule, allBdd, portPredicate);
            // L1CacheValue l1Result = cache.getL1(l1Key);
            // if (l1Result != null) {
            //     // 缓存命中，直接使用结果
            //     allBdd = l1Result.newUsedSpaceId;
            //     portPredicate.putAll(l1Result.updatedPortSpaces);
            //     return;
            // }
            
            // === L2缓存检查：规则编码 ===
            // L2CacheKey l2EncodeKey = L2CacheKey.forEncodeRule(rule.ip, rule.prefixLen);
            // Integer tmpMatch = cache.getL2Encode(l2EncodeKey);
            // if (tmpMatch == null) {
            //     tmpMatch = bdd.encodeDstIPPrefixWithCache(rule.ip, rule.prefixLen);
            //     cache.putL2Encode(l2EncodeKey, tmpMatch);
            // }
            
            // === L2缓存检查：计算hit space ===
            // L2CacheKey l2HitKey = L2CacheKey.forCalHit(tmpMatch, allBdd);
            // L2HitResult hitResult = cache.getL2Hit(l2HitKey);
            // if (hitResult == null) {
            //     // 执行实际计算
            //     int tmpHit = calculateHit(tmpMatch, allBdd);
            //     int newAllBdd = bdd.orWithCache(allBdd, tmpHit);
            //     hitResult = new L2HitResult(tmpHit, newAllBdd);
            //     cache.putL2Hit(l2HitKey, hitResult);
            // }
            
            // === L2缓存检查：端口合并 ===
            // ... 类似逻辑
            
            // === 更新L1缓存 ===
            // L1CacheValue l1Value = new L1CacheValue(newAllBdd, portPredicate);
            // cache.putL1(l1Key, l1Value);
        }
    }
    
    /**
     * 示例3：TopoRunner集成
     * 
     * 展示如何在TopoRunner中使用NP-BDD
     */
    public static class TopoRunnerIntegration {
        
        /**
         * 在验证开始时，初始化NP-BDD组件
         */
        public void initializeNPBDD() {
            System.out.println("初始化NP-BDD组件...");
            
            // 全局单例，无需显式初始化
            BDDPredicateRegistry registry = BDDPredicateRegistry.getInstance();
            BDDPredicateCache cache = BDDPredicateCache.getInstance();
            
            // 可选：清空之前的缓存
            cache.clear();
            registry.clear();
            
            System.out.println("NP-BDD组件初始化完成");
        }
        
        /**
         * 在验证结束时，打印统计信息
         */
        public void printNPBDDStats() {
            System.out.println("\n========== NP-BDD性能统计 ==========");
            
            BDDPredicateRegistry registry = BDDPredicateRegistry.getInstance();
            registry.printStats();
            
            BDDPredicateCache cache = BDDPredicateCache.getInstance();
            cache.printStats();
            
            System.out.println("====================================\n");
        }
    }
    
    /**
     * 主函数：完整的集成示例
     */
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("       NP-BDD集成指南");
        System.out.println("========================================\n");
        
        System.out.println("=== 关键改造点 ===\n");
        
        System.out.println("1. BDDEngine改造：");
        System.out.println("   - 添加 encodeDstIPPrefixWithCache() 方法");
        System.out.println("   - 添加 andWithCache(), orWithCache() 等方法");
        System.out.println("   - 在每个方法中集成L3缓存\n");
        
        System.out.println("2. Device改造：");
        System.out.println("   - 修改 encodeRuleToLecFromScratchToFinish()");
        System.out.println("   - 在规则处理循环中集成L1/L2缓存");
        System.out.println("   - 关键检查点：规则编码、hit计算、端口合并\n");
        
        System.out.println("3. TopoRunner改造：");
        System.out.println("   - 在start()开始时初始化NP-BDD");
        System.out.println("   - 在验证结束时打印统计信息");
        System.out.println("   - BDD引擎池继续使用，但共享全局谓词表\n");
        
        System.out.println("=== 预期收益 ===\n");
        System.out.println("根据HeTu论文的实验结果：");
        System.out.println("- 编码时间：减少2-3个数量级");
        System.out.println("- 内存使用：减少50-75%");
        System.out.println("- 整体性能：提升100-6000倍");
        
        System.out.println("\n========================================");
    }
}

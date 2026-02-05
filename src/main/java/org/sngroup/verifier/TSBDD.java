package org.sngroup.verifier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jdd.bdd.BDD;

import java.io.Serializable;

// ========== 新增：NP-BDD相关import ==========
import org.sngroup.verifier.npbdd.BDDPredicate;
import org.sngroup.verifier.npbdd.BDDPredicateRegistry;
import org.sngroup.verifier.npbdd.BDDPredicateCache;
import org.sngroup.verifier.npbdd.L3CacheKey;
// ========== 新增结束 ==========

@JsonIgnoreProperties(ignoreUnknown = true)
public class TSBDD implements Cloneable, Serializable {
    public BDD bdd;

    // ========== 新增：NP-BDD组件引用 ==========
    /**
     * 全局谓词注册表引用
     */
    private static final BDDPredicateRegistry predicateRegistry =
        BDDPredicateRegistry.getInstance();

    /**
     * 全局缓存引用
     */
    private static final BDDPredicateCache predicateCache =
        BDDPredicateCache.getInstance();
    // ========== 新增结束 ==========

    public TSBDD(){

    }

    public TSBDD(BDD bdd){
        this.bdd = bdd;
    }

    @Override
    public Object clone() {
        TSBDD tsbdd = null;
        try{
            tsbdd = (TSBDD) super.clone();
        }catch(CloneNotSupportedException e) {
            e.printStackTrace();
        }
        tsbdd.bdd = (BDD)this.bdd.clone();
        return tsbdd;
    }



    public  int cnt = 0;

    // ========== 原有方法保持不变 ==========
    public int and(int u1, int u2){
        cnt++;
//        synchronized (bdd){
            return bdd.and(u1, u2);
//        }
    }

    public int andTo(int u1, int u2){
        cnt++;
//        synchronized (bdd){
            return bdd.andTo(u1, u2);
//        }
    }

    public int xor(int u1, int u2){
        cnt++;
//        synchronized (bdd){
            return bdd.xor(u1, u2);
//        }
    }

    public int or(int u1, int u2){
        cnt++;
//        synchronized (bdd){
            return bdd.or(u1, u2);
//        }
    }

    public int orTo(int u1, int u2){
        cnt++;
//        synchronized (bdd){
            return bdd.orTo(u1, u2);
//        }
    }

    public int not(int u1){
        cnt++;
//        synchronized (bdd){
            return bdd.not(u1);
//        }
    }

    public int diff(int u1, int u2){
        int ret;
//        synchronized (bdd) {
            int tmp = bdd.ref(bdd.not(u2));
            ret = bdd.ref(bdd.and(u1, tmp));
            bdd.deref(tmp);
//        }
        return ret;
    }

    public int ref(int u1){
//        synchronized (bdd){
            return bdd.ref(u1);
//        }
    }

    public int deref(int u1){
//        synchronized (bdd) {
            return bdd.deref(u1);
//            return 0;
//        }
    }

    public int createVar(){
//        synchronized (bdd){
            return bdd.createVar();
//        }
    }

    public boolean isValid(int u){
//        synchronized (bdd) {
            return bdd.isValid(u);
//        }
    }

    public int nodeCount(int u){
//        synchronized (bdd){
            return bdd.nodeCount(u);
//        }
    }

    public int getVarUnmasked(int u){
//        synchronized (bdd) {
            return bdd.getVarUnmasked(u);
//        }
    }

    public int getLow(int u){
//        synchronized (bdd) {
            return bdd.getLow(u);
//        }
    }

    public int getHigh(int u){
//        synchronized (bdd) {
            return bdd.getHigh(u);
//        }
    }

    public int mk(int u, int v, int w){
//        synchronized (bdd){
            return bdd.mk(u, v, w);
//        }
    }

    public void gc(){
//        synchronized (bdd){
            bdd.gc();
//        }
    }

    public int exists(int u, int cube){
//        synchronized (bdd){
            return bdd.exists(u, cube);
//        }
    }

    public long getMemoryUsage(){
//        synchronized (bdd) {
            return bdd.getMemoryUsage();
//        }
    }

    public void print(int u){
//        synchronized (bdd) {
            bdd.printSet(u);
//        }
    }
    // ========== 原有方法结束 ==========

    // ========== 新增：带缓存的操作方法 ==========
    /**
     * 带缓存的AND操作
     * 对应HeTu论文L3缓存中的AND操作
     */
    public int andWithCache(int predId1, int predId2) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return and(predId1, predId2);
        }

        // L3缓存检查
        L3CacheKey cacheKey = L3CacheKey.forBinary("AND", predId1, predId2);
        Integer cachedResult = predicateCache.getL3(cacheKey);

        if (cachedResult != null) {
            return cachedResult;
        }

        // 执行实际计算
        int node1 = predicateRegistry.getBDDNode(predId1);
        int node2 = predicateRegistry.getBDDNode(predId2);
        int resultNode = bdd.and(node1, node2);

        // 封装并注册
        BDDPredicate resultPredicate = new BDDPredicate(resultNode, this);
        int resultId = predicateRegistry.getOrCreateId(resultPredicate, this);

        // 缓存
        predicateCache.putL3(cacheKey, resultId);

        return resultId;
    }

    /**
     * 带缓存的OR操作
     */
    public int orWithCache(int predId1, int predId2) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return or(predId1, predId2);
        }

        // L3缓存检查
        L3CacheKey cacheKey = L3CacheKey.forBinary("OR", predId1, predId2);
        Integer cachedResult = predicateCache.getL3(cacheKey);

        if (cachedResult != null) {
            return cachedResult;
        }

        // 执行实际计算
        int node1 = predicateRegistry.getBDDNode(predId1);
        int node2 = predicateRegistry.getBDDNode(predId2);
        int resultNode = bdd.or(node1, node2);

        BDDPredicate resultPredicate = new BDDPredicate(resultNode, this);
        int resultId = predicateRegistry.getOrCreateId(resultPredicate, this);

        predicateCache.putL3(cacheKey, resultId);

        return resultId;
    }

    /**
     * 带缓存的NOT操作
     */
    public int notWithCache(int predId) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return not(predId);
        }

        // L3缓存检查
        L3CacheKey cacheKey = L3CacheKey.forNot(predId);
        Integer cachedResult = predicateCache.getL3(cacheKey);

        if (cachedResult != null) {
            return cachedResult;
        }

        // 执行实际计算
        int node = predicateRegistry.getBDDNode(predId);
        int resultNode = bdd.not(node);

        BDDPredicate resultPredicate = new BDDPredicate(resultNode, this);
        int resultId = predicateRegistry.getOrCreateId(resultPredicate, this);

        predicateCache.putL3(cacheKey, resultId);

        return resultId;
    }

    /**
     * 带缓存的andTo操作（破坏性更新）
     */
    public int andToWithCache(int predId1, int predId2) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return andTo(predId1, predId2);
        }

        // andTo是破坏性更新，直接使用andWithCache
        return andWithCache(predId1, predId2);
    }

    /**
     * 带缓存的orTo操作（破坏性更新）
     */
    public int orToWithCache(int predId1, int predId2) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return orTo(predId1, predId2);
        }

        return orWithCache(predId1, predId2);
    }

    /**
     * 带缓存的diff操作（差集）
     */
    public int diffWithCache(int predId1, int predId2) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return diff(predId1, predId2);
        }

        // L3缓存检查
        L3CacheKey cacheKey = L3CacheKey.forDiff(predId1, predId2);
        Integer cachedResult = predicateCache.getL3(cacheKey);

        if (cachedResult != null) {
            return cachedResult;
        }

        // diff(u1, u2) = u1 AND NOT(u2)
        int notPredId2 = notWithCache(predId2);
        int resultId = andWithCache(predId1, notPredId2);

        // 缓存结果
        predicateCache.putL3(cacheKey, resultId);

        return resultId;
    }

    /**
     * 带缓存的XOR操作
     */
    public int xorWithCache(int predId1, int predId2) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return xor(predId1, predId2);
        }

        L3CacheKey cacheKey = L3CacheKey.forBinary("XOR", predId1, predId2);
        Integer cachedResult = predicateCache.getL3(cacheKey);

        if (cachedResult != null) {
            return cachedResult;
        }

        int node1 = predicateRegistry.getBDDNode(predId1);
        int node2 = predicateRegistry.getBDDNode(predId2);
        int resultNode = bdd.xor(node1, node2);

        BDDPredicate resultPredicate = new BDDPredicate(resultNode, this);
        int resultId = predicateRegistry.getOrCreateId(resultPredicate, this);

        predicateCache.putL3(cacheKey, resultId);

        return resultId;
    }

    /**
     * 带缓存的ref操作
     */
    public int refWithCache(int predId) {
        if (!BDDEngine.isNPBDDEnabled()) {
            return ref(predId);
        }

        // ref操作不需要缓存，直接调用原方法
        int node = predicateRegistry.getBDDNode(predId);
        return ref(node);
    }

    /**
     * 批量OR操作（带缓存）
     */
    public int orMultipleWithCache(int... predicateIds) {
        if (predicateIds == null || predicateIds.length == 0) {
            return 0; // FALSE
        }

        int result = predicateIds[0];
        for (int i = 1; i < predicateIds.length; i++) {
            if (BDDEngine.isNPBDDEnabled()) {
                result = orWithCache(result, predicateIds[i]);
            } else {
                result = or(result, predicateIds[i]);
            }
        }

        return result;
    }

    /**
     * 批量AND操作（带缓存）
     */
    public int andMultipleWithCache(int... predicateIds) {
        if (predicateIds == null || predicateIds.length == 0) {
            return 1; // TRUE
        }

        int result = predicateIds[0];
        for (int i = 1; i < predicateIds.length; i++) {
            if (BDDEngine.isNPBDDEnabled()) {
                result = andWithCache(result, predicateIds[i]);
            } else {
                result = and(result, predicateIds[i]);
            }
        }

        return result;
    }
    // ========== 新增结束 ==========
}
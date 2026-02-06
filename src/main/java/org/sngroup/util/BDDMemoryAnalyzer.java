package org.sngroup.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.sngroup.verifier.BDDEngine;

/**
 * BDD内存分析工具 - 专注于分析BDD引擎和规则转换的内存消耗
 */
public class BDDMemoryAnalyzer {
    private static final BDDMemoryAnalyzer INSTANCE = new BDDMemoryAnalyzer();
    
    // 输出文件
    private static final String MEMORY_LOG = "./bdd_memory.csv";
    private static final String MEMORY_DETAIL = "./bdd_memory_detail.txt";
    
    // 内存使用追踪
    private final Map<String, Long> memoryBefore = new HashMap<>();
    private final Map<String, Long> memoryAfter = new HashMap<>();
    private final Map<String, Integer> ruleCount = new HashMap<>();
    private final Map<String, Integer> bddNodeCount = new HashMap<>();
    
    // BDD节点计数
    private final AtomicLong totalBDDNodes = new AtomicLong(0);
    private long initialMemory = 0;
    private long peakMemory = 0;
    
    // 前20条规则的详细内存分析
    private static final int SAMPLE_SIZE = 20;
    private final Map<Integer, Long> sampleRuleMemory = new HashMap<>();
    
    // GC监控
    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private long lastGcCount = 0;
    private long lastGcTime = 0;
    
    private BDDMemoryAnalyzer() {
        // 获取JVM内存和GC监控接口
        memoryMXBean = ManagementFactory.getMemoryMXBean();
        gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        // 初始化GC计数
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            lastGcCount += gcBean.getCollectionCount();
            lastGcTime += gcBean.getCollectionTime();
        }
        
        // 记录初始内存状态
        initialMemory = getCurrentMemoryUsage();
        peakMemory = initialMemory;
        
        // 创建日志文件
        try (PrintWriter writer = new PrintWriter(new FileWriter(MEMORY_LOG))) {
            writer.println("timestamp,operation,device,rules,memory_before_kb,memory_after_kb,delta_kb,bdd_nodes,memory_per_rule_kb,memory_per_node_bytes");
        } catch (IOException e) {
            System.err.println("无法创建内存日志文件: " + e.getMessage());
        }
    }
    
    public static BDDMemoryAnalyzer getInstance() {
        return INSTANCE;
    }
    
    /**
     * 开始监控设备规则转换
     */
    public void startDeviceMonitoring(String deviceName) {
        long memory = getCurrentMemoryUsage();
        memoryBefore.put(deviceName, memory);
        
        logMemoryOperation("开始处理设备", deviceName, 0, memory/1024, 0, 0);
        updatePeakMemory();
    }
    
    /**
     * 结束监控设备规则转换
     */
    public void endDeviceMonitoring(String deviceName, int rules, int nodes) {
        long before = memoryBefore.getOrDefault(deviceName, 0L);
        long after = getCurrentMemoryUsage();
        long delta = after - before;
        
        memoryAfter.put(deviceName, after);
        ruleCount.put(deviceName, rules);
        bddNodeCount.put(deviceName, nodes);
        totalBDDNodes.addAndGet(nodes);
        
        logMemoryOperation("完成处理设备", deviceName, rules, 
                before/1024, after/1024, nodes);
        
        updatePeakMemory();
        
        System.out.println("[内存分析] 设备 " + deviceName + " 处理完成: " + 
                         rules + " 条规则, " + nodes + " 个BDD节点, 内存增长: " + 
                         (delta/1024/1024) + " MB");
    }
    
    /**
     * 监控单条规则转换的内存使用
     */
    public void monitorRuleConversion(int ruleIndex, Runnable conversion) {
        // 只分析前SAMPLE_SIZE条规则
        if (ruleIndex >= SAMPLE_SIZE) {
            conversion.run();
            return;
        }
        
        // 执行GC以获得更准确的测量
        System.gc();
        long before = getCurrentMemoryUsage();
        
        // 执行规则转换
        conversion.run();
        
        // 再次测量内存使用
        long after = getCurrentMemoryUsage();
        long delta = Math.max(0, after - before);
        
        sampleRuleMemory.put(ruleIndex, delta);
        
        // 更新峰值内存
        updatePeakMemory();
    }
    
    /**
     * 分析BDD引擎内存使用
     */
    public void analyzeBDDEngine(BDDEngine engine) {
        long memory = getCurrentMemoryUsage();
        int nodeCount = 0;
        
        // 尝试获取BDD节点数量
        try {
            nodeCount = engine.getBDD().nodeCount(1);
        } catch (Exception e) {
            // 忽略错误，继续分析
        }
        
        logMemoryOperation("BDD引擎分析", "全局", 0, 
                0, memory/1024, nodeCount);
        
        System.out.println("[内存分析] BDD引擎状态: " + 
                         nodeCount + " 个节点, 当前内存: " + 
                         (memory/1024/1024) + " MB");
    }
    
    /**
     * 记录内存使用和GC状态
     */
    public void recordMemorySnapshot(String label) {
        long memory = getCurrentMemoryUsage();
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        // 计算GC统计
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            gcCount += gcBean.getCollectionCount();
            gcTime += gcBean.getCollectionTime();
        }
        
        long gcCountDelta = gcCount - lastGcCount;
        long gcTimeDelta = gcTime - lastGcTime;
        
        lastGcCount = gcCount;
        lastGcTime = gcTime;
        
        updatePeakMemory();
        
        // 只记录到控制台，避免文件IO开销
        System.out.println("[内存快照] " + label + 
                         " - 堆内存: " + (heapUsage.getUsed()/1024/1024) + "/" + 
                         (heapUsage.getCommitted()/1024/1024) + " MB, " +
                         "非堆内存: " + (nonHeapUsage.getUsed()/1024/1024) + " MB, " +
                         "GC次数增量: " + gcCountDelta + ", " +
                         "GC时间增量: " + gcTimeDelta + " ms");
    }
    
    /**
     * 生成详细的内存分析报告
     */
    public void generateDetailedReport(int totalRules, int totalDevices) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(MEMORY_DETAIL))) {
            writer.println("============ BDD内存详细分析报告 ============");
            writer.println("生成时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println();
            
            writer.println("---------- 总体内存使用 ----------");
            writer.println("初始内存: " + (initialMemory/1024/1024) + " MB");
            writer.println("峰值内存: " + (peakMemory/1024/1024) + " MB");
            writer.println("内存增长: " + ((peakMemory - initialMemory)/1024/1024) + " MB");
            writer.println();
            
            writer.println("---------- BDD节点统计 ----------");
            writer.println("总BDD节点数: " + totalBDDNodes.get());
            if (totalRules > 0) {
                writer.println("平均每条规则节点数: " + String.format("%.2f", (double)totalBDDNodes.get() / totalRules));
            }
            if (totalBDDNodes.get() > 0) {
                writer.println("平均每节点内存: " + String.format("%.2f", (double)(peakMemory - initialMemory) / totalBDDNodes.get()) + " 字节");
            }
            writer.println();
            
            writer.println("---------- 设备内存使用 ----------");
            for (String device : memoryBefore.keySet()) {
                long before = memoryBefore.getOrDefault(device, 0L);
                long after = memoryAfter.getOrDefault(device, 0L);
                int rules = ruleCount.getOrDefault(device, 0);
                int nodes = bddNodeCount.getOrDefault(device, 0);
                
                if (rules > 0 && after > before) {
                    writer.println("设备: " + device);
                    writer.println("  规则数: " + rules);
                    writer.println("  BDD节点数: " + nodes);
                    writer.println("  内存增长: " + ((after - before)/1024/1024) + " MB");
                    writer.println("  每条规则内存: " + String.format("%.2f", (double)(after - before)/rules/1024) + " KB");
                    if (nodes > 0) {
                        writer.println("  每个节点内存: " + String.format("%.2f", (double)(after - before)/nodes) + " 字节");
                    }
                    writer.println();
                }
            }
            
            writer.println("---------- 单条规则内存分析 ----------");
            if (!sampleRuleMemory.isEmpty()) {
                writer.println("采样的前" + Math.min(SAMPLE_SIZE, sampleRuleMemory.size()) + "条规则:");
                for (Map.Entry<Integer, Long> entry : sampleRuleMemory.entrySet()) {
                    writer.println("  规则#" + entry.getKey() + ": " + (entry.getValue()/1024) + " KB");
                }
            } else {
                writer.println("没有进行单条规则采样");
            }
            
            writer.println("\n详细内存日志已保存至: " + MEMORY_LOG);
            writer.println("=========================================");
            
        } catch (IOException e) {
            System.err.println("无法创建详细报告: " + e.getMessage());
        }
        
        System.out.println("BDD内存分析报告已生成: " + MEMORY_DETAIL);
    }
    
    /**
     * 更新峰值内存使用
     */
    private void updatePeakMemory() {
        long currentMemory = getCurrentMemoryUsage();
        if (currentMemory > peakMemory) {
            peakMemory = currentMemory;
        }
    }
    
    /**
     * 获取当前内存使用
     */
    private long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    /**
     * 记录内存操作到日志
     */
    private void logMemoryOperation(String operation, String device, int rules, 
                                 long memoryBefore, long memoryAfter, int nodes) {
        long memoryDelta = memoryAfter - memoryBefore;
        double memoryPerRule = (rules > 0) ? (double)memoryDelta / rules : 0;
        double memoryPerNode = (nodes > 0) ? (double)memoryDelta * 1024 / nodes : 0;
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(MEMORY_LOG, true))) {
            writer.write(System.currentTimeMillis() + "," + 
                       operation + "," + 
                       device + "," + 
                       rules + "," + 
                       memoryBefore + "," + 
                       memoryAfter + "," + 
                       memoryDelta + "," + 
                       nodes + "," + 
                       String.format("%.2f", memoryPerRule) + "," + 
                       String.format("%.2f", memoryPerNode));
            writer.newLine();
        } catch (IOException e) {
            // 简单记录错误但不中断执行
            System.err.println("无法写入内存日志: " + e.getMessage());
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        memoryBefore.clear();
        memoryAfter.clear();
        ruleCount.clear();
        bddNodeCount.clear();
        sampleRuleMemory.clear();
    }
}
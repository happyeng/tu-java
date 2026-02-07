package org.sngroup.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 监控和记录构建阶段的性能指标
 */
public class BuildPerformanceMonitor {
    // 单例实例
    private static final BuildPerformanceMonitor INSTANCE = new BuildPerformanceMonitor();

    // 输出文件
    private static final String PERFORMANCE_LOG = "./build_performance.csv";
    private static final String MEMORY_SAMPLES_LOG = "./memory_samples.csv";
    private static final String SUMMARY_FILE = "./build_summary.txt";

    // 时间和内存指标记录
    private final Map<String, Long> startTimes = new HashMap<>();
    private final Map<String, Long> endTimes = new HashMap<>();
    private final Map<String, Long> startMemory = new HashMap<>();
    private final Map<String, Long> endMemory = new HashMap<>();
    private final Map<String, Long> peakMemory = new ConcurrentHashMap<>();

    // 每个阶段的内存采样
    private final Map<String, List<MemorySample>> memorySamples = new ConcurrentHashMap<>();
    private static class MemorySample {
        long timestamp;
        long usedMemory;
        long totalMemory;
        long maxMemory;

        MemorySample(long timestamp, long usedMemory, long totalMemory, long maxMemory) {
            this.timestamp = timestamp;
            this.usedMemory = usedMemory;
            this.totalMemory = totalMemory;
            this.maxMemory = maxMemory;
        }
    }

    // 规则统计
    private final AtomicInteger totalRules = new AtomicInteger(0);
    private int totalDevices = 0;
    private int totalTopoNets = 0;

    // GC监控
    private final List<GarbageCollectorMXBean> gcBeans;
    private final Map<String, Long> phaseStartGcCount = new HashMap<>();
    private final Map<String, Long> phaseEndGcCount = new HashMap<>();
    private final Map<String, Long> phaseStartGcTime = new HashMap<>();
    private final Map<String, Long> phaseEndGcTime = new HashMap<>();

    // 内存采样定时器
    private static final int SAMPLING_INTERVAL_MS = 5000; // 5秒采样一次
    private Timer samplingTimer;
    private String currentPhase = null;

    // 防止对象构建
    private BuildPerformanceMonitor() {
        // 初始化GC监控
        gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        // 创建性能日志文件
        try (PrintWriter writer = new PrintWriter(new FileWriter(PERFORMANCE_LOG))) {
            writer.println("timestamp,phase,operation,duration_ms,memory_kb,memory_delta_kb,gc_count,gc_time_ms");
        } catch (IOException e) {
            System.err.println("无法创建性能日志: " + e.getMessage());
        }

        // 创建内存采样日志
        try (PrintWriter writer = new PrintWriter(new FileWriter(MEMORY_SAMPLES_LOG))) {
            writer.println("timestamp,phase,used_memory_mb,total_memory_mb,max_memory_mb,gc_count");
        } catch (IOException e) {
            System.err.println("无法创建内存采样日志: " + e.getMessage());
        }

        // 初始化内存采样定时器
        samplingTimer = new Timer("MemorySampler", true);
    }

    /**
     * 获取实例
     */
    public static BuildPerformanceMonitor getInstance() {
        return INSTANCE;
    }

    /**
     * 记录构建阶段开始
     */
    public void startBuild() {
        // 启动前强制GC，获取更准确的基准内存
        System.gc();
        try {
            Thread.sleep(1000); // 等待GC完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        recordStart("Build", "全阶段");
    }

    /**
     * 记录规则读取阶段开始
     */
    public void startRuleReading() {
        recordStart("RuleReading", "规则读取");
    }

    /**
     * 记录规则转换阶段开始
     */
    public void startRuleTransformation() {
        recordStart("RuleTransformation", "规则转换");
    }

    /**
     * 记录TopoNet生成阶段开始
     */
    public void startTopoNetGeneration() {
        recordStart("TopoNetGeneration", "TopoNet生成");
    }

    /**
     * 记录构建阶段结束
     */
    public void endBuild() {
        // 结束前强制GC，清理临时对象
        System.gc();
        try {
            Thread.sleep(1000); // 等待GC完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        recordEnd("Build", "全阶段");

        // 停止采样定时器
        if (samplingTimer != null) {
            samplingTimer.cancel();
            samplingTimer = null;
        }
    }

    /**
     * 记录规则读取阶段结束
     */
    public void endRuleReading() {
        recordEnd("RuleReading", "规则读取");
    }

    /**
     * 记录规则转换阶段结束
     */
    public void endRuleTransformation() {
        recordEnd("RuleTransformation", "规则转换");
    }

    /**
     * 记录TopoNet生成阶段结束
     */
    public void endTopoNetGeneration() {
        recordEnd("TopoNetGeneration", "TopoNet生成");
    }

    /**
     * 设置统计数据
     */
    public void setStatistics(int deviceCount, int ruleCount, int topoNetCount) {
        this.totalDevices = deviceCount;
        this.totalRules.set(ruleCount);
        this.totalTopoNets = topoNetCount;
    }

    /**
     * 增加规则数量
     */
    public void incrementRuleCount(int count) {
        totalRules.addAndGet(count);
    }

    /**
     * 记录规则计数
     */
    public void recordRuleCount(String deviceName, int ruleCount) {
        totalRules.addAndGet(ruleCount);
        System.out.println("[性能监控] 设备 " + deviceName + " 规则数: " + ruleCount +
                         ", 当前累计规则总数: " + totalRules.get());
    }

    /**
     * 生成性能摘要报告
     */
    public void generateSummary() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(SUMMARY_FILE))) {
            writer.println("============= 构建阶段性能摘要 =============");
            writer.println("生成时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println();

            writer.println("---------- 基本统计 ----------");
            writer.println("设备总数: " + totalDevices);
            writer.println("规则总数: " + totalRules.get());
            writer.println("TopoNet总数: " + totalTopoNets);
            writer.println();

            writer.println("---------- 时间性能(ms) ----------");
            for (String phase : new String[]{"Build", "RuleReading", "RuleTransformation", "TopoNetGeneration"}) {
                long duration = getDuration(phase);
                writer.println(getPhaseLabel(phase) + ": " + duration + " ms");
            }
            writer.println();

            writer.println("---------- 内存性能(MB) ----------");
            for (String phase : new String[]{"Build", "RuleReading", "RuleTransformation", "TopoNetGeneration"}) {
                // 从采样中计算实际内存增长
                long memoryUsage = calculateEffectiveMemoryGrowth(phase) / (1024 * 1024);
                long peakUsage = getPeakMemory(phase) / (1024 * 1024);
                long totalGcCount = getPhaseGcCount(phase);

                writer.println(getPhaseLabel(phase) + " 内存增长: " + memoryUsage + " MB");
                writer.println(getPhaseLabel(phase) + " 内存峰值: " + peakUsage + " MB");
                writer.println(getPhaseLabel(phase) + " GC次数: " + totalGcCount);
            }
            writer.println();

            writer.println("---------- 单位性能 ----------");
            if (totalRules.get() > 0) {
                double timePerRule = (double) getDuration("RuleTransformation") / totalRules.get();
                double memPerRule = (double) calculateEffectiveMemoryGrowth("RuleTransformation") / totalRules.get() / 1024;
                writer.println("每条规则平均转换时间: " + String.format("%.2f", timePerRule) + " ms");
                writer.println("每条规则平均内存消耗: " + String.format("%.2f", memPerRule) + " KB");
            }

            if (totalTopoNets > 0) {
                double timePerTopoNet = (double) getDuration("TopoNetGeneration") / totalTopoNets;
                double memPerTopoNet = (double) calculateEffectiveMemoryGrowth("TopoNetGeneration") / totalTopoNets / 1024;
                writer.println("每个TopoNet平均生成时间: " + String.format("%.2f", timePerTopoNet) + " ms");
                writer.println("每个TopoNet平均内存消耗: " + String.format("%.2f", memPerTopoNet) + " KB");
            }

            writer.println();
            writer.println("详细性能日志已保存至: " + PERFORMANCE_LOG);
            writer.println("内存采样日志已保存至: " + MEMORY_SAMPLES_LOG);
            writer.println("========================================");

            System.out.println("性能摘要已生成: " + SUMMARY_FILE);

        } catch (IOException e) {
            System.err.println("无法创建摘要文件: " + e.getMessage());
        }
    }

    /**
     * 记录阶段开始
     */
    private void recordStart(String phase, String label) {
        // 记录阶段开始前先进行GC
        System.gc();
        try {
            Thread.sleep(500); // 等待GC完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long timestamp = System.currentTimeMillis();
        long memoryUsage = getCurrentMemoryUsage();
        long gcCount = getTotalGcCount();
        long gcTime = getTotalGcTime();

        startTimes.put(phase, timestamp);
        startMemory.put(phase, memoryUsage);
        peakMemory.put(phase, memoryUsage);
        phaseStartGcCount.put(phase, gcCount);
        phaseStartGcTime.put(phase, gcTime);

        // 初始化内存采样列表
        memorySamples.put(phase, new ArrayList<>());

        // 启动定期内存采样
        currentPhase = phase;
        startMemorySampling(phase);

        logPerformance(timestamp, phase, "开始 " + label, 0, memoryUsage / 1024, 0, gcCount, gcTime);
        System.out.println("[性能监控] " + label + " 阶段开始，初始内存: " + (memoryUsage / (1024 * 1024)) + " MB");

        // 添加初始内存样本
        addMemorySample(phase, timestamp, memoryUsage);
    }

    /**
     * 记录阶段结束
     */
    private void recordEnd(String phase, String label) {
        // 记录阶段结束前先进行GC
        System.gc();
        try {
            Thread.sleep(500); // 等待GC完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long timestamp = System.currentTimeMillis();
        long memoryUsage = getCurrentMemoryUsage();
        long gcCount = getTotalGcCount();
        long gcTime = getTotalGcTime();

        long startTime = startTimes.getOrDefault(phase, timestamp);
        long startMem = startMemory.getOrDefault(phase, memoryUsage);
        long duration = timestamp - startTime;
        long memoryDelta = memoryUsage - startMem;

        endTimes.put(phase, timestamp);
        endMemory.put(phase, memoryUsage);
        phaseEndGcCount.put(phase, gcCount);
        phaseEndGcTime.put(phase, gcTime);

        // 添加最终内存样本
        addMemorySample(phase, timestamp, memoryUsage);

        // 计算更准确的内存增长（从采样中获取）
        long effectiveMemoryGrowth = calculateEffectiveMemoryGrowth(phase);

        logPerformance(timestamp, phase, "结束 " + label, duration,
                memoryUsage / 1024, effectiveMemoryGrowth / 1024,
                gcCount - phaseStartGcCount.getOrDefault(phase, 0L),
                gcTime - phaseStartGcTime.getOrDefault(phase, 0L));

        System.out.println("[性能监控] " + label + " 阶段结束，耗时: " + duration +
                           " ms，内存增长: " + (effectiveMemoryGrowth / (1024 * 1024)) + " MB" +
                           "，峰值内存: " + (getPeakMemory(phase) / (1024 * 1024)) + " MB");
    }

    /**
     * 开始定期内存采样
     */
    private void startMemorySampling(String phase) {
        if (samplingTimer != null) {
            samplingTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (currentPhase != null) {
                        long timestamp = System.currentTimeMillis();
                        long currentMemory = getCurrentMemoryUsage();
                        addMemorySample(currentPhase, timestamp, currentMemory);
                        updatePeakMemory(currentPhase);
                    }
                }
            }, SAMPLING_INTERVAL_MS, SAMPLING_INTERVAL_MS);
        }
    }

    /**
     * 添加内存采样
     */
    private void addMemorySample(String phase, long timestamp, long memoryUsed) {
        Runtime runtime = Runtime.getRuntime();
        MemorySample sample = new MemorySample(
            timestamp,
            memoryUsed,
            runtime.totalMemory(),
            runtime.maxMemory()
        );

        List<MemorySample> samples = memorySamples.get(phase);
        if (samples != null) {
            synchronized (samples) {
                samples.add(sample);
            }
        }

        // 记录到采样日志
        try (PrintWriter writer = new PrintWriter(new FileWriter(MEMORY_SAMPLES_LOG, true))) {
            writer.println(timestamp + "," + phase + "," +
                          (memoryUsed / (1024 * 1024)) + "," +
                          (runtime.totalMemory() / (1024 * 1024)) + "," +
                          (runtime.maxMemory() / (1024 * 1024)) + "," +
                          getTotalGcCount());
        } catch (IOException e) {
            // 简单记录错误但不中断
            System.err.println("无法写入内存采样日志: " + e.getMessage());
        }
    }

    /**
     * 从采样中计算有效内存增长
     */
    private long calculateEffectiveMemoryGrowth(String phase) {
        List<MemorySample> samples = memorySamples.get(phase);
        if (samples == null || samples.isEmpty() || samples.size() < 2) {
            // 如果没有足够的样本，则使用开始和结束内存差值
            return Math.max(0, endMemory.getOrDefault(phase, 0L) - startMemory.getOrDefault(phase, 0L));
        }

        // 找出最低的10%和最高的10%的内存使用
        List<MemorySample> sortedSamples = new ArrayList<>(samples);
        sortedSamples.sort((a, b) -> Long.compare(a.usedMemory, b.usedMemory));

        int lowIndex = Math.min(sortedSamples.size() - 1, Math.max(0, (int)(sortedSamples.size() * 0.1)));
        int highIndex = Math.max(0, Math.min(sortedSamples.size() - 1, (int)(sortedSamples.size() * 0.9)));

        long lowMemory = sortedSamples.get(lowIndex).usedMemory;
        long highMemory = sortedSamples.get(highIndex).usedMemory;

        return Math.max(0, highMemory - lowMemory);
    }

    /**
     * 记录内存峰值
     */
    public void updatePeakMemory(String phase) {
        long currentMemory = getCurrentMemoryUsage();
        long currentPeak = peakMemory.getOrDefault(phase, 0L);

        if (currentMemory > currentPeak) {
            peakMemory.put(phase, currentMemory);
            logPerformance(System.currentTimeMillis(), phase, "内存峰值更新", 0,
                           currentMemory / 1024, (currentMemory - currentPeak) / 1024,
                           getTotalGcCount(), getTotalGcTime());

            System.out.println("[性能监控] " + getPhaseLabel(phase) +
                             " 阶段内存峰值更新: " + (currentMemory / (1024 * 1024)) + " MB");
        }
    }

    /**
     * 记录当前处理进度
     */
    public void recordProgress(String phase, String operation, int processed, int total) {
        long timestamp = System.currentTimeMillis();
        long memoryUsage = getCurrentMemoryUsage();
        long startMem = startMemory.getOrDefault(phase, memoryUsage);
        long memoryDelta = memoryUsage - startMem;

        updatePeakMemory(phase);

        if (total > 0) {
            double percentage = (double) processed / total * 100;
            logPerformance(timestamp, phase, operation + " " + processed + "/" + total +
                          " (" + String.format("%.1f", percentage) + "%)", 0,
                          memoryUsage / 1024, memoryDelta / 1024,
                          getTotalGcCount(), getTotalGcTime());

            if (processed % 100 == 0 || processed == total) {
                System.out.println("[性能监控] " + operation + ": " + processed + "/" + total +
                                  " (" + String.format("%.1f", percentage) + "%), 内存: " +
                                  (memoryUsage / (1024 * 1024)) + " MB");
            }
        }
    }

    /**
     * 获取当前内存使用量
     */
    private long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * 获取总GC次数
     */
    private long getTotalGcCount() {
        long totalCount = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long count = gcBean.getCollectionCount();
            if (count > 0) {
                totalCount += count;
            }
        }
        return totalCount;
    }

    /**
     * 获取总GC时间
     */
    private long getTotalGcTime() {
        long totalTime = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long time = gcBean.getCollectionTime();
            if (time > 0) {
                totalTime += time;
            }
        }
        return totalTime;
    }

    /**
     * 获取阶段GC次数
     */
    private long getPhaseGcCount(String phase) {
        long startCount = phaseStartGcCount.getOrDefault(phase, 0L);
        long endCount = phaseEndGcCount.getOrDefault(phase, startCount);
        return endCount - startCount;
    }

    /**
     * 获取指定阶段的持续时间
     */
    private long getDuration(String phase) {
        Long end = endTimes.get(phase);
        Long start = startTimes.get(phase);
        if (end == null || start == null) return 0;
        return end - start;
    }

    /**
     * 获取指定阶段的内存使用增长
     */
    private long getMemoryUsage(String phase) {
        Long end = endMemory.get(phase);
        Long start = startMemory.get(phase);
        if (end == null || start == null) return 0;
        return Math.max(0, end - start);
    }

    /**
     * 获取指定阶段的内存峰值
     */
    private long getPeakMemory(String phase) {
        return peakMemory.getOrDefault(phase, 0L);
    }

    /**
     * 获取阶段标签
     */
    private String getPhaseLabel(String phase) {
        switch (phase) {
            case "Build": return "全阶段";
            case "RuleReading": return "规则读取";
            case "RuleTransformation": return "规则转换";
            case "TopoNetGeneration": return "TopoNet生成";
            default: return phase;
        }
    }

    /**
     * 记录性能数据到日志文件
     */
    private void logPerformance(long timestamp, String phase, String operation,
                              long duration, long memoryKb, long memoryDeltaKb,
                              long gcCount, long gcTime) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(PERFORMANCE_LOG, true))) {
            writer.println(timestamp + "," + phase + "," + operation + "," +
                          duration + "," + memoryKb + "," + memoryDeltaKb + "," +
                          gcCount + "," + gcTime);
        } catch (IOException e) {
            System.err.println("无法写入性能日志: " + e.getMessage());
        }
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        if (samplingTimer != null) {
            samplingTimer.cancel();
            samplingTimer = null;
        }

        startTimes.clear();
        endTimes.clear();
        startMemory.clear();
        endMemory.clear();
        peakMemory.clear();
        memorySamples.clear();
        phaseStartGcCount.clear();
        phaseEndGcCount.clear();
        phaseStartGcTime.clear();
        phaseEndGcTime.clear();
    }
}
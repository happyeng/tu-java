package org.sngroup.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sngroup.verifier.BDDEngine;
import org.sngroup.verifier.Lec;

/**
 * 监控BDD规则转换的性能
 * 专注于规则转换阶段的性能指标收集
 */
public class BDDRuleTransformMonitor {
    private static final BDDRuleTransformMonitor INSTANCE = new BDDRuleTransformMonitor();

    // 输出文件
    private static final String RULE_TRANSFORM_LOG = "./rule_transform.csv";
    private static final String RULE_STATS_FILE = "./rule_transform_stats.txt";

    // 规则转换统计
    private final AtomicInteger totalRules = new AtomicInteger(0);
    private final AtomicInteger totalLecs = new AtomicInteger(0);
    private final AtomicLong totalTransformTime = new AtomicLong(0);

    // 设备规则统计
    private final Map<String, Integer> deviceRuleCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> deviceLecCount = new ConcurrentHashMap<>();
    private final Map<String, Long> deviceTransformTime = new ConcurrentHashMap<>();

    // 规则类型统计
    private final Map<String, AtomicInteger> ruleTypeCount = new ConcurrentHashMap<>();

    // 规则转换时间分布
    private final List<Long> transformTimeSamples = new ArrayList<>();
    private static final int MAX_SAMPLES = 1000;

    // 规则成功转换率
    private final AtomicInteger successfulTransforms = new AtomicInteger(0);
    private final AtomicInteger failedTransforms = new AtomicInteger(0);

    private BDDRuleTransformMonitor() {
        // 创建CSV日志文件
        try (PrintWriter writer = new PrintWriter(new FileWriter(RULE_TRANSFORM_LOG))) {
            writer.println("timestamp,device,rule_index,rule_type,prefix_length,ports,transform_time_ms,result_size,memory_delta_kb");
        } catch (IOException e) {
            System.err.println("无法创建规则转换日志: " + e.getMessage());
        }
    }

    public static BDDRuleTransformMonitor getInstance() {
        return INSTANCE;
    }

    /**
     * 开始设备规则转换监控
     */
    public void startDeviceRuleTransform(String deviceName, int ruleCount) {
        deviceRuleCount.put(deviceName, ruleCount);
        deviceTransformTime.put(deviceName, System.currentTimeMillis());

        logRuleTransform(deviceName, -1, "START", 0, 0, 0, 0, 0);
        System.out.println("[规则转换] 开始处理设备 " + deviceName + " 的 " + ruleCount + " 条规则");
    }

    /**
     * 结束设备规则转换监控
     */
    public void endDeviceRuleTransform(String deviceName, int lecCount) {
        long startTime = deviceTransformTime.getOrDefault(deviceName, System.currentTimeMillis());
        long duration = System.currentTimeMillis() - startTime;

        deviceLecCount.put(deviceName, lecCount);
        totalLecs.addAndGet(lecCount);

        int ruleCount = deviceRuleCount.getOrDefault(deviceName, 0);
        totalRules.addAndGet(ruleCount);

        if (ruleCount > 0) {
            totalTransformTime.addAndGet(duration);

            logRuleTransform(deviceName, -1, "END", 0, 0, duration, lecCount, 0);

            System.out.println("[规则转换] 设备 " + deviceName + " 处理完成: " +
                             ruleCount + " 条规则转换为 " + lecCount + " 个LEC, 耗时: " +
                             duration + " ms (平均 " + (duration / ruleCount) + " ms/规则)");
        }
    }

    /**
     * 记录单条规则转换
     */
    public void recordRuleTransform(String deviceName, int ruleIndex, String ruleType,
                                   int prefixLength, int portCount, long transformTimeMs,
                                   int resultSize, long memoryDeltaKb) {
        logRuleTransform(deviceName, ruleIndex, ruleType, prefixLength,
                        portCount, transformTimeMs, resultSize, memoryDeltaKb);

        // 增加规则类型计数
        ruleTypeCount.computeIfAbsent(ruleType, k -> new AtomicInteger(0))
                    .incrementAndGet();

        // 记录转换时间样本
        synchronized (transformTimeSamples) {
            if (transformTimeSamples.size() < MAX_SAMPLES) {
                transformTimeSamples.add(transformTimeMs);
            }
        }

        // 记录成功/失败
        if (resultSize > 0) {
            successfulTransforms.incrementAndGet();
        } else {
            failedTransforms.incrementAndGet();
        }
    }

    /**
     * 记录LEC生成结果
     */
    public void recordLecGeneration(String deviceName, int lecCount, long timeMs) {
        System.out.println("[规则转换] 设备 " + deviceName + " 生成了 " + lecCount +
                         " 个LEC, 耗时: " + timeMs + " ms");
    }

    /**
     * 分析LEC和规则的关系
     */
    public void analyzeLecDistribution(Map<String, HashSet<Lec>> deviceLecs) {
        // 分析LEC分布情况
        int totalLecs = 0;
        int maxLecs = 0;
        String maxLecDevice = "";

        Map<String, Integer> lecTypeCount = new HashMap<>();

        for (Map.Entry<String, HashSet<Lec>> entry : deviceLecs.entrySet()) {
            String device = entry.getKey();
            int count = entry.getValue().size();
            totalLecs += count;

            if (count > maxLecs) {
                maxLecs = count;
                maxLecDevice = device;
            }

            // 统计LEC类型
            for (Lec lec : entry.getValue()) {
                String typeStr = lec.type.toString();
                lecTypeCount.put(typeStr, lecTypeCount.getOrDefault(typeStr, 0) + 1);
            }
        }

        System.out.println("[LEC分析] 总计 " + totalLecs + " 个LEC, 平均每个设备 " +
                         (deviceLecs.size() > 0 ? totalLecs / deviceLecs.size() : 0) +
                         " 个, 最大 " + maxLecs + " 个 (设备: " + maxLecDevice + ")");
    }

    /**
     * 生成规则转换统计报告
     */
    public void generateReport() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(RULE_STATS_FILE))) {
            writer.println("=========== 规则转换性能统计 ===========");
            writer.println("生成时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println();

            writer.println("---------- 总体统计 ----------");
            writer.println("总规则数: " + totalRules.get());
            writer.println("总LEC数: " + totalLecs.get());
            writer.println("规则转换总时间: " + totalTransformTime.get() + " ms");
            if (totalRules.get() > 0) {
                writer.println("平均每条规则转换时间: " + (totalTransformTime.get() / totalRules.get()) + " ms");
                writer.println("平均每条规则生成LEC数: " + String.format("%.2f", (double)totalLecs.get() / totalRules.get()));
            }
            writer.println("转换成功率: " + String.format("%.2f%%",
                          (double)successfulTransforms.get() * 100 /
                          (successfulTransforms.get() + failedTransforms.get())));
            writer.println();

            writer.println("---------- 设备统计 ----------");
            for (String device : deviceRuleCount.keySet()) {
                int rules = deviceRuleCount.getOrDefault(device, 0);
                int lecs = deviceLecCount.getOrDefault(device, 0);
                long time = deviceTransformTime.containsKey(device) ?
                          System.currentTimeMillis() - deviceTransformTime.get(device) : 0;

                if (rules > 0) {
                    writer.println("设备: " + device);
                    writer.println("  规则数: " + rules);
                    writer.println("  LEC数: " + lecs);
                    writer.println("  转换时间: " + time + " ms");
                    writer.println("  平均每条规则时间: " + (time / rules) + " ms");
                    writer.println("  平均每条规则LEC: " + String.format("%.2f", (double)lecs / rules));
                    writer.println();
                }
            }

            writer.println("---------- 规则类型分布 ----------");
            for (Map.Entry<String, AtomicInteger> entry : ruleTypeCount.entrySet()) {
                int count = entry.getValue().get();
                double percentage = totalRules.get() > 0 ?
                                   (double)count * 100 / totalRules.get() : 0;
                writer.println(entry.getKey() + ": " + count + " (" +
                              String.format("%.2f%%", percentage) + ")");
            }
            writer.println();

            writer.println("---------- 转换时间分布 ----------");
            if (!transformTimeSamples.isEmpty()) {
                // 计算分位数
                List<Long> samples = new ArrayList<>(transformTimeSamples);
                samples.sort(Long::compare);

                int size = samples.size();
                long min = samples.get(0);
                long p25 = samples.get(size / 4);
                long p50 = samples.get(size / 2);
                long p75 = samples.get(size * 3 / 4);
                long p90 = samples.get((int)(size * 0.9));
                long p99 = samples.get((int)(size * 0.99));
                long max = samples.get(size - 1);

                writer.println("样本数: " + size);
                writer.println("最小值: " + min + " ms");
                writer.println("25%分位数: " + p25 + " ms");
                writer.println("中位数: " + p50 + " ms");
                writer.println("75%分位数: " + p75 + " ms");
                writer.println("90%分位数: " + p90 + " ms");
                writer.println("99%分位数: " + p99 + " ms");
                writer.println("最大值: " + max + " ms");
            } else {
                writer.println("无转换时间样本");
            }

            writer.println("\n详细转换日志已保存至: " + RULE_TRANSFORM_LOG);
            writer.println("======================================");

        } catch (IOException e) {
            System.err.println("无法创建规则转换统计报告: " + e.getMessage());
        }

        System.out.println("规则转换统计报告已生成: " + RULE_STATS_FILE);
    }

    /**
     * 记录规则转换日志
     */
    private void logRuleTransform(String deviceName, int ruleIndex, String ruleType,
                                int prefixLength, int portCount, long transformTimeMs,
                                int resultSize, long memoryDeltaKb) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(RULE_TRANSFORM_LOG, true))) {
            writer.println(System.currentTimeMillis() + "," + deviceName + "," +
                          ruleIndex + "," + ruleType + "," + prefixLength + "," +
                          portCount + "," + transformTimeMs + "," + resultSize + "," +
                          memoryDeltaKb);
        } catch (IOException e) {
            // 简单记录错误但不中断执行
            System.err.println("无法写入规则转换日志: " + e.getMessage());
        }
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        deviceRuleCount.clear();
        deviceLecCount.clear();
        deviceTransformTime.clear();
        ruleTypeCount.clear();
        transformTimeSamples.clear();
    }
}
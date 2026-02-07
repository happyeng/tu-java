/*
 * This program is free software: you can redistribute it and/or modify it under the terms of
 *  the GNU General Public License as published by the Free Software Foundation, either
 *   version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *   PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this
 *  program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.sngroup.test.runner;

import org.sngroup.Configuration;
import org.sngroup.util.*;
import org.sngroup.verifier.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EdgeConnectivityRunner - 优化版本
 * 基于真实拓扑的精确连接关系验证
 * 采用分批处理优化内存使用
 *
 * 输出说明：
 * - 正常结果：设备对:网段1, 网段2, ...
 * - NULL结果：设备对:NULL（仅当转发表为空但有物理连接时）
 * - 无输出：有转发表但无匹配规则的连接不输出
 */
public class EdgeConnectivityRunner extends TopoRunner {

    // 转发规则类
    public static class Rule {
        long ip;
        int prefixLen;
        Set<String> ports;

        public Rule(long ip, int prefixLen, Set<String> ports) {
            this.ip = ip;
            this.prefixLen = prefixLen;
            this.ports = ports;
        }
    }

    // 验证结果类
    private static class EdgeConnectivityResult {
        String srcDevice;
        String dstDevice;
        Set<String> reachableSegments;

        public EdgeConnectivityResult(String src, String dst, Set<String> segments) {
            this.srcDevice = src;
            this.dstDevice = dst;
            this.reachableSegments = segments;
        }
    }

    // 节点分类
    private Set<String> edgeDevices;
    private Set<String> aggDevices;
    private Set<String> coreDevices;

    // 连接关系（基于真实拓扑）
    private Map<String, Set<String>> edgeToAggCoreConnections;
    private Map<String, Set<String>> aggToEdgeConnections;
    private Map<String, Set<String>> coreToEdgeConnections;

    // 性能参数
    private final int devicesPerBatch = 3000;
    private final int maxThreads = Runtime.getRuntime().availableProcessors() * 2;
    private final ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

    // 当前批次的转发表缓存
    private final Map<String, List<Rule>> currentBatchRules = new ConcurrentHashMap<>();

    // 收集所有结果，最后统一排序写入
    private final List<EdgeConnectivityResult> allResults = Collections.synchronizedList(new ArrayList<>());

    // 文件路径管理 - 实例级别，避免与其他命令冲突
    private String networkName = "";
    private String resultFileName = "edge_connectivity_results.txt";
    private String resultFilePath = null;
    private boolean isFileInitialized = false;

    // 全局实例管理，用于关闭钩子
    private static EdgeConnectivityRunner globalInstance = null;

    public EdgeConnectivityRunner() {
        super();
        this.edgeDevices = new HashSet<>();
        this.aggDevices = new HashSet<>();
        this.coreDevices = new HashSet<>();
        this.edgeToAggCoreConnections = new HashMap<>();
        this.aggToEdgeConnections = new HashMap<>();
        this.coreToEdgeConnections = new HashMap<>();
    }

    /**
     * 从命令行参数初始化 - 实例方法
     */
    public void initializeFromArgsInstance(String[] args) {
        if (args != null && args.length >= 2) {
            for (int i = 0; i < args.length - 1; i++) {
                if ("edge-connectivity".equals(args[i])) {
                    String networkParam = args[i + 1];
                    if (networkParam.contains("/")) {
                        networkParam = networkParam.substring(0, networkParam.indexOf("/"));
                    }
                    setNetworkNameInstance(networkParam);
                    break;
                }
            }
        }
        initializeFileWritingInstance();
    }

    /**
     * 设置为全局实例，用于关闭钩子
     */
    public void setAsGlobalInstance() {
        globalInstance = this;
    }

    /**
     * 静态方法：清理全局实例，供关闭钩子调用
     */
    public static void finalizeGlobalInstance() {
        if (globalInstance != null) {
            globalInstance.finalizeFileWritingInstance();
        }
    }

    /**
     * 设置网络名称 - 实例方法
     */
    public void setNetworkNameInstance(String networkName) {
        this.networkName = networkName;
        updateResultFilePathInstance();
    }

    /**
     * 更新结果文件路径 - 实例方法
     */
    private void updateResultFilePathInstance() {
        if (networkName != null && !networkName.isEmpty()) {
            String dirname = "config/" + networkName;
            resultFilePath = dirname + (dirname.endsWith("/") ? "" : "/") + resultFileName;
        } else {
            resultFilePath = resultFileName;
        }
    }

    /**
     * 初始化文件写入 - 实例方法
     */
    private void initializeFileWritingInstance() {
        if (isFileInitialized) {
            return;
        }

        try {
            updateResultFilePathInstance();
            File resultFile = new File(resultFilePath);
            File parentDir = resultFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            System.out.println("Edge连接性验证结果存放路径: " + resultFile.getAbsolutePath());
            System.out.println("开始Edge连接性验证和结果写入...");
            isFileInitialized = true;
        } catch (Exception e) {
            System.err.println("初始化Edge连接性文件写入失败: " + e.getMessage());
        }
    }

    /**
     * 完成文件写入 - 实例方法
     */
    public void finalizeFileWritingInstance() {
        if (!isFileInitialized) {
            return;
        }

        try {
            if (resultFilePath != null) {
                File resultFile = new File(resultFilePath);
                System.out.println("Edge连接性验证结果存放路径: " + resultFile.getAbsolutePath());
                System.out.println("Edge连接性验证结果写入完成。");
            }
            isFileInitialized = false;
        } catch (Exception e) {
            System.err.println("完成Edge连接性文件写入失败: " + e.getMessage());
        }
    }

    @Override
    public Device getDevice(String name) {
        return null; // 不使用Device对象
    }

    @Override
    public ThreadPool getThreadPool() {
        return null; // 使用自定义ExecutorService
    }

    @Override
    public void build() {
        System.out.println("==========================================");
        System.out.println("开始构建高性能Edge连接性验证器");
        System.out.println("网络名称: " + networkName);
        System.out.println("==========================================");

        long startTime = System.currentTimeMillis();

        // 1. 分析拓扑，识别节点类型（基于真实拓扑）
        analyzeTopologyAndClassifyNodes();

        // 2. 找出连接关系（基于真实拓扑）
        findConnectionRelationships();

        long endTime = System.currentTimeMillis();
        System.out.println("构建完成，耗时: " + (endTime - startTime) + "ms");
        System.out.println("准备分批处理设备");
    }

    /**
     * 分析拓扑结构，识别节点类型（基于真实拓扑）
     */
    private void analyzeTopologyAndClassifyNodes() {
        for (String deviceName : network.devicePorts.keySet()) {
            if (isEdgeDevice(deviceName)) {
                edgeDevices.add(deviceName);
            } else if (isAggDevice(deviceName)) {
                aggDevices.add(deviceName);
            } else if (isCoreDevice(deviceName)) {
                coreDevices.add(deviceName);
            } else {
                aggDevices.add(deviceName); // 默认为agg
            }
        }

        System.out.println("节点分类: Edge(" + edgeDevices.size() + "), Agg(" +
                         aggDevices.size() + "), Core(" + coreDevices.size() + ")");
    }

    private boolean isEdgeDevice(String deviceName) {
        String lowerName = deviceName.toLowerCase();
        return lowerName.startsWith("edge");
    }

    private boolean isAggDevice(String deviceName) {
        return deviceName.toLowerCase().startsWith("aggr");
    }

    private boolean isCoreDevice(String deviceName) {
        String lowerName = deviceName.toLowerCase();
        return lowerName.contains("core") || lowerName.contains("spine");
    }

    /**
     * 找出所有连接关系（基于真实拓扑）
     */
    private void findConnectionRelationships() {
        // Edge设备的连接关系
        for (String edgeDevice : edgeDevices) {
            Set<String> connectedAggCores = new HashSet<>();
            Map<String, Set<DevicePort>> connections = network.devicePorts.get(edgeDevice);

            if (connections != null) {
                for (String connectedDevice : connections.keySet()) {
                    if (aggDevices.contains(connectedDevice)) {
                        connectedAggCores.add(connectedDevice);
                        aggToEdgeConnections.computeIfAbsent(connectedDevice, k -> new HashSet<>()).add(edgeDevice);
                    } else if (coreDevices.contains(connectedDevice)) {
                        connectedAggCores.add(connectedDevice);
                        coreToEdgeConnections.computeIfAbsent(connectedDevice, k -> new HashSet<>()).add(edgeDevice);
                    }
                }
            }

            if (!connectedAggCores.isEmpty()) {
                edgeToAggCoreConnections.put(edgeDevice, connectedAggCores);
            }
        }

        int totalConnections = edgeToAggCoreConnections.values().stream().mapToInt(Set::size).sum() +
                              aggToEdgeConnections.values().stream().mapToInt(Set::size).sum() +
                              coreToEdgeConnections.values().stream().mapToInt(Set::size).sum();
        System.out.println("连接关系: " + totalConnections + " 个连接");
    }

    @Override
    public void start() {
        System.out.println("开始分批验证...");
        long startTime = System.currentTimeMillis();

        try {
            // 获取所有设备列表（基于真实拓扑）
            List<String> allDevices = new ArrayList<>();
            allDevices.addAll(edgeDevices);
            allDevices.addAll(aggDevices);
            allDevices.addAll(coreDevices);

            System.out.println("发现设备总数: " + allDevices.size());

            // 分批处理
            List<List<String>> batches = createBatches(allDevices, devicesPerBatch);
            System.out.println("分" + batches.size() + "批处理，每批" + devicesPerBatch + "个设备");

            AtomicInteger processedBatches = new AtomicInteger(0);

            for (List<String> batch : batches) {
                processBatch(batch);
                int completed = processedBatches.incrementAndGet();
                System.out.println("已完成批次: " + completed + "/" + batches.size());

                // 清理内存
                clearCurrentBatch();
                System.gc(); // 建议垃圾回收
            }

        } catch (Exception e) {
            System.err.println("验证过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }

        // 统一排序并写入结果
        writeResultsInOrder();

        long endTime = System.currentTimeMillis();
        System.out.println("验证完成！总耗时: " + (endTime - startTime) + "ms");
        System.out.println("总验证结果数: " + allResults.size());

        finalizeFileWritingInstance();
        executor.shutdown();
    }

    /**
     * 创建批次
     */
    private List<List<String>> createBatches(List<String> devices, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < devices.size(); i += batchSize) {
            int end = Math.min(i + batchSize, devices.size());
            batches.add(new ArrayList<>(devices.subList(i, end)));
        }
        return batches;
    }

    /**
     * 处理单个批次
     */
    private void processBatch(List<String> batchDevices) {
        System.out.println("开始处理批次，设备数: " + batchDevices.size());

        // 1. 并行加载当前批次的转发表
        loadForwardingTablesForBatch(batchDevices);

        // 2. 并行处理每个设备的连接性验证
        processDeviceConnectivity(batchDevices);

        System.out.println("批次处理完成");
    }

    /**
     * 为当前批次加载转发表
     */
    private void loadForwardingTablesForBatch(List<String> deviceNames) {
        System.out.println("加载批次转发表...");

        CompletableFuture<Void>[] futures = deviceNames.stream()
            .map(deviceName -> CompletableFuture.runAsync(() -> {
                loadDeviceRules(deviceName);
            }, executor))
            .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();

        // 统计加载情况
        int loadedCount = currentBatchRules.size();
        int emptyCount = deviceNames.size() - loadedCount;
        System.out.println("批次转发表加载完成，已加载设备数: " + loadedCount + "，转发表为空设备数: " + emptyCount);
    }

    /**
     * 加载单个设备的转发规则
     */
    private void loadDeviceRules(String deviceName) {
        try {
            String rulesFile = Configuration.getConfiguration().getDeviceRuleFile(deviceName);
            File file = new File(rulesFile);

            if (!file.exists()) {
                // 文件不存在，但可能有物理连接，在处理时会写入NULL结果
                return;
            }

            if (file.length() == 0) {
                // 文件为空，但可能有物理连接，在处理时会写入NULL结果
                return;
            }

            List<Rule> rules = new ArrayList<>();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8),
                    32768)) {

                String line;
                while ((line = br.readLine()) != null) {
                    String[] tokens = line.split("\\s+");
                    if (tokens.length >= 4 &&
                        (tokens[0].equals("fw") || tokens[0].equals("ALL") ||
                         tokens[0].equals("ANY") || tokens[0].equals("any"))) {

                        try {
                            Set<String> ports = new HashSet<>();
                            for (int i = 3; i < tokens.length; i++) {
                                ports.add(tokens[i]);
                            }

                            long ip = Long.parseLong(tokens[1]);
                            int prefix = Integer.parseInt(tokens[2]);

                            Rule rule = new Rule(ip, prefix, ports);
                            rules.add(rule);
                        } catch (NumberFormatException e) {
                            // 跳过无效规则
                        }
                    }
                }
            }

            if (!rules.isEmpty()) {
                currentBatchRules.put(deviceName, rules);
            }
            // 如果转发表文件存在但没有有效规则，在处理时也会写入NULL结果

        } catch (IOException e) {
            // 静默处理IO错误，在处理时会写入NULL结果
        }
    }

    /**
     * 处理设备连接性验证
     */
    private void processDeviceConnectivity(List<String> deviceNames) {
        // 处理当前批次中的所有设备，包括转发表为空的设备
        CompletableFuture<Void>[] futures = deviceNames.stream()
            .map(deviceName -> CompletableFuture.runAsync(() -> {
                processDeviceConnections(deviceName);
            }, executor))
            .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
    }

    /**
     * 处理单个设备的所有连接
     */
    private void processDeviceConnections(String deviceName) {
        List<Rule> rules = currentBatchRules.get(deviceName);

        // 获取目标设备列表（基于真实拓扑连接关系）
        Set<String> targetDevices = getTargetDevices(deviceName);

        if (targetDevices != null) {
            if (rules == null || rules.isEmpty()) {
                // 转发表为空但有物理连接，写入NULL结果
                for (String targetDevice : targetDevices) {
                    EdgeConnectivityResult result = new EdgeConnectivityResult(
                        deviceName, targetDevice, new HashSet<>(Arrays.asList("NULL")));
                    allResults.add(result);
                    System.out.println("设备 [" + deviceName + "] 转发表为空，但与 [" + targetDevice + "] 有物理连接，写入NULL结果");
                }
            } else {
                // 有转发表，正常处理：查找可达网段
                for (String targetDevice : targetDevices) {
                    Set<String> reachableSegments = getReachableSegments(deviceName, targetDevice, rules);

                    if (!reachableSegments.isEmpty()) {
                        EdgeConnectivityResult result = new EdgeConnectivityResult(
                            deviceName, targetDevice, reachableSegments);
                        allResults.add(result);
                    }
                    // 有转发表但无匹配规则的情况，不写入任何结果
                }
            }
        }
    }

    /**
     * 获取源设备的目标设备列表（基于真实拓扑连接关系）
     */
    private Set<String> getTargetDevices(String srcDevice) {
        if (edgeToAggCoreConnections.containsKey(srcDevice)) {
            return edgeToAggCoreConnections.get(srcDevice);
        } else if (aggToEdgeConnections.containsKey(srcDevice)) {
            return aggToEdgeConnections.get(srcDevice);
        } else if (coreToEdgeConnections.containsKey(srcDevice)) {
            return coreToEdgeConnections.get(srcDevice);
        }
        return null;
    }

    /**
     * 获取可达网段（基于真实端口连接）
     */
    private Set<String> getReachableSegments(String srcDevice, String dstDevice, List<Rule> rules) {
        Set<String> segments = new HashSet<>();

        // 获取源设备连接到目标设备的真实端口
        Set<String> targetPorts = getPortsToTarget(srcDevice, dstDevice);

        if (targetPorts.isEmpty()) {
            return segments;
        }

        // 查找使用这些端口的转发规则
        for (Rule rule : rules) {
            // 检查规则是否使用了连接到目标设备的端口
            boolean hasMatchingPort = rule.ports.stream()
                .anyMatch(targetPorts::contains);

            if (hasMatchingPort) {
                String segment = ipPrefixToSegment(rule.ip, rule.prefixLen);
                segments.add(segment);
            }
        }

        return segments;
    }

    /**
     * 获取源设备连接到目标设备的端口（基于真实拓扑）
     */
    private Set<String> getPortsToTarget(String srcDevice, String dstDevice) {
        Set<String> ports = new HashSet<>();
        Map<String, Set<DevicePort>> connections = network.devicePorts.get(srcDevice);

        if (connections != null) {
            Set<DevicePort> devicePorts = connections.get(dstDevice);
            if (devicePorts != null) {
                for (DevicePort dp : devicePorts) {
                    ports.add(dp.getPortName());
                }
            }
        }

        return ports;
    }

    /**
     * IP前缀转网段字符串
     */
    private String ipPrefixToSegment(long ip, int prefixLen) {
        return String.format("%d.%d.%d.%d/%d",
                           (ip >> 24) & 0xFF, (ip >> 16) & 0xFF,
                           (ip >> 8) & 0xFF, ip & 0xFF, prefixLen);
    }

    /**
     * 统一排序并写入结果
     */
    private void writeResultsInOrder() {
        System.out.println("开始排序并写入结果...");

        try {
            updateResultFilePathInstance();

            // 按照设备名称排序：edge开头的设备优先
            allResults.sort((r1, r2) -> {
                String src1 = r1.srcDevice.toLowerCase();
                String src2 = r2.srcDevice.toLowerCase();

                // 判断设备类型
                boolean isEdge1 = src1.startsWith("edge");
                boolean isEdge2 = src2.startsWith("edge");

                // edge开头的设备优先
                if (isEdge1 && !isEdge2) {
                    return -1;
                } else if (!isEdge1 && isEdge2) {
                    return 1;
                }

                // 在相同类型内，按源设备名称排序
                int srcCompare = r1.srcDevice.compareTo(r2.srcDevice);
                if (srcCompare != 0) {
                    return srcCompare;
                }

                // 源设备相同时，按目标设备名称排序
                return r1.dstDevice.compareTo(r2.dstDevice);
            });

            // 统计NULL结果
            long nullResults = allResults.stream()
                .filter(result -> result.reachableSegments.contains("NULL"))
                .count();
            long validResults = allResults.size() - nullResults;

            // 写入文件 - 简单格式，每行一个设备对
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(resultFilePath), 65536)) {

                for (EdgeConnectivityResult result : allResults) {
                    String devicePair = result.srcDevice + "-" + result.dstDevice;
                    String segmentList = String.join(", ", result.reachableSegments);
                    writer.write(devicePair + ":" + segmentList + "\n");
                }

                writer.flush();
            }

            System.out.println("结果写入完成，共写入 " + allResults.size() + " 条记录");
            System.out.println("其中有效结果: " + validResults + " 条，NULL结果: " + nullResults + " 条");

        } catch (Exception e) {
            System.err.println("写入结果文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 清理当前批次的数据
     */
    private void clearCurrentBatch() {
        currentBatchRules.clear();
        System.out.println("已清理当前批次的转发表缓存");
    }

    public void close() {
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
    }

    public void awaitFinished() {
        // 已在start()方法中等待完成
    }

    @Override
    public void sendCount(Context ctx, DevicePort sendPort, BDDEngine bddEngine) {
        // 不需要此方法
    }

    @Override
    public long getInitTime() {
        return 0;
    }
}
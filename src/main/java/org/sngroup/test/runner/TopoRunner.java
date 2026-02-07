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
 *
 * Authors: Chenyang Huang (Xiamen University) <xmuhcy@stu.xmu.edu.cn>
 *          Qiao Xiang     (Xiamen University) <xiangq27@gmail.com>
 *          Ridi Wen       (Xiamen University) <23020211153973@stu.xmu.edu.cn>
 *          Yuxin Wang     (Xiamen University) <yuxxinwang@gmail.com>
 */

package org.sngroup.test.runner;

import jdd.bdd.BDD;
import org.sngroup.Configuration;
import org.sngroup.util.*;
import org.sngroup.verifier.*;

import java.io.*;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.sngroup.verifier.npbdd.BDDPredicateRegistry;
import org.sngroup.verifier.npbdd.BDDPredicateCache;
import org.sngroup.verifier.npnet.Neighborhood;
import org.sngroup.verifier.npnet.NeighborhoodBuilder;

public class TopoRunner extends Runner {

    private static final int THREAD_POOL_READ_SIZE = 1;
    public ThreadPool threadPool;
    public static Map<String, Device> devices;

    public int ruleCnt = 0;

    public static boolean isIpv6 = false;
    public static boolean isIpv4withS = false;

    private int poolSize = 20;

    private static Map<String, TopoNet> topoNetMap;

    private static Map<Integer, HashSet<TopoNet>> topoNetGroupMap = new HashMap<>();
    Set<Integer> dvNetSet;

    public static DVNet srcNet;

    public static BDDEngine srcBdd;
    private NeighborhoodBuilder neighborhoodBuilder;
    private List<Neighborhood> neighborhoods;
    private boolean enableNPNet = true;  // 可通过系统属性 npnet.enabled 控制
    // 路径验证相关参数
    private int srcNodeBatchSize = 50;
    private int maxPathsPerSrcNode = 100;
    private long verificationTimeoutMillis = 3 * 60 * 1000;
    private BufferedWriter resultWriter;
    /**
     * 是否启用NP-BDD优化
     * 默认false，需要显式调用enableNPBDDOptimization()启用
     */
    private boolean enableNPBDD = false;

    /**
     * 是否在验证结束时打印详细的NP-BDD统计信息
     * 默认true
     */
    private boolean printDetailedStats = true;

    /**
     * 启用NP-BDD优化
     * 必须在build()之前调用
     */
    public void enableNPBDDOptimization() {
        this.enableNPBDD = true;
        BDDEngine.enableNPBDD();  // 设置全局标志
        System.out.println("[TopoRunner] NP-BDD优化已启用");
    }

    /**
     * 禁用NP-BDD优化（恢复原始模式）
     */
    public void disableNPBDDOptimization() {
        this.enableNPBDD = false;
        BDDEngine.disableNPBDD();
        System.out.println("[TopoRunner] NP-BDD优化已禁用");
    }

    /**
     * 设置是否打印详细统计信息
     */
    public void setPrintDetailedStats(boolean print) {
        this.printDetailedStats = print;
    }

    /**
     * 获取NP-BDD启用状态
     */
    public boolean isNPBDDEnabled() {
        return this.enableNPBDD;
    }

    // 验证结果类
    public static class VerificationResult {
        public boolean reachable;
        public List<String> path;
        public String matchedIP;
        public String matchedPrefix;

        public VerificationResult(boolean reachable, List<String> path, String matchedIP, String matchedPrefix) {
            this.reachable = reachable;
            this.path = path;
            this.matchedIP = matchedIP;
            this.matchedPrefix = matchedPrefix;
        }
    }

    // 转发表匹配 - 直接规则查找，不使用BDD
    public static class ForwardingEntry {
        public long ip;
        public int prefixLen;
        public Set<String> ports;
        public ForwardType forwardType;

        public ForwardingEntry(long ip, int prefixLen, Set<String> ports, ForwardType forwardType) {
            this.ip = ip;
            this.prefixLen = prefixLen;
            this.ports = ports;
            this.forwardType = forwardType;
        }

        // 检查此条目是否匹配给定的目标IP
        public boolean matches(long dstIP) {
            if (this.prefixLen == 0) return true; // 默认路由
            long mask = (-1L) << (32 - this.prefixLen);
            boolean result = (this.ip & mask) == (dstIP & mask);
            return result;
        }

        // 获取用于比较的掩码网络
        public long getNetwork() {
            if (this.prefixLen == 0) return 0;
            long mask = (-1L) << (32 - this.prefixLen);
            return this.ip & mask;
        }

        // 获取转发表中的原始IP格式字符串
        public String getOriginalIPString() {
            return this.ip + "/" + this.prefixLen;
        }

        // 获取条目的字符串表示（点分十进制IP格式，仅用于调试）
        public String getReadableString() {
            String ipStr = ((this.ip >> 24) & 0xFF) + "." +
                    ((this.ip >> 16) & 0xFF) + "." +
                    ((this.ip >> 8) & 0xFF) + "." +
                    (this.ip & 0xFF);
            return ipStr + "/" + this.prefixLen + " -> " + this.ports;
        }
    }

    // IPv6转发条目
    public static class ForwardingEntryIPV6 {
        public String ip;
        public int prefixLen;
        public Set<String> ports;
        public ForwardType forwardType;

        public ForwardingEntryIPV6(String ip, int prefixLen, Set<String> ports, ForwardType forwardType) {
            this.ip = ip;
            this.prefixLen = prefixLen;
            this.ports = ports;
            this.forwardType = forwardType;
        }

        // 简化的IPv6匹配 - 完整实现需要合适的IPv6地址解析
        public boolean matches(String dstIP) {
            if (this.prefixLen == 0) return true; // 默认路由
            // 简化：目前使用精确字符串前缀匹配
            return dstIP.startsWith(this.ip.substring(0, Math.min(this.ip.length(), this.prefixLen / 4)));
        }

        // 获取条目的字符串表示（保持原始格式）
        public String getOriginalIPString() {
            return this.ip + "/" + this.prefixLen;
        }
    }

    // 设备转发表缓存
    private final ConcurrentHashMap<String, List<ForwardingEntry>> deviceForwardingTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ForwardingEntryIPV6>> deviceForwardingTableIPV6 = new ConcurrentHashMap<>();

    // 并发控制
    private final ConcurrentHashMap<String, Object> deviceLocks = new ConcurrentHashMap<>();
    private final Set<String> loadedDevices = ConcurrentHashMap.newKeySet();

    public TopoRunner() {
        super();
        devices = new HashMap<>();
        dvNetSet = new HashSet<>();
        topoNetMap = new HashMap<>();

        // 初始化结果写入器
        try {
            String resultFileName = "verification_results.txt";
            resultWriter = new BufferedWriter(new FileWriter(resultFileName));
            System.out.println("结果文件已创建: " + resultFileName);

        } catch (IOException e) {
            System.err.println("创建结果文件失败: " + e.getMessage());
            e.printStackTrace();

            try {
                String backupFileName = "verification_results_" + System.currentTimeMillis() + ".txt";
                resultWriter = new BufferedWriter(new FileWriter(backupFileName));
                System.out.println("使用备份结果文件: " + backupFileName);
            } catch (IOException e2) {
                System.err.println("创建备份结果文件失败: " + e2.getMessage());
                resultWriter = null;
            }
        }
    }

    public void setSrcNodeBatchSize(int batchSize) {
        this.srcNodeBatchSize = batchSize;
    }

    public void setMaxPathsPerSrcNode(int maxPaths) {
        this.maxPathsPerSrcNode = maxPaths;
    }

    public void setVerificationTimeoutMinutes(int minutes) {
        this.verificationTimeoutMillis = minutes * 60 * 1000L;
    }

    public ThreadPool getThreadPool() {
        return threadPool;
    }

    public Device getDevice(String name) {
        return devices.get(name);
    }

    public void build() {
        // IPV6 OR IPV4
        if (isIpv6) BDDEngine.ipBits = 128;
        else BDDEngine.ipBits = 32;

        // ========== NP-BDD初始化（保留） ==========
        if (enableNPBDD) {
            System.out.println("[TopoRunner] 初始化NP-BDD组件...");
            BDDPredicateRegistry.getInstance().clear();
            BDDPredicateCache.getInstance().clear();
            System.out.println("[TopoRunner] NP-BDD组件初始化完成");
        }
        // ========== NP-BDD初始化结束 ==========

        srcBdd = new BDDEngine();

        if (srcBdd.getBDD() == null) {
            throw new RuntimeException("BDD初始化失败，可能内存不足");
        }

        System.out.println("Start Build in Runner!!!");
        srcNet = new DVNet(-1, srcBdd);

        int threadPoolSize = 10;
        try {
            threadPoolSize = Configuration.getConfiguration().getThreadPoolSize();
        } catch (Exception e) {
            System.err.println("无法获取线程池大小配置，使用默认值: " + threadPoolSize);
        }
        threadPool = ThreadPool.FixedThreadPool(threadPoolSize);
        devices.clear();

        // 1. 创建设备对象
        for (String deviceName : network.devicePorts.keySet()) {
            Device d = new Device(deviceName, network, this, threadPool);
            if (network.edgeDevices.contains(deviceName)) {
                TopoNet.edgeDevices.add(d);
            }
            devices.put(deviceName, d);
        }

        // 2. 【恢复】读取所有设备的规则 + 空间文件
        if (isIpv6 || isIpv4withS) readRuleByDeviceIPV6();
        else readRuleByDevice();

        // 3. 【恢复】srcBdd 编码所有规则为 LEC + 解析空间
        srcBddTransformAllRules();

        // 4. 生成 TopoNet（保留）
        genTopoNet();

        System.out.println("结点总数量: " + devices.size());
        System.out.println("S0结点数量: " + network.edgeDevices.size());
        System.out.println("表项总数量: " + ruleCnt);

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double usedMemoryMB = usedMemory / (1024.0 * 1024.0);
        System.out.printf("Memory usage (after build): %.2f M%n", usedMemoryMB);

        System.out.println("End Build in Runner!!!");
    }

    private void readRuleByDevice() {
        for (Map.Entry<String, Device> entry : devices.entrySet()) {
            String name = entry.getKey();
            Device device = entry.getValue();
            threadPool.execute(() -> {
                device.readOnlyRulesFile(Configuration.getConfiguration().getDeviceRuleFile(name));
            });
        }

        Device.readOnlySpaceFile(Configuration.getConfiguration().getSpaceFile());
        threadPool.awaitAllTaskFinished();
    }

    private void readRuleByDeviceIPV6() {
        for (Map.Entry<String, Device> entry : devices.entrySet()) {
            String name = entry.getKey();
            Device device = entry.getValue();
            threadPool.execute(() -> {
                if (isIpv4withS) {
                    device.readOnlyRulesFileIPV4_S(Configuration.getConfiguration().getDeviceRuleFile(name));
                } else {
                    device.readOnlyRulesFileIPV6(Configuration.getConfiguration().getDeviceRuleFile(name));
                }
            });
        }

        Device.readOnlySpaceFileIPV6(Configuration.getConfiguration().getSpaceFile());
        threadPool.awaitAllTaskFinished();
    }

    public void srcBddTransformAllRules() {
        long timePoint1 = System.currentTimeMillis();

        for (Device device : devices.values()) {
            // 跳过没有规则的设备
            if (device.rules == null || device.rules.isEmpty()) {
                continue;
            }

            if (!(isIpv6 || isIpv4withS)) {
                device.encodeRuleToLecFromScratch(srcNet); // IPV4
            } else {
                try {
                    device.encodeRuleToLecFromScratchIPV6(srcNet); // IPV6
                } catch (java.net.UnknownHostException e) {
                    throw new RuntimeException(e);
                }
            }
            ruleCnt += device.rules.size();
        }

        long timePoint2 = System.currentTimeMillis();
        System.out.println("规则转化所使用的时间: " + (timePoint2 - timePoint1) + "ms");

        // 解析空间到BDD
        if (!(isIpv6 || isIpv4withS)) {
            srcNet.srcDvNetParseAllSpace(Device.spaces);
        } else {
            srcNet.srcDvNetParseAllSpaceIPV6(Device.spacesIPV6);
        }

        long timePoint3 = System.currentTimeMillis();
        System.out.println("BDD编码所使用的总时间: " + (timePoint3 - timePoint1) + "ms");
    }

    @Override
    public void start() {
        long startTime = System.currentTimeMillis();
        System.out.println("Start verification...");

        // 初始化NP-BDD
        if (BDDEngine.isNPBDDEnabled()) {
            BDDPredicateCache.getInstance().clear();
            BDDPredicateRegistry.getInstance().clear();
            System.out.println("[NP-BDD] 缓存已清空, 开始验证");
        }

        // ===== 关键修复1: 创建共享BDD引擎队列并预填充 =====
        LinkedBlockingDeque<BDDEngine> sharedQueueBDD = new LinkedBlockingDeque<>();

        // 预先创建足够的BDD引擎实例
        int bddPoolSize = Math.max(10, topoNetMap.size());
        System.out.println("[BDD Pool] 初始化 " + bddPoolSize + " 个BDD引擎...");

        for (int i = 0; i < bddPoolSize; i++) {
            try {
                BDDEngine newEngine = new BDDEngine();
                // 关键：复制srcBdd的配置
                if (srcBdd != null) {
                    newEngine.copyFrom(srcBdd);
                }
                sharedQueueBDD.offer(newEngine);
            } catch (Exception e) {
                System.err.println("[BDD Pool] 创建BDD引擎 #" + i + " 失败: " + e.getMessage());
            }
        }
        System.out.println("[BDD Pool] 初始化完成，可用引擎数: " + sharedQueueBDD.size());
        // ===== 修复1结束 =====

        try {
            // === NP-Net模式: 先处理邻域, 再处理独立TopoNet ===
            if (enableNPNet && neighborhoods != null && !neighborhoods.isEmpty()) {
                System.out.println("[NP-Net] 使用邻域聚合模式验证");

                // 收集已被邻域覆盖的设备名
                Set<String> aggregatedDevices = new HashSet<>();
                for (Neighborhood nh : neighborhoods) {
                    for (Device d : nh.getDstDevices()) {
                        aggregatedDevices.add(d.name);
                    }
                }

                // Phase 1: 并行处理各个邻域
                System.out.println("[NP-Net] Phase 1: 处理 " + neighborhoods.size() + " 个邻域");
                for (Neighborhood nh : neighborhoods) {
                    if (nh.isWorthOptimizing()) {
                        processNeighborhood(nh, sharedQueueBDD);
                    } else {
                        // 不值得优化的邻域, 独立处理每个TopoNet
                        for (Map.Entry<String, TopoNet> entry : nh.getDeviceTopoNets().entrySet()) {
                            processStandaloneTopoNet(entry.getValue(), sharedQueueBDD);
                        }
                    }
                }

                // Phase 2: 处理不在任何邻域中的独立TopoNet
                int standaloneCount = 0;
                for (Map.Entry<String, TopoNet> entry : topoNetMap.entrySet()) {
                    if (!aggregatedDevices.contains(entry.getKey())) {
                        standaloneCount++;
                        processStandaloneTopoNet(entry.getValue(), sharedQueueBDD);
                    }
                }
                System.out.println("[NP-Net] Phase 2: 处理 " + standaloneCount + " 个独立TopoNet");

            } else {
                // === 传统模式: 逐个处理TopoNet ===
                System.out.println("使用传统独立TopoNet模式验证");
                for (Map.Entry<String, TopoNet> entry : topoNetMap.entrySet()) {
                    processStandaloneTopoNet(entry.getValue(), sharedQueueBDD);
                }
            }

        } catch (Exception e) {
            System.err.println("验证过程异常: " + e.getMessage());
            e.printStackTrace();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("Verification completed in " + elapsed + "ms");

        // 打印NP-BDD统计
        if (BDDEngine.isNPBDDEnabled()) {
            printNPBDDStats();
        }

        // 打印内存使用
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double usedMemoryMB = usedMemory / (1024.0 * 1024.0);
        System.out.printf("Memory usage (verification): %.6f M%n", usedMemoryMB);
    }


    // 预加载核心设备 - 直接加载转发表
    private void preloadCoreDevices() {
        System.out.println("预加载核心设备转发表...");

        Set<String> coreDevices = new HashSet<>();

        // 1. 添加所有边缘设备
        if (network != null && network.edgeDevices != null) {
            coreDevices.addAll(network.edgeDevices);
        }

        // 2. 添加所有目标设备
        if (network != null && network.dstDevices != null) {
            coreDevices.addAll(network.dstDevices);
        }

        // 3. 串行加载转发表
        if (!coreDevices.isEmpty()) {
            System.out.println("加载 " + coreDevices.size() + " 个核心设备转发表");

            int loadedCount = 0;
            for (String deviceName : coreDevices) {
                try {
                    loadDeviceForwardingTable(deviceName);
                    loadedCount++;

                    if (loadedCount % 100 == 0) {
                        System.out.println("已预加载 " + loadedCount + "/" + coreDevices.size() + " 个设备");
                    }
                } catch (Exception e) {
                    System.err.println("预加载设备失败 " + deviceName + ": " +
                            (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }

            System.out.println("核心设备转发表预加载完成，成功缓存 " +
                    deviceForwardingTable.size() + " 个IPv4表和 " +
                    deviceForwardingTableIPV6.size() + " 个IPv6表");
        }
    }

    // 直接从规则文件加载设备转发表
    private void loadDeviceForwardingTable(String deviceName) {
        try {
            // 防止重复加载
            if (deviceForwardingTable.containsKey(deviceName) || deviceForwardingTableIPV6.containsKey(deviceName)) {
                return;
            }

            String rulesFile = getDeviceRuleFile(deviceName);
            if (rulesFile == null) {
                return;
            }

            File file = new File(rulesFile);
            if (!file.exists() || file.length() == 0) {
                return;
            }

            List<ForwardingEntry> forwardingTable = new ArrayList<>();
            List<ForwardingEntryIPV6> forwardingTableIPV6 = new ArrayList<>();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 32768)) {

                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        String[] tokens = line.split("\\s+");
                        if (tokens.length >= 4 &&
                                (tokens[0].equals("fw") || tokens[0].equals("ALL") ||
                                        tokens[0].equals("ANY") || tokens[0].equals("any"))) {

                            Set<String> ports = new HashSet<>();
                            for (int i = 3; i < tokens.length; i++) {
                                if (tokens[i] != null && !tokens[i].trim().isEmpty()) {
                                    ports.add(tokens[i]);
                                }
                            }

                            if (!ports.isEmpty()) {
                                ForwardType ft = tokens[0].equals("ANY") || tokens[0].equals("any") ?
                                        ForwardType.ANY : ForwardType.ALL;

                                if (!(isIpv6 || isIpv4withS)) {
                                    // IPv4
                                    try {
                                        long ip = Long.parseLong(tokens[1]);
                                        int prefix = Integer.parseInt(tokens[2]);
                                        ForwardingEntry entry = new ForwardingEntry(ip, prefix, ports, ft);
                                        forwardingTable.add(entry);
                                    } catch (NumberFormatException nfe) {
                                        System.err.println("IPv4规则格式错误，跳过: " + line);
                                    }
                                } else {
                                    // IPv6
                                    String ip = tokens[1];
                                    int prefix = Integer.parseInt(tokens[2]);
                                    ForwardingEntryIPV6 entry = new ForwardingEntryIPV6(ip, prefix, ports, ft);
                                    forwardingTableIPV6.add(entry);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 跳过无效规则
                    }
                }
            }

            // 按前缀长度排序（最长前缀优先，用于LPM）
            if (!(isIpv6 || isIpv4withS)) {
                forwardingTable.sort((ForwardingEntry a, ForwardingEntry b) -> Integer.compare(b.prefixLen, a.prefixLen));
                if (!forwardingTable.isEmpty()) {
                    deviceForwardingTable.put(deviceName, forwardingTable);
                    System.out.println("为设备 " + deviceName + " 加载了 " + forwardingTable.size() + " 条IPv4转发条目");
                    // 显示前几条转发条目的详细信息（保持原始格式）
                    int showCount = Math.min(3, forwardingTable.size());
                    for (int i = 0; i < showCount; i++) {
                        ForwardingEntry entry = forwardingTable.get(i);
                        System.out.println("  条目" + (i + 1) + ": " + entry.getOriginalIPString() +
                                " -> 端口" + entry.ports);
                    }
                    if (forwardingTable.size() > 3) {
                        System.out.println("  ... 以及其他 " + (forwardingTable.size() - 3) + " 条条目");
                    }
                }
            } else {
                forwardingTableIPV6.sort((ForwardingEntryIPV6 a, ForwardingEntryIPV6 b) -> Integer.compare(b.prefixLen, a.prefixLen));
                if (!forwardingTableIPV6.isEmpty()) {
                    deviceForwardingTableIPV6.put(deviceName, forwardingTableIPV6);
                    System.out.println("为设备 " + deviceName + " 加载了 " + forwardingTableIPV6.size() + " 条IPv6转发条目");
                    // 显示前几条转发条目的详细信息
                    int showCount = Math.min(3, forwardingTableIPV6.size());
                    for (int i = 0; i < showCount; i++) {
                        ForwardingEntryIPV6 entry = forwardingTableIPV6.get(i);
                        System.out.println("  条目" + (i + 1) + ": " + entry.ip + "/" + entry.prefixLen +
                                " -> 端口" + entry.ports);
                    }
                    if (forwardingTableIPV6.size() > 3) {
                        System.out.println("  ... 以及其他 " + (forwardingTableIPV6.size() - 3) + " 条条目");
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("为设备 " + deviceName + " 加载转发表失败: " + e.getMessage());
        }
    }

    // 获取设备规则文件路径
    private String getDeviceRuleFile(String deviceName) {
        try {
            Configuration config = Configuration.getConfiguration();

            try {
                String configFile = config.getDeviceRuleFile(deviceName);
                if (configFile != null) {
                    return configFile;
                }
            } catch (Exception e) {
                // 方法不存在，尝试备用方案
            }

            // 备用方案：构造默认规则文件路径
            String baseDir = "rules/";

            try {
                String workDir = System.getProperty("user.dir");
                if (workDir != null) {
                    baseDir = workDir + "/rules/";
                }
            } catch (Exception ex) {
                // 使用默认值
            }

            return baseDir + deviceName + ".rules";

        } catch (Exception e) {
            System.err.println("获取规则文件路径失败，使用默认路径: " + e.getMessage());
            return "rules/" + deviceName + ".rules";
        }
    }

    // 加载单个设备转发表
    private boolean loadSingleDeviceForwardingTable(String deviceName) {
        try {
            // 防止重复加载
            if (loadedDevices.contains(deviceName)) {
                return true;
            }

            Object deviceLock = deviceLocks.computeIfAbsent(deviceName, k -> new Object());

            synchronized (deviceLock) {
                // 双重检查
                if (loadedDevices.contains(deviceName)) {
                    return true;
                }

                // 加载转发表
                loadDeviceForwardingTable(deviceName);

                // 标记为已加载
                loadedDevices.add(deviceName);
                return true;
            }

        } catch (Exception e) {
            System.err.println("为设备 " + deviceName + " 加载转发表失败: " +
                    (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return false;
        }
    }

    // 处理单个TopoNet
    private void processTopoNet(TopoNet topoNet, LinkedBlockingDeque<BDDEngine> sharedQueueBDD) {
        try {
            // 1. 构建TopoNet基础结构（最小BDD使用）
            boolean reused = topoNet.getAndSetBddEngine(sharedQueueBDD);
            topoGenNode(topoNet);
            topoNetDeepCopyBdd(topoNet, reused);

            // 2. 获取源节点列表
            List<Node> srcNodes = new ArrayList<>();
            if (topoNet.srcNodes != null) {
                srcNodes.addAll(topoNet.srcNodes);
            }

            if (srcNodes.isEmpty()) {
                System.out.println("  无源节点，跳过");
                return;
            }

            System.out.println("  源节点数量: " + srcNodes.size());
            System.out.println("  目标设备: " + topoNet.dstDevice.name);

            // 3. 批处理源节点
            processSrcNodesBatched(topoNet, srcNodes);

        } catch (Exception e) {
            System.err.println("处理TopoNet失败 " + topoNet.dstDevice.name + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 批处理源节点
    private void processSrcNodesBatched(TopoNet topoNet, List<Node> srcNodes) {
        // 将源节点分批
        List<List<Node>> batches = new ArrayList<>();
        for (int i = 0; i < srcNodes.size(); i += srcNodeBatchSize) {
            int end = Math.min(i + srcNodeBatchSize, srcNodes.size());
            batches.add(new ArrayList<>(srcNodes.subList(i, end)));
        }

        System.out.println("  源节点分为 " + batches.size() + " 批处理");

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<Node> srcNodeBatch = batches.get(batchIndex);
            System.out.println("    处理源节点批次 " + (batchIndex + 1) + "/" + batches.size() +
                    " (包含 " + srcNodeBatch.size() + " 个源节点)");

            // 处理这批源节点
            processSrcNodeBatch(topoNet, srcNodeBatch);
        }
    }

    // 处理一批源节点
    private void processSrcNodeBatch(TopoNet topoNet, List<Node> srcNodeBatch) {
        for (Node srcNode : srcNodeBatch) {
            String srcDeviceName = srcNode.device.name;
            String dstDeviceName = topoNet.dstDevice.name;

            try {
                VerificationResult result = verifySrcNodeWithTimeout(srcNode, topoNet.getDstNode(), topoNet);

                // 无论成功失败都写入结果
                writeResult(srcDeviceName, dstDeviceName, result);
                flushResults();

            } catch (Exception e) {
                System.err.println("      验证异常: " + srcDeviceName + " -> " + dstDeviceName + ": " + e.getMessage());
                // 异常情况写入false
                writeResult(srcDeviceName, dstDeviceName, null);
                flushResults();
            }

            // 简单内存清理
            if (srcNodeBatch.indexOf(srcNode) % 50 == 49) {
                System.gc();
            }
        }
    }

    // 带超时的源节点验证
    private VerificationResult verifySrcNodeWithTimeout(Node srcNode, Node dstNode, TopoNet topoNet) {
        String srcDeviceName = srcNode.device.name;
        String dstDeviceName = dstNode.device.name;

        ExecutorService pathVerifyExecutor = Executors.newCachedThreadPool();
        try {
            Future<VerificationResult> future = pathVerifyExecutor.submit(() ->
                    verifySrcNodeByPathsParallel(srcNode, dstNode, topoNet));

            VerificationResult result = future.get(verificationTimeoutMillis, TimeUnit.MILLISECONDS);
            return result;

        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            pathVerifyExecutor.shutdownNow();
        }
    }

    // 使用直接转发表查找的基于路径的并行验证
    private VerificationResult verifySrcNodeByPathsParallel(Node srcNode, Node dstNode, TopoNet topoNet) {
        long startTime = System.currentTimeMillis();

        try {
            // 输入验证
            if (srcNode == null || dstNode == null || topoNet == null) {
                return new VerificationResult(false, null, null, null);
            }

            if (srcNode.device == null || dstNode.device == null) {
                return new VerificationResult(false, null, null, null);
            }

            // 1. 计算可能的物理路径
            List<List<String>> possiblePaths = calculatePossiblePaths(
                    srcNode.device.name, dstNode.device.name, maxPathsPerSrcNode);

            if (possiblePaths.isEmpty()) {
                return new VerificationResult(false, null, null, null);
            }

            // 2. 使用并发验证，一旦找到可达路径立即返回
            ExecutorService pathExecutor = Executors.newFixedThreadPool(Math.min(possiblePaths.size(), 10));
            CompletionService<VerificationResult> completionService = new ExecutorCompletionService<>(pathExecutor);

            try {
                // 提交所有路径验证任务
                for (int pathIndex = 0; pathIndex < possiblePaths.size(); pathIndex++) {
                    final int index = pathIndex;
                    final List<String> path = possiblePaths.get(pathIndex);

                    completionService.submit(() -> {
                        try {
                            return verifyPathByForwardingTable(index + 1, path);
                        } catch (Exception e) {
                            return new VerificationResult(false, null, null, null);
                        }
                    });
                }

                // 等待任务完成，一旦有成功的立即返回
                int completedTasks = 0;
                while (completedTasks < possiblePaths.size()) {
                    try {
                        Future<VerificationResult> future = completionService.poll(100, TimeUnit.MILLISECONDS);
                        if (future != null) {
                            completedTasks++;
                            VerificationResult result = future.get();

                            if (result != null && result.reachable) {
                                return result;
                            }
                        }

                        // 检查超时
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - startTime > verificationTimeoutMillis) {
                            return new VerificationResult(false, null, null, null);
                        }

                    } catch (Exception e) {
                        completedTasks++;
                    }
                }

                return new VerificationResult(false, null, null, null);

            } catch (Exception e) {
                return new VerificationResult(false, null, null, null);
            } finally {
                pathExecutor.shutdownNow();
            }
        } catch (Exception e) {
            return new VerificationResult(false, null, null, null);
        }
    }

    // 使用正确的交集逻辑验证路径
    private VerificationResult verifyPathByForwardingTable(int pathIndex, List<String> path) {
        try {
            // 输入验证
            if (path == null || path.isEmpty()) {
                return new VerificationResult(false, null, null, null);
            }

            if (path.size() < 2) {
                // 单节点路径，直接可达
                return new VerificationResult(true, new ArrayList<>(path), "direct", "direct");
            }

            // 1. 为路径中所有设备加载转发表
            if (!loadForwardingTablesForPath(path)) {
                return new VerificationResult(false, null, null, null);
            }

            // 2. 使用交集方法验证路径可达性
            Set<String> reachableNetworks = calculatePathReachableNetworks(path, pathIndex);

            if (reachableNetworks.isEmpty()) {
                return new VerificationResult(false, null, null, null);
            }

            // 3. 选择第一个可达网段作为结果
            String firstReachableNetwork = reachableNetworks.iterator().next();
            return new VerificationResult(true, new ArrayList<>(path), firstReachableNetwork, firstReachableNetwork);

        } catch (Exception e) {
            return new VerificationResult(false, null, null, null);
        }
    }

    // 计算路径的可达网段（使用交集方法）
    private Set<String> calculatePathReachableNetworks(List<String> path, int pathIndex) {
        try {
            // 初始化：第一跳的所有可达网段
            Set<String> currentReachableNetworks = null;

            // 逐跳计算交集
            for (int i = 0; i < path.size() - 1; i++) {
                String currentDevice = path.get(i);
                String nextDevice = path.get(i + 1);

                // 获取当前设备到下一设备的可达网段
                Set<String> hopReachableNetworks = getNetworksReachableToDevice(currentDevice, nextDevice);

                if (hopReachableNetworks.isEmpty()) {
                    return new HashSet<>(); // 空集合，路径不可达
                }

                if (currentReachableNetworks == null) {
                    // 第一跳，直接使用
                    currentReachableNetworks = new HashSet<>(hopReachableNetworks);
                } else {
                    // 后续跳，计算交集
                    currentReachableNetworks.retainAll(hopReachableNetworks);

                    if (currentReachableNetworks.isEmpty()) {
                        return new HashSet<>();
                    }
                }
            }

            return currentReachableNetworks != null ? currentReachableNetworks : new HashSet<>();

        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    // 获取从当前设备可以到达指定下一设备的所有网段
    private Set<String> getNetworksReachableToDevice(String currentDevice, String nextDevice) {
        Set<String> reachableNetworks = new HashSet<>();

        try {
            // 获取当前设备的转发表
            List<ForwardingEntry> forwardingTable = deviceForwardingTable.get(currentDevice);
            if (forwardingTable == null || forwardingTable.isEmpty()) {
                return reachableNetworks;
            }

            // 遍历转发表，找出所有能到达下一设备的网段
            for (ForwardingEntry entry : forwardingTable) {
                if (canForwardToNextDevice(currentDevice, entry, nextDevice)) {
                    String networkStr = entry.getOriginalIPString();
                    reachableNetworks.add(networkStr);
                }
            }

        } catch (Exception e) {
            // 静默处理异常
        }

        return reachableNetworks;
    }

    // 在转发表中查找最长前缀匹配
    private ForwardingEntry findLongestPrefixMatch(List<ForwardingEntry> forwardingTable, Long dstIP) {
        // 表已按前缀长度排序（最长优先）
        for (ForwardingEntry entry : forwardingTable) {
            if (entry.matches(dstIP)) {
                return entry;
            }
        }
        return null; // 未找到匹配
    }

    // 检查转发条目是否能转发到下一设备
    private boolean canForwardToNextDevice(String currentDevice, ForwardingEntry entry, String nextDevice) {
        try {
            // 检查条目中的任何端口是否能到达下一设备
            for (String port : entry.ports) {
                if (isConnectedToDevice(currentDevice, port, nextDevice)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // 检查当前设备上的端口是否连接到下一设备
    private boolean isConnectedToDevice(String currentDevice, String port, String nextDevice) {
        try {
            // 1. 检查拓扑连接
            if (Node.topology != null) {
                DevicePort srcPort = new DevicePort(currentDevice, port);
                DevicePort dstPort = Node.topology.get(srcPort);
                if (dstPort != null && dstPort.deviceName != null && dstPort.deviceName.equals(nextDevice)) {
                    return true;
                }
            }

            // 2. 启发式检查 - 端口名包含设备名或名之
            if (port.contains(nextDevice) || nextDevice.contains(port)) {
                return true;
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // 为路径中所有设备加载转发表
    private boolean loadForwardingTablesForPath(List<String> path) {
        try {
            for (String deviceName : path) {
                if (deviceName != null && !deviceName.trim().isEmpty()) {
                    if (!loadSingleDeviceForwardingTable(deviceName)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // 计算可能的物理路径
    private List<List<String>> calculatePossiblePaths(String srcDevice, String dstDevice, int maxPaths) {
        List<List<String>> paths = new ArrayList<>();

        try {
            // 输入验证
            if (srcDevice == null || dstDevice == null) {
                return paths;
            }

            if (srcDevice.equals(dstDevice)) {
                // 源和目标相同，直接路径
                List<String> directPath = new ArrayList<>();
                directPath.add(srcDevice);
                paths.add(directPath);
                return paths;
            }

            // 使用BFS查找多条可能路径
            Queue<List<String>> queue = new LinkedList<>();
            Set<String> allVisited = new HashSet<>();

            // 初始路径
            List<String> initialPath = new ArrayList<>();
            initialPath.add(srcDevice);
            queue.add(initialPath);

            int processedPaths = 0;
            final int maxProcessedPaths = 2000;

            while (!queue.isEmpty() && paths.size() < maxPaths && processedPaths < maxProcessedPaths) {
                List<String> currentPath = queue.poll();
                if (currentPath == null || currentPath.isEmpty()) {
                    continue;
                }

                processedPaths++;

                String currentDevice = currentPath.get(currentPath.size() - 1);
                if (currentDevice == null) {
                    continue;
                }

                if (currentDevice.equals(dstDevice)) {
                    paths.add(new ArrayList<>(currentPath));
                    continue;
                }

                // 限制路径长度为10跳
                if (currentPath.size() >= 10) {
                    continue;
                }

                // 查找邻居设备
                Set<String> neighbors = findNeighborDevices(currentDevice);
                if (neighbors != null) {
                    for (String neighbor : neighbors) {
                        if (neighbor != null && !currentPath.contains(neighbor)) {
                            List<String> newPath = new ArrayList<>(currentPath);
                            newPath.add(neighbor);

                            String pathKey = String.join("->", newPath);
                            if (!allVisited.contains(pathKey)) {
                                allVisited.add(pathKey);
                                queue.add(newPath);
                            }
                        }
                    }
                }
            }

            // 按路径长度排序，优先验证短路径
            paths.sort(Comparator.comparingInt(List::size));

        } catch (Exception e) {
            // 静默处理异常
        }

        return paths;
    }

    // 查找设备的邻居设备
    private Set<String> findNeighborDevices(String deviceName) {
        Set<String> neighbors = new HashSet<>();

        try {
            if (deviceName == null || deviceName.trim().isEmpty()) {
                return neighbors;
            }

            // 基于拓扑连接信息查找
            if (Node.topology != null) {
                for (Map.Entry<DevicePort, DevicePort> entry : Node.topology.entrySet()) {
                    if (entry == null) continue;

                    DevicePort srcPort = entry.getKey();
                    DevicePort dstPort = entry.getValue();

                    if (srcPort != null && srcPort.deviceName != null && srcPort.deviceName.equals(deviceName)) {
                        if (dstPort != null && dstPort.deviceName != null) {
                            neighbors.add(dstPort.deviceName);
                        }
                    } else if (dstPort != null && dstPort.deviceName != null && dstPort.deviceName.equals(deviceName)) {
                        if (srcPort != null && srcPort.deviceName != null) {
                            neighbors.add(srcPort.deviceName);
                        }
                    }
                }
            }

            // 如果拓扑信息不完整，使用设备端口信息作为备用
            if (neighbors.isEmpty() && network != null && network.devicePorts != null && network.devicePorts.containsKey(deviceName)) {
                if (network.edgeDevices != null) {
                    for (String edgeDevice : network.edgeDevices) {
                        if (edgeDevice != null && !edgeDevice.equals(deviceName)) {
                            neighbors.add(edgeDevice);
                        }
                    }
                }
                if (network.dstDevices != null) {
                    for (String dstDevice : network.dstDevices) {
                        if (dstDevice != null && !dstDevice.equals(deviceName)) {
                            neighbors.add(dstDevice);
                        }
                    }
                }
            }

        } catch (Exception e) {
            // 静默处理异常
        }

        return neighbors;
    }

    // 写入验证结果（只写入 true/false）
    private synchronized void writeResult(String srcDevice, String dstDevice, VerificationResult result) {
        boolean reachable = (result != null && result.reachable);

        // 格式：源设备 目标设备 true/false
        String resultLine = srcDevice + " " + dstDevice + " " + reachable;

        try {
            if (resultWriter != null) {
                resultWriter.write(resultLine);
                resultWriter.newLine();
                resultWriter.flush();
            }

            System.out.println("    [结果] " + resultLine);

        } catch (IOException e) {
            System.err.println("写入结果失败: " + e.getMessage());
            e.printStackTrace();
        }

        if (resultWriter == null) {
            System.out.println("    [结果-控制台] " + resultLine);
        }
    }

    // 强制刷新结果文件
    private synchronized void flushResults() {
        try {
            if (resultWriter != null) {
                resultWriter.flush();
            }
        } catch (IOException e) {
            System.err.println("刷新结果文件失败: " + e.getMessage());
        }
    }

    // 安全获取IPPrefix的IP地址
    private long getIPFromPrefix(IPPrefix prefix) {
        try {
            // 使用反射安全访问IP字段
            try {
                java.lang.reflect.Field ipField = prefix.getClass().getField("ip");
                return (Long) ipField.get(prefix);
            } catch (Exception e1) {
                try {
                    java.lang.reflect.Method getIpMethod = prefix.getClass().getMethod("getIp");
                    return (Long) getIpMethod.invoke(prefix);
                } catch (Exception e2) {
                    return 0L;
                }
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    // 安全获取IPPrefix的前缀长度
    private int getPrefixLenFromPrefix(IPPrefix prefix) {
        try {
            // 使用反射安全访问前缀长度字段
            try {
                java.lang.reflect.Field prefixLenField = prefix.getClass().getField("prefixLen");
                return (Integer) prefixLenField.get(prefix);
            } catch (Exception e1) {
                try {
                    java.lang.reflect.Field prefixLengthField = prefix.getClass().getField("prefixLength");
                    return (Integer) prefixLengthField.get(prefix);
                } catch (Exception e2) {
                    try {
                        java.lang.reflect.Method getPrefixLenMethod = prefix.getClass().getMethod("getPrefixLen");
                        return (Integer) getPrefixLenMethod.invoke(prefix);
                    } catch (Exception e3) {
                        try {
                            java.lang.reflect.Method getPrefixLengthMethod = prefix.getClass().getMethod("getPrefixLength");
                            return (Integer) getPrefixLengthMethod.invoke(prefix);
                        } catch (Exception e4) {
                            return 24; // 默认前缀长度
                        }
                    }
                }
            }
        } catch (Exception e) {
            return 24; // 默认前缀长度
        }
    }

    // 安全获取IPPrefixIPV6的IP地址
    private String getIPFromPrefixIPV6(IPPrefixIPV6 prefix) {
        try {
            // 使用反射安全访问IP字段
            try {
                java.lang.reflect.Field ipField = prefix.getClass().getField("ip");
                return (String) ipField.get(prefix);
            } catch (Exception e1) {
                try {
                    java.lang.reflect.Method getIpMethod = prefix.getClass().getMethod("getIp");
                    return (String) getIpMethod.invoke(prefix);
                } catch (Exception e2) {
                    return "::";
                }
            }
        } catch (Exception e) {
            return "::";
        }
    }

    // 安全获取IPPrefixIPV6的前缀长度
    private int getPrefixLenFromPrefixIPV6(IPPrefixIPV6 prefix) {
        try {
            // 使用反射安全访问前缀长度字段
            try {
                java.lang.reflect.Field prefixLenField = prefix.getClass().getField("prefixLen");
                return (Integer) prefixLenField.get(prefix);
            } catch (Exception e1) {
                try {
                    java.lang.reflect.Field prefixLengthField = prefix.getClass().getField("prefixLength");
                    return (Integer) prefixLengthField.get(prefix);
                } catch (Exception e2) {
                    try {
                        java.lang.reflect.Method getPrefixLenMethod = prefix.getClass().getMethod("getPrefixLen");
                        return (Integer) getPrefixLenMethod.invoke(prefix);
                    } catch (Exception e3) {
                        try {
                            java.lang.reflect.Method getPrefixLengthMethod = prefix.getClass().getMethod("getPrefixLength");
                            return (Integer) getPrefixLengthMethod.invoke(prefix);
                        } catch (Exception e4) {
                            return 64; // 默认IPv6前缀长度
                        }
                    }
                }
            }
        } catch (Exception e) {
            return 64; // 默认IPv6前缀长度
        }
    }

    private void genTopoNet() {
        topoNetMap = new HashMap<>();
        int topoCnt = -1;

        TopoNet.network = this.network;

        // === Step 1: 为所有dstDevices创建TopoNet ===
        for (Device dstDevice : TopoNet.edgeDevices) {
            if (!network.dstDevices.contains(dstDevice.name)) continue;

            TopoNet topoNet = new TopoNet(dstDevice, topoCnt);
            topoNet.setInvariant(dstDevice.name, "exist >= 1", "*");
            topoNetMap.put(dstDevice.name, topoNet);
            topoCnt--;
        }

        // ===== 关键修复4: 确保 devicePortsTopo 被正确初始化 =====
        if (network != null && network.devicePorts != null) {
            System.out.println("[TopoRunner] 初始化 TopoNet.devicePortsTopo...");
            TopoNet.transformDevicePorts(network.devicePorts);

            if (TopoNet.devicePortsTopo != null) {
                System.out.println("[TopoRunner] TopoNet.devicePortsTopo 初始化成功，包含 "
                    + TopoNet.devicePortsTopo.size() + " 个设备");
            } else {
                System.err.println("[TopoRunner] 严重错误：TopoNet.devicePortsTopo 初始化失败！");
            }
        } else {
            System.err.println("[TopoRunner] 警告：network 或 network.devicePorts 为 null");
        }

        // ===== 关键修复5: 初始化 Node.nextTable =====
        try {
            System.out.println("[TopoRunner] 初始化 Node.nextTable...");
            TopoNet.setNextTable();
            System.out.println("[TopoRunner] Node.nextTable 初始化完成");
        } catch (Exception e) {
            System.err.println("[TopoRunner] 初始化 Node.nextTable 失败: " + e.getMessage());
            e.printStackTrace();
        }
        // ===== 修复4和5结束 =====

        // === Step 2: NP-Net邻域构建 ===
        if (enableNPNet && topoNetMap.size() > 1) {
            try {
                neighborhoodBuilder = new NeighborhoodBuilder(devices, network, TopoNet.edgeDevices);
                neighborhoods = neighborhoodBuilder.buildNeighborhoods();

                // 将TopoNet分配给对应的Neighborhood
                for (Neighborhood nh : neighborhoods) {
                    Device firstDst = nh.getDstDevices().iterator().next();
                    TopoNet repTopoNet = topoNetMap.get(firstDst.name);
                    if (repTopoNet != null) {
                        nh.setRepresentativeTopoNet(repTopoNet);
                    }

                    for (Device d : nh.getDstDevices()) {
                        TopoNet tn = topoNetMap.get(d.name);
                        if (tn != null) {
                            nh.putDeviceTopoNet(d.name, tn);
                        }
                    }
                }

                System.out.println("[NP-Net] 邻域构建完成: " + neighborhoods.size() + " 个邻域");
            } catch (Exception e) {
                System.err.println("[NP-Net] 邻域构建失败, 回退到独立TopoNet模式: " + e.getMessage());
                neighborhoods = new ArrayList<>();
                enableNPNet = false;
            }
        }

        System.out.println("TopoNet总数量: " + topoNetMap.size());
    }



    // ==================================================================================
    // topoGenNode() — 为TopoNet中的所有设备创建Node并设置BDD引擎
    // ==================================================================================
    private void topoGenNode(TopoNet topoNet) {
        for (Device device : devices.values()) {
            try {
                Node node = new Node(device, topoNet);

                // 确保设置BDD引擎
                if (topoNet.bddEngine != null) {
                    node.setBdd(topoNet.bddEngine);
                } else {
                    System.err.println("警告：TopoNet [" + topoNet.dstDevice.name + "] 的BDD引擎为空，Node [" + device.name + "] 可能无法正常工作");
                }

                topoNet.nodesTable.put(device.name, node);
                if (TopoNet.edgeDevices.contains(device)) {
                    if (device == topoNet.dstDevice) { // 终结点
                        topoNet.setDstNode(node);
                        node.isDestination = true;
                    } else { // 边缘结点
                        topoNet.srcNodes.add(node);
                    }
                }
            } catch (Exception e) {
                System.err.println("创建节点 [" + device.name + "] 时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void topoNetDeepCopyBdd(TopoNet topoNet, boolean reused) {
        String dstDevice = topoNet.dstDevice.name;
        Integer s = getDevicePacketSpace(dstDevice);
        if (s != null) {
            if (!reused) {
                try {
                    topoNet.copyBdd(srcBdd, "Reflect");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else topoNet.setNodeBdd();
            topoNet.deviceLecs = Device.globalLecs;

            // 设置TopoNet的包空间字段
            try {
                topoNet.packetSpace = s;
            } catch (Exception e) {
                System.err.println("无法设置TopoNet包空间: " + e.getMessage());
            }
        }
    }

    // 获取设备包空间（最小BDD使用）
    private Integer getDevicePacketSpace(String deviceName) {
        try {
            if (srcNet != null && srcNet.devicePacketSpace != null) {
                return srcNet.devicePacketSpace.get(deviceName);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void awaitFinished() {
        threadPool.awaitAllTaskFinished(100);
    }

    @Override
    public void sendCount(Context ctx, DevicePort sendPort, BDDEngine bddEngine) {

    }

    public long getInitTime() {
        return 0;
    }

    @Override
    public void close() {
        try {
            if (resultWriter != null) {
                resultWriter.close();
            }
        } catch (IOException e) {
            System.err.println("关闭结果写入器失败: " + e.getMessage());
        }

        // 清理转发表缓存
        try {
            loadedDevices.clear();
            deviceLocks.clear();
            deviceForwardingTable.clear();
            deviceForwardingTableIPV6.clear();
            System.out.println("已清理所有转发表缓存和状态");
        } catch (Exception e) {
            System.err.println("清理缓存失败: " + e.getMessage());
        }

        // ========== 新增：清理NP-BDD资源 ==========
        if (enableNPBDD) {
            try {
                System.out.println("[TopoRunner] 清理NP-BDD资源...");
                BDDPredicateRegistry.getInstance().clear();
                BDDPredicateCache.getInstance().clear();
                System.out.println("[TopoRunner] NP-BDD资源已清理");
            } catch (Exception e) {
                System.err.println("[TopoRunner] 清理NP-BDD资源失败: " + e.getMessage());
            }
        }
        // ========== 新增结束 ==========

        devices.values().forEach(Device::close);
        threadPool.shutdownNow();
    }

    /**
     * 简化版统计打印（只打印关键指标）
     */
    private void printSimpleNPBDDStats() {
        BDDPredicateRegistry registry = BDDPredicateRegistry.getInstance();
        BDDPredicateCache cache = BDDPredicateCache.getInstance();

        BDDPredicateRegistry.RegistryStats regStats = registry.getStats();
        BDDPredicateCache.CacheStats cacheStats = cache.getStats();

        System.out.println("\n[NP-BDD] 性能摘要:");
        System.out.println("  谓词数: " + regStats.totalPredicates +
                " | L3命中率: " + String.format("%.1f%%", cacheStats.getL3HitRate()) +
                " | L2命中率: " + String.format("%.1f%%", cacheStats.getL2HitRate()) +
                " | L1命中率: " + String.format("%.1f%%", cacheStats.getL1HitRate()));
    }

    // ==================================================================================
    // 【新增1】processNeighborhood() — 处理单个邻域的NP-Net验证
    // ==================================================================================
    private void processNeighborhood(Neighborhood neighborhood, LinkedBlockingDeque<BDDEngine> sharedQueueBDD) {
        try {
            // Step 1: 为代表TopoNet初始化
            TopoNet repTopoNet = neighborhood.getRepresentativeTopoNet();
            if (repTopoNet == null) {
                System.err.println("[NP-Net] 邻域 " + neighborhood.getName() + " 无代表TopoNet, 跳过");
                return;
            }

            boolean reused = repTopoNet.getAndSetBddEngine(sharedQueueBDD);
            topoGenNode(repTopoNet);
            topoNetDeepCopyBdd(repTopoNet, reused);
            repTopoNet.nodeCalIndegree();

            // Step 2: 执行内部遍历 (在Inner Area内传播)
            neighborhood.executeInnerTraversal();

            // Step 3: 对每个dstDevice执行外部遍历
            for (Map.Entry<String, TopoNet> entry : neighborhood.getDeviceTopoNets().entrySet()) {
                String deviceName = entry.getKey();
                TopoNet topoNet = entry.getValue();

                if (topoNet == repTopoNet) {
                    // 代表TopoNet已经完成完整遍历, 直接收集结果
                    collectResults(topoNet);
                } else {
                    // 其他TopoNet: 初始化 + 从Entrance注入聚合空间 + 外部遍历
                    boolean reused2 = topoNet.getAndSetBddEngine(sharedQueueBDD);
                    topoGenNode(topoNet);
                    topoNetDeepCopyBdd(topoNet, reused2);
                    topoNet.nodeCalIndegree();

                    // 从Entrance注入聚合空间, 执行外部遍历
                    neighborhood.executeExternalTraversal(deviceName, topoNet);

                    // 收集结果
                    collectResults(topoNet);

                    // 归还BDD引擎
                    if (topoNet.bddEngine != null) {
                        sharedQueueBDD.offer(topoNet.bddEngine);
                    }
                }
            }

            // 归还代表TopoNet的BDD引擎
            if (repTopoNet.bddEngine != null) {
                sharedQueueBDD.offer(repTopoNet.bddEngine);
            }

        } catch (Exception e) {
            System.err.println("[NP-Net] 处理邻域 " + neighborhood.getName() + " 失败: " + e.getMessage());
            e.printStackTrace();
            // 回退: 独立处理
            for (Map.Entry<String, TopoNet> entry : neighborhood.getDeviceTopoNets().entrySet()) {
                try {
                    processStandaloneTopoNet(entry.getValue(), sharedQueueBDD);
                } catch (Exception e2) {
                    System.err.println("回退处理也失败: " + e2.getMessage());
                }
            }
        }
    }


    // ==================================================================================
    // 【新增2】processStandaloneTopoNet() — 处理独立TopoNet (原processTopoNet逻辑)
    // ==================================================================================
    private void processStandaloneTopoNet(TopoNet topoNet, LinkedBlockingDeque<BDDEngine> sharedQueueBDD) {
        try {
            // 1. 构建TopoNet基础结构
            boolean reused = topoNet.getAndSetBddEngine(sharedQueueBDD);

            if (topoNet.bddEngine == null) {
                System.err.println("[修复] TopoNet [" + topoNet.dstDevice.name + "] BDD引擎为空，创建新引擎");
                topoNet.bddEngine = new BDDEngine();
                if (srcBdd != null) {
                    topoNet.bddEngine.copyFrom(srcBdd);
                }
            }

            topoGenNode(topoNet);
            topoNetDeepCopyBdd(topoNet, reused);

            // 2. 检查devicePortsTopo是否初始化
            if (TopoNet.devicePortsTopo == null) {
                System.err.println("[严重错误] TopoNet.devicePortsTopo 未初始化！");
                return;
            }

            // 3. 初始化节点（调用topoNetStart初始化locCib）
            topoNet.nodeCalIndegree();

            // ===== 关键修复：使用正确的验证方法 =====
            // 不使用bfsByIteration，而是使用startCountByTopo递归验证
            Node dstNode = topoNet.getDstNode();
            if (dstNode != null) {
                // 创建初始Context
                Context c = new Context();
                c.topoId = topoNet.topoCnt;
                c.setDeviceName(topoNet.dstDevice.name);

                // 使用visited集合追踪访问过的节点
                Set<String> visited = new HashSet<>();

                // 从目标节点开始递归验证（这会更新所有节点的locCib）
                dstNode.startCountByTopo(c, visited);

            } else {
                System.err.println("[错误] TopoNet [" + topoNet.dstDevice.name + "] 目标节点为空");
            }
            // ===== 修复结束 =====

            // 5. 收集结果
            collectResults(topoNet);

            // 6. 归还BDD引擎
            if (topoNet.bddEngine != null && reused) {
                sharedQueueBDD.offer(topoNet.bddEngine);
            }

        } catch (Exception e) {
            System.err.println("处理独立TopoNet失败 " +
                    (topoNet.dstDevice != null ? topoNet.dstDevice.name : "unknown") +
                    ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    // ==================================================================================
    // 【新增3】collectResults() — 收集TopoNet中所有源节点的验证结果
    // ==================================================================================
    private void collectResults(TopoNet topoNet) {
        if (topoNet.srcNodes == null) return;

        for (Node srcNode : topoNet.srcNodes) {
            try {
                srcNode.showResult();
            } catch (Exception e) {
                // 【修复】使用 srcNode.device.name 代替 srcNode.deviceName
                // 因为 Node.deviceName 是 package-private, 从 TopoRunner 跨包无法访问
                String nodeName = (srcNode.device != null) ? srcNode.device.name : "unknown";
                System.err.println("收集结果异常(" + nodeName + "): " + e.getMessage());
            }
        }
    }


    // ==================================================================================
    // 【新增4】printNPBDDStats() — 打印NP-BDD缓存统计
    // ==================================================================================
    private void printNPBDDStats() {
        System.out.println("\n========== NP-BDD 性能统计 ==========");

        try {
            BDDPredicateRegistry registry = BDDPredicateRegistry.getInstance();
            BDDPredicateRegistry.RegistryStats regStats = registry.getStats();
            System.out.println("谓词注册表: 总谓词=" + regStats.totalPredicates +
                    ", 查找命中=" + regStats.cacheHits +
                    ", 查找未命中=" + regStats.cacheMisses);
        } catch (Exception e) {
            System.out.println("谓词注册表统计获取失败: " + e.getMessage());
        }

        try {
            BDDPredicateCache cache = BDDPredicateCache.getInstance();
            BDDPredicateCache.CacheStats cacheStats = cache.getStats();
            System.out.println("L1缓存: 命中=" + cacheStats.l1Hits + ", 未命中=" + cacheStats.l1Misses);
            System.out.println("L2-Encode缓存: 命中=" + cacheStats.l2EncodeHits + ", 未命中=" + cacheStats.l2EncodeMisses);
            System.out.println("L2-Hit缓存: 命中=" + cacheStats.l2HitHits + ", 未命中=" + cacheStats.l2HitMisses);
            System.out.println("L2-Merge缓存: 命中=" + cacheStats.l2MergeHits + ", 未命中=" + cacheStats.l2MergeMisses);
            System.out.println("L3缓存: 命中=" + cacheStats.l3Hits + ", 未命中=" + cacheStats.l3Misses);
        } catch (Exception e) {
            System.out.println("缓存统计获取失败: " + e.getMessage());
        }

        try {
            if (enableNPNet && neighborhoods != null) {
                System.out.println("\n========== NP-Net 统计 ==========");
                System.out.println("邻域总数: " + neighborhoods.size());
                int totalAggregated = 0;
                for (Neighborhood n : neighborhoods) {
                    totalAggregated += n.getDstDevices().size();
                }
                System.out.println("聚合设备总数: " + totalAggregated);
                System.out.println("减少的TopoNet数: " + (totalAggregated - neighborhoods.size()));
            }
        } catch (Exception e) {
            System.out.println("NP-Net统计获取失败: " + e.getMessage());
        }

        System.out.println("====================================\n");
    }
}
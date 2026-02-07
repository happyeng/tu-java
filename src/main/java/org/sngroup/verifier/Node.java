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

package org.sngroup.verifier;

import org.sngroup.Configuration;
import org.sngroup.test.runner.Runner;
import org.sngroup.test.runner.TopoRunner;
import org.sngroup.util.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.FileWriter;
import java.io.File;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Arrays;
import java.util.LinkedHashSet;

public class Node {

    public static AtomicInteger numDpvnet = new AtomicInteger(1);

    public static Map<DevicePort, DevicePort> topology = new HashMap<>();

    public static Map<String, Device> devices = new Hashtable<>();

    public Device device;
    public int index;
    public static Map<String, HashSet<NodePointer>> nextTable = new HashMap<>();

    String deviceName;

    public TopoNet topoNet;

    public TSBDD bdd;

    public boolean hasResult;

    public CibMessage lastResult;

    Invariant invariant;

    // int match_num;

    protected Set<CibTuple> todoList;

    protected Vector<CibTuple> locCib;
    protected Map<String, List<CibTuple>> portToCib;

    public boolean isDestination = false;

    // 新增：标记是否为非验证节点（没有转发规则的节点）
    public boolean isNonVerificationNode = false;

    // 新增：验证结果缓存
    private Boolean verificationResult = null;

    // 新增：节点级别的锁，保护并发访问
    private final ReentrantReadWriteLock nodeLock = new ReentrantReadWriteLock();
    private final Object locCibLock = new Object();

    // ===== 文件写入功能相关变量 =====
    private static String resultFileName = "reachable_networks.txt";
    private static String networkName = "";
    private static String resultFilePath = null;
    private static final Object fileWriteLock = new Object();
    private static final Object outputLock = new Object();
    private static boolean isInitialized = false;
    private static boolean enableFileOutput = true;
    private static BufferedWriter fileWriter = null;

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public Node(Device device, int index, Invariant invariant, Runner runner) {
        this.device = device;
        this.index = index;
        this.invariant = invariant;
        this.deviceName = device.name;
        // 检查是否为非验证节点
        checkAndSetNonVerificationStatus();
        // ------------------------------------------------------------ 2
        // -------------------------------------------------------------------------//
        hasResult = false;
        todoList = new HashSet<>();
        locCib = new Vector<>();
        portToCib = new Hashtable<>();
        lastResult = null;
    }

    public Node(Device device, TopoNet topoNet) {
        this.device = device;
        this.topoNet = topoNet;
        this.invariant = topoNet.invariant;
        this.index = topoNet.topoCnt;
        this.deviceName = device.name;
        // 检查是否为非验证节点
        checkAndSetNonVerificationStatus();
        // ------------------------------------------------------------ 2
        // -------------------------------------------------------------------------//
        hasResult = false;
        todoList = new HashSet<>();
        locCib = new Vector<>();
        portToCib = new Hashtable<>();
        lastResult = null;
    }

    int getPacketSpace() {
        return topoNet.packetSpace;
    }

    public void setBdd(BDDEngine bddEngine) {
        this.bdd = bddEngine.getBDD();
    }

    public void setTopoNet(TopoNet topoNet) {
        this.topoNet = topoNet;
    }

    public void topoNetStart() {
        initializeCibByTopo();
        // match_num = Integer.parseInt(invariant.getMatch().split("\\s+")[2]);
    }

    public boolean checkIsSrcNode() {
        TopoNet topoNet = this.topoNet;
        return topoNet.srcNodes.contains(this);
    }

    public boolean updateLocCibByTopo(String from, Collection<Announcement> announcements) {
        synchronized (locCibLock) {
            boolean newResult = false;
            Queue<CibTuple> queue = new LinkedList<>(portToCib.get(from));
            if (queue.size() == 0) return true;

            boolean useCache = BDDEngine.isNPBDDEnabled();

            while (!queue.isEmpty()) {
                CibTuple cibTuple = queue.poll();

                for (Announcement announcement : announcements) {
                    // 【NP-BDD】使用带缓存的AND和REF操作
                    int intersection;
                    if (useCache) {
                        intersection = bdd.andWithCache(announcement.predicate, cibTuple.predicate);
                        // andWithCache返回的已是谓词ID，不需要额外ref
                    } else {
                        intersection = bdd.ref(bdd.and(announcement.predicate, cibTuple.predicate));
                    }

                    if (intersection != cibTuple.predicate) {
                        CibTuple newCibTuple = cibTuple.keepAndSplit(intersection, bdd);
                        addCib(newCibTuple);
                        if (!hasResult && todoList.contains(cibTuple))
                            todoList.add(newCibTuple);
                        queue.add(newCibTuple);
                        newResult = true;
                    }
                }
            }
            return true;
        }
    }



    protected void addCib(CibTuple cib) {
        synchronized (locCibLock) {
            locCib.add(cib);
            updateActionPortTable(cib);
        }
    }

    private void updateActionPortTable(CibTuple cib) {
        synchronized (locCibLock) {
            for (String port : cib.action.ports) {
                portToCib.putIfAbsent(port, new Vector<>());
                portToCib.get(port).add(cib);
                List<CibTuple> tmpPortCibs = portToCib.get(port);
                // System.out.println("节点 " + this.deviceName + "更新端口后: " + port + "port to cib 大小" + tmpPortCibs.size());
            }
        }
    }


    // 根据LEC和该节点的下一跳初始化LocCIB表
    public void initializeCibByTopo() {
        try {
            if (topoNet == null || topoNet.deviceLecs == null) {
                return;
            }

            HashSet<Lec> lecs = topoNet.getDeviceLecs(deviceName);
            if (lecs == null || lecs.isEmpty()) {
                return;
            }

            int cnt = 0;
            boolean useCache = BDDEngine.isNPBDDEnabled();

            for (Lec lec : lecs) {
                if (lec.predicate == 0) continue;
                try {
                    // 【NP-BDD】使用带缓存的AND操作计算LEC与PacketSpace的交集
                    int intersection;
                    if (useCache) {
                        intersection = bdd.andWithCache(lec.predicate, getPacketSpace());
                    } else {
                        intersection = bdd.and(lec.predicate, getPacketSpace());
                    }

                    if (intersection != 0) {
                        cnt += 1;
                        CibTuple cibTuple = new CibTuple(intersection, lec.forwardAction, 1);
                        addCib(cibTuple);
                        todoList.add(cibTuple);
                    }
                } catch (Exception e) {
                    // 静默处理
                }
            }

            if (cnt == 0) {
                // 设备没有有效的LEC条目
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 从LocCIB中导出CIBOut
    public Map<Count, Integer> getCibOut() {
        synchronized (locCibLock) {
            Map<Count, Integer> cibOut = new HashMap<>();
            boolean useCache = BDDEngine.isNPBDDEnabled();

            for (CibTuple cibTuple : locCib) {
                if (cibTuple.predicate == 0)
                    continue;
                if (cibOut.containsKey(cibTuple.count)) {
                    int pre = cibOut.get(cibTuple.count);
                    // 【NP-BDD】使用带缓存的OR操作合并谓词
                    if (useCache) {
                        pre = bdd.orToWithCache(pre, cibTuple.predicate);
                    } else {
                        pre = bdd.orTo(pre, cibTuple.predicate);
                    }
                    cibOut.put(cibTuple.count, pre);
                } else {
                    cibOut.put(cibTuple.count, cibTuple.predicate);
                }
            }
            return cibOut;
        }
    }


    // ---------------------------------------------------------
    // 接收+发送------------------------------------------------------

    public void bfsByIteration(Context c) {
        nodeLock.writeLock().lock();
        try {
            Announcement a = new Announcement(0, getPacketSpace(), Utility.getOneNumVector(1));
            Vector<Announcement> al = new Vector<>();
            al.add(a);
            CibMessage cibOut = new CibMessage(al, new ArrayList<>(), index);
            c.setCib(cibOut);
            c.setDeviceName(deviceName);
            // 记录访问路径
            Set<String> visited = new HashSet<>();
            Queue<Context> queue = new LinkedList<>(); // 使用队列替换栈
            queue.add(c);
            // int bfsCnt = 0;
            // int checkCount = 0;
            int bfsCnt = 0;
            int ctxCnt = 0;
            int checkCnt = 0;
            System.out.println("终结点开始验证: " + c.getDeviceName());

            while (!queue.isEmpty()) {
                bfsCnt++;
                int size = queue.size();
                // System.out.println("当前层数中的节点" + size);
                for (int i = 0; i < size; i++) {
                    Context currentCtx = queue.poll(); // 出队列
                    String curDeviceName = currentCtx.getDeviceName();
                    visited.add(curDeviceName);
                    // System.out.println("BFS层次: " + bfsCnt + ", 当前设备: " + curDeviceName);
                    HashSet<DevicePort> ps = TopoNet.devicePortsTopo.get(curDeviceName);

                    int satisfiedCount = 0;

                    if (ps != null) {
                        // System.out.println("当前设备的端口数量: " + ps.size());
                        for (DevicePort p : ps) {
                            if (p.portName.equals("temp")) {
                                System.out.println("temptemptemptemp");
                                continue;
                            }
                            DevicePort dst = topology.get(p);
                            if (dst != null) {
                                String dstDeviceName = dst.deviceName;
                                // System.out.println("检查下一跳结点: " + dstDeviceName);
                                checkCnt++;

                                if (!visited.contains(dstDeviceName)) {
                                    Node dstNode = this.topoNet.getDstNodeByName(dst.deviceName);
                                    Context ctx = new Context();
                                    ctx.setCib(currentCtx.getCib());
                                    int topoId = currentCtx.topoId;
                                    ctx.setTopoId(topoId);
                                    NodePointer np = new NodePointer(dst.getPortName(), topoId);

                                    if (dstNode.countCheckByTopo(np, currentCtx)) {
                                        ctxCnt++;
                                        satisfiedCount++;
                                        // System.out.println("节点满足条件: " + dstNode.deviceName);
                                        List<Announcement> announcements = new LinkedList<>();
                                        Map<Count, Integer> nextCibOut = dstNode.getCibOut();
                                        for (Map.Entry<Count, Integer> entry : nextCibOut.entrySet())
                                            announcements.add(new Announcement(0, entry.getValue(), entry.getKey().count));
                                        CibMessage cibMessage = new CibMessage(announcements, new LinkedList<>(), index);
                                        ctx.setCib(cibMessage);
                                        ctx.setDeviceName(dstNode.deviceName);
                                        queue.add(ctx); // 入队列
                                        visited.add(dst.deviceName); // 访问标记放在这里
                                    } else {
                                        // System.out.println("节点不满足条件: " + dstNode.deviceName);
                                    }
                                } else {
                                    // System.out.println("节点已访问: " + dstDeviceName);
                                }
                            } else {
                                // System.out.println("没有找到目标端口: " + p.toString());
                            }
                        }
                    } else {
                        // System.out.println("当前设备没有端口信息: " + curDeviceName);
                    }

                    // System.out.println("节点 " + curDeviceName + " 结束后满足条件的节点个数: " + satisfiedCount);
                }
            }
            System.out.println("BFS结束，总遍历次数: " + bfsCnt + ", 满足条件的节点数: " + ctxCnt + ", 总检查次数: " + checkCnt);
        } finally {
            nodeLock.writeLock().unlock();
        }
    }

    // ==================================================================================
    // 【新增1】mergeAllCibOutPredicates() — 将cibOut中所有非零BDD谓词合并
    // 返回合并后的单一BDD节点，表示该源节点到目标节点的完整可达空间
    // ==================================================================================
    private int mergeAllCibOutPredicates(Map<Count, Integer> cibOut) {
        if (cibOut == null || cibOut.isEmpty()) {
            return 0; // BDD FALSE
        }

        boolean useCache = BDDEngine.isNPBDDEnabled();
        int merged = 0;

        for (Map.Entry<Count, Integer> entry : cibOut.entrySet()) {
            int predicate = entry.getValue();
            if (predicate == 0) continue;

            if (merged == 0) {
                merged = predicate;
            } else {
                if (useCache) {
                    merged = bdd.orToWithCache(merged, predicate);
                } else {
                    merged = bdd.orTo(merged, predicate);
                }
            }
        }

        return merged;
    }


    // ==================================================================================
    // 【新增2】decodeBddToSegments() — 将BDD节点解码为IP网段列表
    // 利用BDDEngine的printSet和decodeDstIP机制，提取所有满足赋值中的目标IP
    // 返回格式: ["25.5.228.0/24", "25.5.240.0/22", "25.9.95.2", ...]
    // ==================================================================================
    private List<String> decodeBddToSegments(int bddNode) {
        List<String> segments = new ArrayList<>();

        if (bddNode == 0) {
            return segments; // 空集
        }

        try {
            BDDEngine bddEngine = topoNet.getBddEngine();
            if (bddEngine == null) {
                return segments;
            }

            if (bddNode == 1) {
                // BDD TRUE => 全空间
                segments.add("0.0.0.0/0");
                return segments;
            }

            // 使用BDDEngine的printSet获取BDD的集合表示
            String rawOutput = bddEngine.printSet(bddNode);
            if (rawOutput == null || rawOutput.isEmpty() ||
                rawOutput.equals("null") || rawOutput.equals("all")) {
                if ("all".equals(rawOutput)) {
                    segments.add("0.0.0.0/0");
                }
                return segments;
            }

            // 解析printSet输出：每行是一个满足赋值，提取目标IP部分
            // printSet的输出格式示例: "10.0.1.0/24" 或直接的IP描述
            // 根据BDDEngine.decodeDstIP逻辑，提取 dstIPStartIndex 开始的32位
            String[] lines = rawOutput.split("\\n");
            Set<String> uniqueSegments = new LinkedHashSet<>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                // 尝试直接解析printSet返回的IP格式
                if (trimmed.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*")) {
                    // 提取IP/prefix部分
                    String extracted = extractIPSegment(trimmed);
                    if (extracted != null && !extracted.isEmpty()) {
                        uniqueSegments.add(extracted);
                    }
                }
            }

            // 如果printSet未返回可解析的IP格式，使用逐路径枚举方式
            if (uniqueSegments.isEmpty()) {
                List<String> enumerated = enumerateBddPaths(bddEngine, bddNode);
                uniqueSegments.addAll(enumerated);
            }

            segments.addAll(uniqueSegments);

        } catch (Exception e) {
            // 解码异常时返回空
            System.err.println("BDD解码异常: " + e.getMessage());
        }

        return segments;
    }

    /**
     * 从printSet输出行中提取IP网段
     */
    private String extractIPSegment(String line) {
        // 匹配 IP/prefix 或 纯IP 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(/\\d{1,2})?"
        );
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String ip = matcher.group(1);
            String prefix = matcher.group(2);
            if (prefix != null) {
                return ip + prefix;
            }
            return ip;
        }
        return null;
    }


    /**
     * 通过BDD路径枚举方式解码所有IP网段
     * 当printSet输出不易直接解析时使用此方法
     * 使用BDD的oneSat + 减去已找到路径的方式逐步枚举
     */
    private List<String> enumerateBddPaths(BDDEngine bddEngine, int bddNode) {
        List<String> results = new ArrayList<>();
        TSBDD tsbdd = bddEngine.getBDD();

        try {
            int remaining = bddNode;
            int maxIterations = 10000; // 防止无限循环
            int iteration = 0;

            while (remaining != 0 && iteration < maxIterations) {
                iteration++;

                // 获取一个满足赋值
                String oneResult = bddEngine.printSet(remaining);
                if (oneResult == null || oneResult.isEmpty() || oneResult.equals("null")) {
                    break;
                }

                // 尝试解析
                String segment = extractIPSegment(oneResult.split("\\n")[0]);
                if (segment != null && !segment.isEmpty()) {
                    results.add(segment);

                    // 将已找到的前缀编码为BDD并从remaining中减去
                    try {
                        int foundBdd = encodeSegmentToBdd(bddEngine, segment);
                        if (foundBdd != 0) {
                            remaining = tsbdd.diff(remaining, foundBdd);
                        } else {
                            break;
                        }
                    } catch (Exception e) {
                        break;
                    }
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            // 枚举失败
        }

        return results;
    }

    /**
     * 将IP/prefix网段编码回BDD用于减法运算
     */
    private int encodeSegmentToBdd(BDDEngine bddEngine, String segment) {
        try {
            String ip;
            int prefixLen = 32;

            if (segment.contains("/")) {
                String[] parts = segment.split("/");
                ip = parts[0];
                prefixLen = Integer.parseInt(parts[1]);
            } else {
                ip = segment;
            }

            // 内联IP转long（Utility中无ip2Int方法）
            String[] octets = ip.split("\\.");
            long ipLong = 0;
            for (int i = 0; i < 4; i++) {
                ipLong = (ipLong << 8) | (Long.parseLong(octets[i]) & 0xFF);
            }
            return bddEngine.encodeDstIPPrefix(ipLong, prefixLen);
        } catch (Exception e) {
            return 0;
        }
    }




    protected boolean countCheckByTopo(NodePointer from, Context ctx) {
        nodeLock.readLock().lock();
        try {
            CibMessage message = ctx.getCib();
            if (message != null) {
                // 1. 交集检查
                if (locCib.size() == 0) {
                    // System.out.println("节点 " + this.deviceName + " 的 locCib 为空，无法继续传播。");
                    return false;
                }
                if (!updateLocCibByTopo(from.name, message.announcements)) {
                    // System.out.println("节点 " + this.deviceName + " 无法更新 locCib，交集检查失败。");
                    return false;
                }
                // 2. 检查是否到达接入层结点
                if (checkIsSrcNode()) {
                    // System.out.println("节点 " + this.deviceName + " 到达接入层结点，停止传播。");
                    return false;
                }
                // 3. 拓扑排序, 只在满足todolist时继续传播
                if (!hasResult && todoList.isEmpty()) {
                    // System.out.println("节点 " + this.deviceName + " 拓扑排序结果为空，继续传播。");
                    return true;
                }
            }
            // System.out.println("节点 " + this.deviceName + " 无法满足传播条件，停止传播。");
            return false;
        } finally {
            nodeLock.readLock().unlock();
        }
    }


    public void sendFirstResultByTopo(Context ctx, Set<String> visited) {
        if (isNonVerificationNode) {
            return;
        }

        boolean useCache = BDDEngine.isNPBDDEnabled();
        List<Announcement> announcements = new LinkedList<>();
        Map<Count, Integer> cibOut = getCibOut(); // 已集成缓存

        for (Map.Entry<Count, Integer> entry : cibOut.entrySet()) {
            announcements.add(new Announcement(0, entry.getValue(), entry.getKey().count));
        }

        CibMessage cibMessage = new CibMessage(announcements, new LinkedList<>(), index);
        ctx.setCib(cibMessage);
        sendCountByTopo(ctx, visited);
        hasResult = true;
    }

    // 新函数:收到一个数据包就执行一次，对于其中每个FIB都要做一次
    protected void countByTopo(NodePointer from, Context ctx, Set<String> visited) {
        nodeLock.writeLock().lock();
        try {
            CibMessage message = ctx.getCib();
            // 检查是否是新结果
            // boolean isNew = storageAndCheckNew(from, message);
            if (message != null) {
                // Collection<Announcement> as = getAnnouncement(from);
                // 1. 检查某一端口的lec是否满足初始化时的packet space要求, 如果不满足则可直接判断不完全可达(包含交集检查)
                if (!updateLocCibByTopo(from.name, message.announcements)) {
                    return;
                }
                // 2.检查是否到达接入层结点, 关键剪枝
                if (checkIsSrcNode()) {
                    return;
                }
                // 3.拓扑排序, 只在满足todolist时往下递归, 关键剪枝
                if (!hasResult && todoList.isEmpty()) {
                    sendFirstResultByTopo(ctx, visited);
                }
            }
        } finally {
            nodeLock.writeLock().unlock();
        }
    }

    public void sendCountByTopo(Context ctx, Set<String> visited) {
        nodeLock.readLock().lock();
        try {
            lastResult = ctx.getCib();
            // new Func
            visited.add(deviceName);
            Collection<DevicePort> ps = TopoNet.devicePortsTopo.get(deviceName);
            if (ps != null) {
                for (DevicePort p : ps) {
                    if (p.portName.equals("temp")) {
                        return;
                    }
                    transferByTopo(ctx, p, visited);
                }
                // 回溯
                visited.remove(deviceName);
            } else
                System.out.println("No Forwarding Port!");
        } finally {
            nodeLock.readLock().unlock();
        }
    }

    public void transferByTopo(Context oldCtx, DevicePort sendPort, Set<String> visited) {
        DevicePort dst = topology.get(sendPort);
        Node dstNode = this.topoNet.getDstNodeByName(dst.deviceName);
        if (!visited.contains(dst.deviceName)) {
            // TODO: 跳数过大时,直接丢掉该包
            Context ctx = new Context();
            ctx.setCib(oldCtx.getCib());
            int topoId = oldCtx.topoId;
            ctx.setTopoId(topoId);
            NodePointer np = new NodePointer(dst.getPortName(), topoId);
            dstNode.countByTopo(np, ctx, visited);
        }
    }

    public void close() {
        // th.interrupt();
    }

    @Override
    public String toString() {
        // return getNodeName();
        return "";
    }

    // 新增：获取验证结果，用于批次验证
    public boolean getVerificationResult() {
        nodeLock.readLock().lock();
        try {
            if (verificationResult != null) {
                return verificationResult;
            }

            // 计算验证结果
            Map<Count, Integer> cibOut = getCibOut();
            int match_num = Integer.parseInt(invariant.getMatch().split("\\s+")[2]);

            for (Map.Entry<Count, Integer> entry : cibOut.entrySet()) {
                for (Integer count : entry.getKey().count) {
                    if (count >= match_num) {
                        verificationResult = true;
                        return true;
                    }
                }
            }

            verificationResult = false;
            return false;
        } finally {
            nodeLock.readLock().unlock();
        }
    }

    // 新增：重置验证结果缓存
    public void resetVerificationResult() {
        nodeLock.writeLock().lock();
        try {
            verificationResult = null;
        } finally {
            nodeLock.writeLock().unlock();
        }
    }

    public void showResult() {
        if (Configuration.getConfiguration().isShowResult()) {
            synchronized (outputLock) {
                Map<Count, Integer> cibOut = getCibOut();

                String srcDeviceName = this.device.name;
                String dstDeviceName = topoNet.dstDevice != null ?
                        topoNet.dstDevice.name : "Unknown";

                boolean hasValidRules = device != null &&
                        ((device.rules != null && !device.rules.isEmpty()) ||
                         (device.rulesIPV6 != null && !device.rulesIPV6.isEmpty()));

                // 非验证节点或无规则设备
                if (isNonVerificationNode || !hasValidRules) {
                    writeReachabilityToFile(srcDeviceName, dstDeviceName, "NULL", false);
                    System.out.println(srcDeviceName + "-" + dstDeviceName + ":NULL");
                    System.out.println("Num of DPVnets been verified: " + numDpvnet.getAndIncrement());
                    return;
                }

                try {
                    // 【核心修改】合并cibOut中所有BDD谓词，获取完整可达空间
                    int mergedBdd = mergeAllCibOutPredicates(cibOut);

                    if (mergedBdd == 0) {
                        // 不可达
                        writeReachabilityToFile(srcDeviceName, dstDeviceName, "NULL", false);
                        System.out.println(srcDeviceName + "-" + dstDeviceName + ":NULL");
                    } else {
                        // 【核心修改】解码合并后的BDD为IP网段列表
                        List<String> segmentList = decodeBddToSegments(mergedBdd);

                        if (segmentList.isEmpty()) {
                            // 解码结果为空，尝试回退到packetSpace解码
                            String fallback = decodePacketSpaceToNetworks(topoNet.packetSpace);
                            if (fallback != null && !fallback.isEmpty() &&
                                !fallback.startsWith("无效") && !fallback.startsWith("空的") &&
                                !fallback.startsWith("解码失败")) {
                                segmentList = Arrays.asList(fallback.split(",\\s*"));
                            }
                        }

                        if (segmentList.isEmpty()) {
                            writeReachabilityToFile(srcDeviceName, dstDeviceName, "NULL", false);
                            System.out.println(srcDeviceName + "-" + dstDeviceName + ":NULL");
                        } else {
                            // 格式化网段字符串: "seg1, seg2, seg3, ..."
                            String networksStr = String.join(", ", segmentList);
                            writeReachabilityToFile(srcDeviceName, dstDeviceName, networksStr, true);
                            System.out.println(srcDeviceName + "-" + dstDeviceName + ":" + networksStr);
                        }
                    }

                    System.out.println("Num of DPVnets been verified: " + numDpvnet.getAndIncrement());

                } catch (Exception e) {
                    writeReachabilityToFile(srcDeviceName, dstDeviceName, "NULL", false);
                    System.err.println("showResult异常: " + e.getMessage());
                    System.out.println("Num of DPVnets been verified: " + numDpvnet.getAndIncrement());
                }
            }
        }
    }


    // ===== 验证节点状态检查 =====

    /**
     * 检查并设置非验证节点状态
     */
    private void checkAndSetNonVerificationStatus() {
        try {
            if (!shouldParticipateInVerification(deviceName)) {
                isNonVerificationNode = true;
                return;
            }
            if (device != null &&
                (device.rules == null || device.rules.isEmpty()) &&
                (device.rulesIPV6 == null || device.rulesIPV6.isEmpty())) {
                isNonVerificationNode = true;
                return;
            }
            if (device == null) {
                isNonVerificationNode = true;
                return;
            }
            isNonVerificationNode = false;
        } catch (Exception e) {
            isNonVerificationNode = true;
        }
    }

    /**
     * 检查设备是否应该参与验证
     */
    private boolean shouldParticipateInVerification(String deviceName) {
        if (deviceName == null) return false;
        String lowerCaseName = deviceName.toLowerCase();
        return lowerCaseName.contains("aggr") || lowerCaseName.contains("core");
    }

    // ===== 文件写入功能 =====

    /**
     * 设置网络名称，构建结果文件路径
     */
    public static void setNetworkName(String networkName) {
        Node.networkName = networkName;
        updateResultFilePath();
    }

    /**
     * 更新结果文件路径
     */
    private static void updateResultFilePath() {
        if (networkName != null && !networkName.isEmpty()) {
            String dirname = "config/" + networkName;
            resultFilePath = dirname + (dirname.endsWith("/") ? "" : "/") + resultFileName;
        }
    }

    /**
     * 初始化文件写入
     */
    public static void initializeFileWriting() {
        if (!enableFileOutput) return;
        synchronized (fileWriteLock) {
            try {
                if (resultFilePath == null) {
                    resultFilePath = resultFileName;
                }
                File parentDir = new File(resultFilePath).getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                System.out.println("开始写入验证结果...");
                fileWriter = new BufferedWriter(new FileWriter(resultFilePath, false));
                isInitialized = true;
            } catch (IOException e) {
                System.err.println("初始化结果文件写入失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 完成文件写入
     */
    public static void finalizeFileWriting() {
        if (!enableFileOutput || !isInitialized) return;
        synchronized (fileWriteLock) {
            try {
                if (fileWriter != null) {
                    fileWriter.close();
                    fileWriter = null;
                }
                if (resultFilePath != null) {
                    File resultFile = new File(resultFilePath);
                    System.out.println("验证结果存放路径: " + resultFile.getAbsolutePath());
                    System.out.println("写入完成。");
                }
                isInitialized = false;
            } catch (IOException e) {
                System.err.println("完成结果文件写入失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 写入验证结果到文件
     */
    private void writeReachabilityToFile(String srcDevice, String dstDevice, String networks, boolean isReachable) {
        if (!enableFileOutput || !isInitialized) return;
        synchronized (fileWriteLock) {
            try {
                if (fileWriter != null) {
                    String content = String.format("%s-%s:%s%n", srcDevice, dstDevice, networks);
                    fileWriter.write(content);
                    fileWriter.flush();
                }
            } catch (IOException e) {
                System.err.println("写入文件失败: " + e.getMessage());
            }
        }
    }

    /**
     * 将Packet Space解码为网段格式
     */
    private String decodePacketSpaceToNetworks(int packetSpace) {
        try {
            if (topoNet.getBddEngine() == null || packetSpace == 0) {
                return "无效的Packet Space";
            }
            String rawOutput = topoNet.getBddEngine().printSet(packetSpace);
            if (rawOutput == null || rawOutput.isEmpty()) {
                return "空的Packet Space";
            }
            String[] ips = rawOutput.split(";");
            Set<String> networks = new LinkedHashSet<>();
            for (String ip : ips) {
                if (ip.trim().isEmpty()) continue;
                String cleanIp = ip.trim();
                if (cleanIp.contains("/")) {
                    networks.add(cleanIp);
                } else {
                    if (cleanIp.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
                        networks.add(cleanIp);
                    }
                }
            }
            return String.join(", ", networks);
        } catch (Exception e) {
            return "解码失败: " + e.getMessage();
        }
    }


}
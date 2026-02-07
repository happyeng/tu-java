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

    private static String resultFilePath = null;
    private static BufferedWriter resultWriter = null;
    private static final Object writerLock = new Object();

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
    //private static String resultFilePath = null;
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
        //checkAndSetNonVerificationStatus();
        initializeCibByTopo();
        // match_num = Integer.parseInt(invariant.getMatch().split("\\s+")[2]);
    }

    public boolean checkIsSrcNode() {
        TopoNet topoNet = this.topoNet;
        return topoNet.srcNodes.contains(this);
    }

    // updateLocCibByTopo - 添加调试
    public boolean updateLocCibByTopo(String from, Collection<Announcement> announcements) {
        synchronized (locCibLock) {

            if (locCib.isEmpty()) {
                return true;
            }

            boolean newResult = false;
            Queue<CibTuple> queue = new LinkedList<>(locCib);
            boolean useCache = BDDEngine.isNPBDDEnabled();

            int processedCount = 0;
            while (!queue.isEmpty()) {
                CibTuple cibTuple = queue.poll();
                processedCount++;

                for (Announcement announcement : announcements) {
                    int intersection;
                    if (useCache) {
                        intersection = bdd.andWithCache(announcement.predicate, cibTuple.predicate);
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

            if (topoNet == null) {
                return;
            }

            if (topoNet.deviceLecs == null) {
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


        } catch (Exception e) {
            System.err.println("[ERROR initializeCibByTopo] 设备 " + deviceName + " 异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 从LocCIB中导出CIBOut
    // getCibOut - 检查返回
    public Map<Count, Integer> getCibOut() {
        synchronized (locCibLock) {

            Map<Count, Integer> cibOut = new HashMap<>();
            boolean useCache = BDDEngine.isNPBDDEnabled();

            int validCount = 0;
            for (CibTuple cibTuple : locCib) {
                if (cibTuple.predicate == 0) {
                    continue;
                }
                validCount++;

                if (cibOut.containsKey(cibTuple.count)) {
                    int pre = cibOut.get(cibTuple.count);
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
            // ===== 关键修复9: 检查必要的静态数据是否初始化 =====
            if (TopoNet.devicePortsTopo == null) {
                System.err.println("[Node] 严重错误：TopoNet.devicePortsTopo 未初始化，无法执行BFS");
                return;
            }

            // ===== 关键修复10: 使用 TopoNet.network 而不是直接访问 network =====
            if (TopoNet.network == null || TopoNet.network.topology == null) {
                System.err.println("[Node] 错误：TopoNet.network 或 topology 未初始化");
                return;
            }
            // ===== 修复9和10结束 =====

            Queue<Context> queue = new LinkedList<>();
            HashSet<String> visited = new HashSet<>();
            Map<DevicePort, DevicePort> topology = TopoNet.network.topology;

            queue.add(c);
            visited.add(c.getDeviceName());

            int bfsCnt = 0;
            int ctxCnt = 0;
            int checkCnt = 0;
            System.out.println("终结点开始验证: " + c.getDeviceName());

            while (!queue.isEmpty()) {
                bfsCnt++;
                int size = queue.size();

                for (int i = 0; i < size; i++) {
                    Context currentCtx = queue.poll();
                    if (currentCtx == null) {
                        continue;
                    }

                    String curDeviceName = currentCtx.getDeviceName();
                    visited.add(curDeviceName);

                    // ===== 关键修复11: 添加空值检查 =====
                    HashSet<DevicePort> ps = TopoNet.devicePortsTopo.get(curDeviceName);

                    if (ps == null) {
                        System.err.println("[Node] 警告：设备 " + curDeviceName + " 在 devicePortsTopo 中无端口信息");
                        continue;
                    }
                    // ===== 修复11结束 =====

                    int satisfiedCount = 0;

                    for (DevicePort p : ps) {
                        if (p.portName.equals("temp")) {
                            continue;
                        }

                        DevicePort dst = topology.get(p);
                        if (dst != null) {
                            String dstDeviceName = dst.deviceName;
                            checkCnt++;

                            if (!visited.contains(dstDeviceName)) {
                                Node dstNode = this.topoNet.getDstNodeByName(dst.deviceName);

                                // ===== 关键修复12: 添加dstNode空值检查 =====
                                if (dstNode == null) {
                                    System.err.println("[Node] 警告：无法找到目标节点 " + dst.deviceName);
                                    continue;
                                }
                                // ===== 修复12结束 =====

                                Context ctx = new Context();
                                ctx.setCib(currentCtx.getCib());
                                int topoId = currentCtx.topoId;
                                ctx.setTopoId(topoId);
                                NodePointer np = new NodePointer(dst.getPortName(), topoId);

                                if (dstNode.countCheckByTopo(np, currentCtx)) {
                                    ctxCnt++;
                                    satisfiedCount++;

                                    List<Announcement> announcements = new LinkedList<>();
                                    Map<Count, Integer> nextCibOut = dstNode.getCibOut();

                                    if (nextCibOut != null) {
                                        for (Map.Entry<Count, Integer> entry : nextCibOut.entrySet()) {
                                            announcements.add(new Announcement(0, entry.getValue(), entry.getKey().count));
                                        }
                                    }

                                    CibMessage cibMessage = new CibMessage(announcements, new LinkedList<>(), index);
                                    ctx.setCib(cibMessage);
                                    ctx.setDeviceName(dstNode.deviceName);
                                    queue.add(ctx);
                                    visited.add(dst.deviceName);
                                }
                            }
                        }
                    }
                }
            }

            System.out.println("BFS结束，总遍历次数: " + bfsCnt + ", 满足条件的节点数: " + ctxCnt + ", 总检查次数: " + checkCnt);

        } catch (Exception e) {
            System.err.println("[Node] BFS执行异常: " + e.getMessage());
            e.printStackTrace();
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

        try {
            if (bddNode == 0) {
                return segments;
            }

            if (bddNode == 1) {
                segments.add("0.0.0.0/0");
                return segments;
            }

            // 从设备规则中提取目标IP段
            if (device != null && device.rules != null && !device.rules.isEmpty()) {
                for (Rule rule : device.rules) {
                    long dstIP = rule.ip;
                    int prefixLen = rule.prefixLen;

                    // 转换为点分十进制
                    String ipStr = String.format("%d.%d.%d.%d",
                        (dstIP >> 24) & 0xFF,
                        (dstIP >> 16) & 0xFF,
                        (dstIP >> 8) & 0xFF,
                        dstIP & 0xFF);

                    // 根据前缀长度决定输出格式
                    if (prefixLen == 32) {
                        segments.add(ipStr); // 单个IP不带/32
                    } else {
                        segments.add(ipStr + "/" + prefixLen);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[ERROR] decodeBddToSegments异常: " + e.getMessage());
        }

        return segments;
    }

    // 尝试从BDD直接解码（备用方案）
    private String tryDecodeBDD(int bddNode) {
        try {
            BDDEngine bddEngine = topoNet.getBddEngine();
            if (bddEngine == null) return null;

            TSBDD tsbdd = bddEngine.getBDD();
            int[] path = tsbdd.bdd.oneSat(bddNode, null);

            if (path == null || path.length < 64) {
                return null;
            }

            // 尝试从索引32开始提取dstIP（标准位置）
            long ipLong = 0;
            for (int i = 0; i < 32; i++) {
                if (path[32 + i] == 1) {
                    ipLong |= (1L << (31 - i));
                }
            }

            if (ipLong > 0 && ipLong < 0xFFFFFFFFL) {
                String ip = String.format("%d.%d.%d.%d",
                    (ipLong >> 24) & 0xFF,
                    (ipLong >> 16) & 0xFF,
                    (ipLong >> 8) & 0xFF,
                    ipLong & 0xFF);
                return ip + "/32";
            }

            return null;

        } catch (Exception e) {
            System.err.println("[ERROR tryDecodeBDD] " + e.getMessage());
            return null;
        }
    }

    // 从BDD路径中解码目标IP（假设dstIP变量从索引32开始）
    private String decodeIPFromPath(int[] path) {
        try {
            // BDD变量布局：srcIP(0-31), dstIP(32-63), ...
            // 提取 dstIP 的 32 位
            int dstIPStart = 32;
            long ipLong = 0;


            for (int i = 0; i < 32; i++) {
                int varIndex = dstIPStart + i;
                if (varIndex < path.length && path[varIndex] == 1) {
                    ipLong |= (1L << (31 - i));
                }
            }

            // 转换为点分十进制
            String result = String.format("%d.%d.%d.%d",
                (ipLong >> 24) & 0xFF,
                (ipLong >> 16) & 0xFF,
                (ipLong >> 8) & 0xFF,
                ipLong & 0xFF);

            return result;

        } catch (Exception e) {
            System.err.println("[ERROR decodeIP] " + e.getMessage());
            e.printStackTrace();
            return null;
        }
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


    // sendFirstResultByTopo - 验证流程传播
    public void sendFirstResultByTopo(Context c, Set<String> visited) {

        if (visited.contains(deviceName)) {
            return;
        }
        visited.add(deviceName);

        try {
            CibMessage cibOut = new CibMessage(new Vector<>(), new ArrayList<>(), index);
            Map<Count, Integer> cibOutMap = getCibOut();


            for (Map.Entry<Count, Integer> entry : cibOutMap.entrySet()) {
                Announcement a = new Announcement(0, entry.getValue(), entry.getKey().count);
                cibOut.announcements.add(a);
            }

            if (checkIsSrcNode()) {
                hasResult = true;
                lastResult = cibOut;
                return;
            }

            if (Node.nextTable.containsKey(deviceName)) {
                HashSet<NodePointer> nexts = Node.nextTable.get(deviceName);

                for (NodePointer np : nexts) {
                    DevicePort dst = TopoNet.network.topology.get(new DevicePort(deviceName, np.name));
                    if (dst != null) {
                        Node nextNode = topoNet.nodesTable.get(dst.deviceName);
                        if (nextNode != null) {
                            boolean updated = nextNode.updateLocCibByTopo(dst.getPortName(), cibOut.announcements);
                            if (updated) {
                                nextNode.sendFirstResultByTopo(c, visited);
                            }
                        }
                    }
                }
            } else {
            }
        } catch (Exception e) {
            System.err.println("[ERROR sendFirst] 节点 " + deviceName + " 异常: " + e.getMessage());
            e.printStackTrace();
        }
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
        if (!hasResult) {
            return;
        }

        try {
            String srcDeviceName = this.deviceName;
            String dstDeviceName = topoNet.dstDevice != null ? topoNet.dstDevice.name : "Unknown";

            Map<Count, Integer> cibOut = getCibOut();

            // 检查基本条件
            if (cibOut.isEmpty() || isNonVerificationNode ||
                device == null ||
                (device.rules == null || device.rules.isEmpty()) &&
                (device.rulesIPV6 == null || device.rulesIPV6.isEmpty())) {

                writeResultToFile(srcDeviceName, dstDeviceName, "UNREACHABLE");
                System.out.println(srcDeviceName + "-" + dstDeviceName + ":UNREACHABLE");
                System.out.println("Num of DPVnets been verified: " + numDpvnet.getAndIncrement());
                return;
            }

            try {
                int mergedBdd = mergeAllCibOutPredicates(cibOut);

                if (mergedBdd == 0) {
                    writeResultToFile(srcDeviceName, dstDeviceName, "UNREACHABLE");
                    System.out.println(srcDeviceName + "-" + dstDeviceName + ":UNREACHABLE");
                } else {
                    // 解码BDD得到网段列表
                    List<String> segmentList = decodeBddToSegments(mergedBdd);

                    if (segmentList.isEmpty()) {
                        writeResultToFile(srcDeviceName, dstDeviceName, "UNREACHABLE");
                        System.out.println(srcDeviceName + "-" + dstDeviceName + ":UNREACHABLE");
                    } else {
                        // 格式化网段列表：用逗号和空格分隔
                        String networks = String.join(", ", segmentList);
                        writeResultToFile(srcDeviceName, dstDeviceName, networks);
                        System.out.println(srcDeviceName + "-" + dstDeviceName + ":" + networks);
                    }
                }
            } catch (Exception e) {
                System.err.println("[ERROR] showResult BDD处理异常: " + e.getMessage());
                writeResultToFile(srcDeviceName, dstDeviceName, "UNREACHABLE");
                System.out.println(srcDeviceName + "-" + dstDeviceName + ":UNREACHABLE");
            }

            System.out.println("Num of DPVnets been verified: " + numDpvnet.getAndIncrement());

        } catch (Exception e) {
            System.err.println("[ERROR] showResult异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    // ===== 验证节点状态检查 =====

    /**
     * 检查并设置非验证节点状态
     * 修复：删除设备名检查，只根据是否有转发规则判断
     * 所有设备都参与验证，由 edgeDevices 和 dstDevices 配置文件决定
     */
    private void checkAndSetNonVerificationStatus() {
        try {
            // ===== 修复：删除了 shouldParticipateInVerification 检查 =====
            // 原代码会调用 shouldParticipateInVerification，虽然现在返回 true
            // 但这个检查是冗余的，应该删除

            if (device == null) {
                isNonVerificationNode = true;
                return;
            }

            // 检查设备是否有转发规则
            if ((device.rules == null || device.rules.isEmpty()) &&
                (device.rulesIPV6 == null || device.rulesIPV6.isEmpty())) {
                isNonVerificationNode = true;
                return;
            }

            // 有转发规则的设备都参与验证
            isNonVerificationNode = false;
        } catch (Exception e) {
            System.err.println("[验证] 检查验证节点状态异常: " + e.getMessage());
            isNonVerificationNode = true;
        }
    }

    /**
     * 检查设备是否应该参与验证
     */
    private boolean shouldParticipateInVerification(String deviceName) {
        //if (deviceName == null) return false;
        //String lowerCaseName = deviceName.toLowerCase();
        //return lowerCaseName.contains("aggr") || lowerCaseName.contains("core");
        return true;
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
    private void writeReachabilityToFile(String src, String dst, String networks, boolean reachable) {
        synchronized (writerLock) {
            if (resultWriter == null) {
                System.err.println("[ERROR] 结果文件未初始化，无法写入: " + src + "-" + dst);
                return;
            }

            try {
                String line = String.format("%s-%s:%s\n", src, dst, networks);
                resultWriter.write(line);
                resultWriter.flush();
            } catch (IOException e) {
                System.err.println("[ERROR] 写入结果失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 将Packet Space解码为网段格式
     */
    private String decodePacketSpaceToNetworks(int packetSpace) {

        try {
            BDDEngine bddEngine = topoNet.getBddEngine();
            if (bddEngine == null) {
                return null;
            }

            if (packetSpace == 0) {
                return null;
            }

            if (packetSpace == 1) {
                return "0.0.0.0/0";
            }

            // 使用printSet
            String rawOutput = bddEngine.printSet(packetSpace);

            if (rawOutput == null || rawOutput.isEmpty() || rawOutput.equals("null")) {
                return null;
            }

            if (rawOutput.equals("all")) {
                return "0.0.0.0/0";
            }

            // 简单解析：取第一行
            String[] lines = rawOutput.split("\\n");
            if (lines.length > 0) {
                String extracted = extractIPSegment(lines[0].trim());
                return extracted;
            }

            return null;

        } catch (Exception e) {
            System.err.println("[ERROR decodePacketSpace] 异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    // 初始化结果文件（在第一次调用 showResult 前调用一次）
    private static void initializeResultFileIfNeeded() {
        synchronized (writerLock) {
            if (resultWriter != null) {
                return;
            }

            try {
                Configuration config = Configuration.getConfiguration();

                // 通过 getDeviceRuleFile 获取一个规则文件路径来反推配置目录
                String sampleRulePath = config.getDeviceRuleFile("dummy");

                // sampleRulePath 格式: "完整路径/config/fattree2/rule/dummy"
                File ruleFile = new File(sampleRulePath);
                File ruleDir = ruleFile.getParentFile();      // rule目录
                File configDir = ruleDir.getParentFile();     // config/fattree2目录

                if (configDir == null || !configDir.exists()) {
                    System.err.println("[ERROR] 无法确定配置目录");
                    return;
                }

                // 在配置目录下创建结果文件
                File resultFile = new File(configDir, "verification_results.txt");
                resultFilePath = resultFile.getAbsolutePath();

                resultWriter = new BufferedWriter(new FileWriter(resultFile, false));
                resultWriter.write("# Verification Results\n");
                resultWriter.write("# Format: source-destination:network1, network2, ...\n");
                resultWriter.write("# Generated at: " + new java.util.Date() + "\n");
                resultWriter.write("# ==========================================\n");
                resultWriter.flush();

                System.out.println("结果文件已创建: " + resultFilePath);

            } catch (Exception e) {
                System.err.println("[ERROR] 初始化结果文件失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }


    /**
     * 从目标节点开始验证（TopoNet验证的入口）
     * 这是TopoNet验证的正确入口，会递归更新所有节点的locCib
     *
     * @param c 初始Context
     * @param visited 已访问节点集合
     */
    public void startCountByTopo(Context c, Set<String> visited) {
        nodeLock.writeLock().lock();
        try {
            // 1. 初始化Context的CIB为目标节点的包空间
            Announcement a = new Announcement(0, getPacketSpace(), Utility.getOneNumVector(1));
            Vector<Announcement> al = new Vector<>();
            al.add(a);
            CibMessage cibOut = new CibMessage(al, new ArrayList<>(), index);
            c.setCib(cibOut);
            c.setDeviceName(deviceName);

            // 2. 检查并标记非验证节点
            if (isNonVerificationNode) {
                System.out.println("[验证] 节点 " + deviceName + " 是非验证节点，跳过");
                return;
            }

            // 3. 如果节点已有todoList（已初始化），开始递归传播
            if (!todoList.isEmpty()) {
                sendFirstResultByTopo(c, visited);
            } else {
                // 节点没有转发规则，检查是否为源节点
                if (checkIsSrcNode()) {
                    System.out.println("[验证] 到达源节点: " + deviceName);
                    // 源节点的locCib应该已经在初始化时设置好了
                } else {
                    System.out.println("[验证] 节点 " + deviceName + " 无转发规则且非源节点");
                }
            }
        } catch (Exception e) {
            System.err.println("[验证] 启动验证异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            nodeLock.writeLock().unlock();
        }
    }

    public static void initializeResultFile(String configPath) {
        try {
            // configPath 是 "config/fattree2" 这样的路径
            // 直接在这个文件夹下创建结果文件
            File configDir = new File(configPath);

            // 如果路径不存在，尝试创建
            if (!configDir.exists()) {
                System.err.println("[ERROR] 配置文件夹不存在: " + configDir.getAbsolutePath());
                // 尝试使用当前工作目录
                configDir = new File(System.getProperty("user.dir"), configPath);
                System.out.println("[INFO] 尝试使用路径: " + configDir.getAbsolutePath());
            }

            if (!configDir.exists() || !configDir.isDirectory()) {
                System.err.println("[ERROR] 无法找到配置文件夹: " + configDir.getAbsolutePath());
                return;
            }

            // 在配置文件夹下创建结果文件
            File resultFile = new File(configDir, "verification_results.txt");
            resultFilePath = resultFile.getAbsolutePath();

            System.out.println("[INFO] 准备创建结果文件: " + resultFilePath);

            synchronized (writerLock) {
                resultWriter = new BufferedWriter(new FileWriter(resultFile, false));
                resultWriter.write("# Verification Results\n");
                resultWriter.write("# Format: source-destination:reachable_networks\n");
                resultWriter.write("# Generated at: " + new java.util.Date() + "\n");
                resultWriter.write("# ==========================================\n");
                resultWriter.flush();
            }

            System.out.println("结果文件已创建: " + resultFilePath);

        } catch (IOException e) {
            System.err.println("[ERROR] 无法创建结果文件: " + e.getMessage());
            e.printStackTrace();
        }
    }
    // 关闭结果文件
    public static void closeResultFile() {
        synchronized (writerLock) {
            if (resultWriter != null) {
                try {
                    resultWriter.close();
                    resultWriter = null;
                    System.out.println("结果文件已关闭: " + resultFilePath);
                } catch (IOException e) {
                    System.err.println("[ERROR] 关闭结果文件失败: " + e.getMessage());
                }
            }
        }
    }
    // 写入可达性结果（简化版，不输出具体网段）
    private void writeResultToFile(String src, String dst, String networks) {
        synchronized (writerLock) {
            if (resultWriter == null) {
                initializeResultFileIfNeeded();
            }

            if (resultWriter == null) {
                return;
            }

            try {
                String line = String.format("%s-%s:%s\n", src, dst, networks);
                resultWriter.write(line);
                resultWriter.flush();
            } catch (IOException e) {
                System.err.println("[ERROR] 写入结果失败: " + e.getMessage());
            }
        }
    }

}

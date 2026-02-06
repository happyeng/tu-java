/**
 * ==================================================================================
 * Neighborhood.java — NP-Net 邻域数据结构
 * ==================================================================================
 * 
 * 实现HeTu论文Section V中的NP-Net设计:
 * - 将共享相同转发模式的端点接口分组为Neighborhood
 * - 每个Neighborhood包含: Inner Area (内部区域), Outer Area (外部区域), Entrance (入口)
 * - 通过邻域聚合减少冗余TopoNet, 共享边界节点的中间计算结果
 * 
 * 放置位置: src/main/java/org/sngroup/verifier/npnet/Neighborhood.java
 * ==================================================================================
 */

package org.sngroup.verifier.npnet;

import org.sngroup.verifier.*;
import org.sngroup.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * NP-Net邻域 — 对应HeTu论文Fig.4中的一个Neighborhood
 * 
 * 一个Neighborhood聚合了多个共享相同转发模式的目标edge device,
 * 共享内部拓扑遍历的中间结果, 减少重复BFS计算
 */
public class Neighborhood {

    /** 邻域唯一ID */
    private final int id;

    /** 邻域名称 (用于调试) */
    private final String name;

    /** 
     * 邻域内的目标设备集合 (多个共享转发模式的edge device)
     * 这些device原本各需一个独立TopoNet, 现在共享内部遍历
     */
    private final Set<Device> dstDevices;

    /**
     * Inner Area: 邻域内部节点
     * 这些节点的转发行为在所有dstDevices的验证中完全相同
     * 只需遍历一次
     */
    private final Set<String> innerArea;

    /**
     * Outer Area: 邻域外部节点  
     * 这些节点不在本邻域内, 但可能通过Entrance与本邻域交互
     */
    private final Set<String> outerArea;

    /**
     * Entrance: 入口节点 (边界节点)
     * 连接Inner Area和Outer Area的节点
     * 是跨邻域空间聚合的关键点
     */
    private final Set<String> entranceNodes;

    /**
     * 邻域的代表TopoNet (用于共享内部遍历)
     * 所有dstDevices共享这一个内部遍历结果
     */
    private TopoNet representativeTopoNet;

    /**
     * Entrance节点上聚合的空间表示
     * key: entrance节点名, value: 聚合后的BDD谓词
     * 用于外部遍历时作为初始空间
     */
    private final ConcurrentHashMap<String, Integer> entranceSpaces;

    /**
     * 内部遍历是否已完成
     */
    private volatile boolean innerTraversalCompleted = false;

    /**
     * 每个dstDevice对应的独立TopoNet (用于外部遍历)
     */
    private final Map<String, TopoNet> deviceTopoNets;


    public Neighborhood(int id, String name) {
        this.id = id;
        this.name = name;
        this.dstDevices = ConcurrentHashMap.newKeySet();
        this.innerArea = ConcurrentHashMap.newKeySet();
        this.outerArea = ConcurrentHashMap.newKeySet();
        this.entranceNodes = ConcurrentHashMap.newKeySet();
        this.entranceSpaces = new ConcurrentHashMap<>();
        this.deviceTopoNets = new ConcurrentHashMap<>();
    }


    // ==================== 构建方法 ====================

    /**
     * 添加目标设备到本邻域
     */
    public void addDstDevice(Device device) {
        dstDevices.add(device);
    }

    /**
     * 设置区域划分
     * @param inner 内部区域节点名集合
     * @param outer 外部区域节点名集合
     * @param entrance 入口节点名集合
     */
    public void setAreas(Set<String> inner, Set<String> outer, Set<String> entrance) {
        this.innerArea.clear();
        this.innerArea.addAll(inner);
        this.outerArea.clear();
        this.outerArea.addAll(outer);
        this.entranceNodes.clear();
        this.entranceNodes.addAll(entrance);
    }

    /**
     * 设置代表TopoNet
     */
    public void setRepresentativeTopoNet(TopoNet topoNet) {
        this.representativeTopoNet = topoNet;
    }

    /**
     * 注册设备的独立TopoNet
     */
    public void putDeviceTopoNet(String deviceName, TopoNet topoNet) {
        this.deviceTopoNets.put(deviceName, topoNet);
    }


    // ==================== 内部遍历 ====================

    /**
     * 执行内部遍历 (Inner Traversal)
     * 
     * 论文描述: "Inner Traversal propagates space representations within
     * the Inner Area, while boundary nodes propagate their space 
     * representations to the Neighborhood Entrance"
     * 
     * 只遍历Inner Area中的节点, 在Entrance处聚合空间表示
     */
    public void executeInnerTraversal() {
        if (representativeTopoNet == null) {
            System.err.println("[NP-Net] Neighborhood " + name + " 没有代表TopoNet");
            return;
        }

        System.out.println("[NP-Net] Neighborhood " + name + " 开始内部遍历, " +
                          "Inner节点: " + innerArea.size() + ", Entrance节点: " + entranceNodes.size());

        try {
            // 使用代表TopoNet执行BFS, 但限制在Inner Area内
            Node dstNode = representativeTopoNet.getDstNode();
            if (dstNode == null) return;

            TSBDD bdd = representativeTopoNet.getBddEngine().getBDD();
            boolean useCache = BDDEngine.isNPBDDEnabled();

            // 初始化: 从dstNode开始, 只在innerArea内传播
            Context ctx = new Context();
            ctx.topoId = representativeTopoNet.topoCnt;
            dstNode.bfsByIteration(ctx);

            // 收集Entrance节点上的空间表示
            for (String entranceName : entranceNodes) {
                Node entranceNode = representativeTopoNet.getDstNodeByName(entranceName);
                if (entranceNode != null) {
                    Map<Count, Integer> cibOut = entranceNode.getCibOut();
                    // 合并所有谓词
                    int merged = 0;
                    for (Map.Entry<Count, Integer> entry : cibOut.entrySet()) {
                        if (entry.getValue() != 0) {
                            if (useCache) {
                                merged = bdd.orToWithCache(merged, entry.getValue());
                            } else {
                                merged = bdd.orTo(merged, entry.getValue());
                            }
                        }
                    }
                    entranceSpaces.put(entranceName, merged);
                }
            }

            innerTraversalCompleted = true;
            System.out.println("[NP-Net] Neighborhood " + name + " 内部遍历完成, " +
                              "Entrance聚合点: " + entranceSpaces.size());

        } catch (Exception e) {
            System.err.println("[NP-Net] 内部遍历异常: " + e.getMessage());
            e.printStackTrace();
        }
    }


    // ==================== 外部遍历 ====================

    /**
     * 执行外部遍历 (External Traversal)
     * 
     * 论文描述: "External Traversal propagates space representations
     * into the Outer Area via the Neighborhood Entrances."
     * 
     * 对每个dstDevice的独立TopoNet, 从Entrance节点的聚合空间开始,
     * 向Outer Area传播
     * 
     * @param deviceName 目标设备名
     * @param topoNet 该设备的独立TopoNet
     */
    public void executeExternalTraversal(String deviceName, TopoNet topoNet) {
        if (!innerTraversalCompleted) {
            System.err.println("[NP-Net] 内部遍历未完成, 无法执行外部遍历");
            return;
        }

        try {
            // 将Entrance的聚合空间注入到该TopoNet的对应节点
            for (Map.Entry<String, Integer> entry : entranceSpaces.entrySet()) {
                String entranceName = entry.getKey();
                int aggregatedSpace = entry.getValue();

                if (aggregatedSpace == 0) continue;

                Node entranceNode = topoNet.getDstNodeByName(entranceName);
                if (entranceNode != null) {
                    // 将聚合空间作为初始CIB注入entrance节点
                    Announcement a = new Announcement(0, aggregatedSpace,
                            org.sngroup.util.Utility.getOneNumVector(1));
                    Vector<Announcement> al = new Vector<>();
                    al.add(a);
                    CibMessage cibMsg = new CibMessage(al, new ArrayList<>(), entranceNode.index);

                    Context ctx = new Context();
                    ctx.setCib(cibMsg);
                    ctx.setDeviceName(entranceName);
                    ctx.topoId = topoNet.topoCnt;

                    // 从entrance向outer area传播
                    entranceNode.sendCountByTopo(ctx, new HashSet<>(innerArea));
                }
            }
        } catch (Exception e) {
            System.err.println("[NP-Net] 外部遍历异常(" + deviceName + "): " + e.getMessage());
        }
    }


    // ==================== 判断方法 ====================

    /**
     * 检查给定节点是否在Inner Area中
     */
    public boolean isInInnerArea(String deviceName) {
        return innerArea.contains(deviceName);
    }

    /**
     * 检查给定节点是否为Entrance节点
     */
    public boolean isEntrance(String deviceName) {
        return entranceNodes.contains(deviceName);
    }

    /**
     * 检查给定节点是否在Outer Area中
     */
    public boolean isInOuterArea(String deviceName) {
        return outerArea.contains(deviceName);
    }

    /**
     * 该邻域是否值得使用NP-Net优化
     * 只有包含多个dstDevice时, 聚合才有意义
     */
    public boolean isWorthOptimizing() {
        return dstDevices.size() > 1;
    }


    // ==================== Getter ====================

    public int getId() { return id; }
    public String getName() { return name; }
    public Set<Device> getDstDevices() { return Collections.unmodifiableSet(dstDevices); }
    public Set<String> getInnerArea() { return Collections.unmodifiableSet(innerArea); }
    public Set<String> getOuterArea() { return Collections.unmodifiableSet(outerArea); }
    public Set<String> getEntranceNodes() { return Collections.unmodifiableSet(entranceNodes); }
    public TopoNet getRepresentativeTopoNet() { return representativeTopoNet; }
    public Map<String, Integer> getEntranceSpaces() { return Collections.unmodifiableMap(entranceSpaces); }
    public boolean isInnerTraversalCompleted() { return innerTraversalCompleted; }
    public Map<String, TopoNet> getDeviceTopoNets() { return deviceTopoNets; }


    @Override
    public String toString() {
        return "Neighborhood{" +
               "id=" + id +
               ", name='" + name + '\'' +
               ", dstDevices=" + dstDevices.size() +
               ", inner=" + innerArea.size() +
               ", entrance=" + entranceNodes.size() +
               ", outer=" + outerArea.size() +
               '}';
    }
}

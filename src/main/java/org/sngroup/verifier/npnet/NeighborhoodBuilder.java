/**
 * ==================================================================================
 * NeighborhoodBuilder.java — NP-Net 邻域构建器
 * ==================================================================================
 * 
 * 实现HeTu论文Section V中的Neighborhood Construction:
 * 1. 分析所有edge device的转发规则模式 (forwarding pattern fingerprint)
 * 2. 将共享相似转发模式的device分组为Neighborhood
 * 3. 对每个Neighborhood划分 Inner Area / Outer Area / Entrance
 * 4. 创建代表TopoNet用于共享内部遍历
 * 
 * 放置位置: src/main/java/org/sngroup/verifier/npnet/NeighborhoodBuilder.java
 * ==================================================================================
 */

package org.sngroup.verifier.npnet;

import org.sngroup.verifier.*;
import org.sngroup.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 邻域构建器
 * 
 * 核心算法:
 * 1. 计算每个edge device的"转发指纹"(forwarding fingerprint)
 *    指纹 = 该设备所有转发规则的下一跳端口集合的摘要
 * 2. 按指纹分组，指纹相同的device归入同一Neighborhood
 * 3. 对每个Neighborhood:
 *    - Inner Area = dstDevices及其直接邻居中共享相同转发行为的节点
 *    - Entrance = Inner Area中连接到Outer Area的节点 (边界节点)
 *    - Outer Area = 所有不在Inner Area中的节点
 */
public class NeighborhoodBuilder {

    /** 所有设备 */
    private final Map<String, Device> devices;

    /** 网络拓扑 */
    private final Network network;

    /** edge设备集合 */
    private final Set<Device> edgeDevices;

    /** 拓扑邻接表: deviceName -> 直接相连的设备名集合 */
    private final Map<String, Set<String>> adjacencyMap;

    /** 构建的邻域列表 */
    private final List<Neighborhood> neighborhoods;

    /** 最小邻域大小 (少于此数的分组不做聚合) */
    private static final int MIN_NEIGHBORHOOD_SIZE = 2;

    /** 邻域计数器 */
    private int neighborhoodCounter = 0;


    public NeighborhoodBuilder(Map<String, Device> devices, Network network, Set<Device> edgeDevices) {
        this.devices = devices;
        this.network = network;
        this.edgeDevices = edgeDevices;
        this.adjacencyMap = new HashMap<>();
        this.neighborhoods = new ArrayList<>();

        buildAdjacencyMap();
    }


    /**
     * 从网络拓扑构建邻接表
     */
    private void buildAdjacencyMap() {
        if (network.devicePorts == null) return;

        for (Map.Entry<String, Map<String, Set<DevicePort>>> entry : network.devicePorts.entrySet()) {
            String deviceName = entry.getKey();
            Set<String> neighbors = new HashSet<>();

            for (Map.Entry<String, Set<DevicePort>> portEntry : entry.getValue().entrySet()) {
                for (DevicePort dp : portEntry.getValue()) {
                    // 从拓扑表中查找对端设备
                    DevicePort peer = Node.topology.get(dp);
                    if (peer != null && !peer.deviceName.equals(deviceName)) {
                        neighbors.add(peer.deviceName);
                    }
                }
            }

            adjacencyMap.put(deviceName, neighbors);
        }
    }


    /**
     * 执行完整的邻域构建流程
     * 
     * @return 构建的邻域列表
     */
    public List<Neighborhood> buildNeighborhoods() {
        long startTime = System.currentTimeMillis();
        System.out.println("[NP-Net] 开始构建Neighborhoods...");

        // Step 1: 计算每个edge device的转发指纹
        Map<String, String> deviceFingerprints = computeForwardingFingerprints();
        System.out.println("[NP-Net] 计算转发指纹完成, 共 " + deviceFingerprints.size() + " 个edge device");

        // Step 2: 按指纹分组
        Map<String, List<Device>> fingerprintGroups = groupByFingerprint(deviceFingerprints);
        System.out.println("[NP-Net] 指纹分组完成, 共 " + fingerprintGroups.size() + " 个不同指纹");

        // Step 3: 为每个满足条件的分组创建Neighborhood
        for (Map.Entry<String, List<Device>> group : fingerprintGroups.entrySet()) {
            List<Device> groupDevices = group.getValue();

            if (groupDevices.size() >= MIN_NEIGHBORHOOD_SIZE) {
                Neighborhood neighborhood = createNeighborhood(groupDevices);
                neighborhoods.add(neighborhood);
            }
        }

        // 统计
        int totalAggregated = neighborhoods.stream()
                .mapToInt(n -> n.getDstDevices().size())
                .sum();
        int totalEdge = edgeDevices.size();

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("[NP-Net] Neighborhood构建完成:");
        System.out.println("  邻域数量: " + neighborhoods.size());
        System.out.println("  聚合设备: " + totalAggregated + "/" + totalEdge);
        System.out.println("  预计减少TopoNet: " + (totalAggregated - neighborhoods.size()) + " 个");
        System.out.println("  构建耗时: " + elapsed + "ms");

        return neighborhoods;
    }


    /**
     * Step 1: 计算每个edge device的转发指纹
     * 
     * 指纹定义:
     * - 将设备的所有转发规则按下一跳端口分组
     * - 对每个端口, 计算其匹配的前缀集合的hash
     * - 最终指纹 = 所有(端口, 前缀hash)对的有序拼接
     * 
     * 具有相同转发指纹的设备, 其验证图的内部结构相同
     */
    private Map<String, String> computeForwardingFingerprints() {
        Map<String, String> fingerprints = new HashMap<>();

        for (Device device : edgeDevices) {
            if (device.rules == null || device.rules.isEmpty()) {
                fingerprints.put(device.name, "EMPTY");
                continue;
            }

            // 构建指纹: 按下一跳端口分组, 收集前缀
            TreeMap<String, TreeSet<String>> portToPrefixes = new TreeMap<>();

            for (Rule rule : device.rules) {
                String portKey = rule.forwardAction != null ?
                        rule.forwardAction.toString() : "NULL";
                portToPrefixes.computeIfAbsent(portKey, k -> new TreeSet<>())
                        .add(rule.ip + "/" + rule.prefixLen);
            }

            // 生成指纹字符串
            StringBuilder fp = new StringBuilder();
            for (Map.Entry<String, TreeSet<String>> entry : portToPrefixes.entrySet()) {
                fp.append(entry.getKey()).append(":");
                fp.append(entry.getValue().hashCode()).append(";");
            }

            fingerprints.put(device.name, fp.toString());
        }

        return fingerprints;
    }


    /**
     * Step 2: 按指纹分组
     */
    private Map<String, List<Device>> groupByFingerprint(Map<String, String> fingerprints) {
        Map<String, List<Device>> groups = new HashMap<>();

        for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
            String deviceName = entry.getKey();
            String fingerprint = entry.getValue();

            Device device = devices.get(deviceName);
            if (device == null) continue;

            groups.computeIfAbsent(fingerprint, k -> new ArrayList<>()).add(device);
        }

        return groups;
    }


    /**
     * Step 3: 为一组设备创建Neighborhood
     * 
     * 区域划分算法:
     * 1. 收集所有dstDevices的直接拓扑邻居
     * 2. Inner Area = 所有dstDevices自身 + 它们共享的邻居节点
     * 3. Entrance = Inner Area中有连接到非Inner节点的节点
     * 4. Outer Area = 全图 - Inner Area
     */
    private Neighborhood createNeighborhood(List<Device> groupDevices) {
        neighborhoodCounter++;
        String name = "NH-" + neighborhoodCounter;
        Neighborhood neighborhood = new Neighborhood(neighborhoodCounter, name);

        // 添加所有dstDevices
        for (Device device : groupDevices) {
            neighborhood.addDstDevice(device);
        }

        // 计算区域划分
        Set<String> deviceNames = groupDevices.stream()
                .map(d -> d.name)
                .collect(Collectors.toSet());

        // Inner Area = dstDevices + 它们共同的邻居
        Set<String> inner = new HashSet<>(deviceNames);

        // 找到所有dstDevices的邻居交集 (共享邻居)
        Set<String> sharedNeighbors = null;
        for (String devName : deviceNames) {
            Set<String> neighbors = adjacencyMap.getOrDefault(devName, Collections.emptySet());
            if (sharedNeighbors == null) {
                sharedNeighbors = new HashSet<>(neighbors);
            } else {
                sharedNeighbors.retainAll(neighbors);
            }
        }

        // 将共享邻居加入Inner Area
        if (sharedNeighbors != null) {
            inner.addAll(sharedNeighbors);
        }

        // 找到Entrance节点 (Inner Area中连接到Outer的节点)
        Set<String> entrance = new HashSet<>();
        for (String innerNode : inner) {
            Set<String> neighbors = adjacencyMap.getOrDefault(innerNode, Collections.emptySet());
            for (String neighbor : neighbors) {
                if (!inner.contains(neighbor)) {
                    // 该inner节点有连接到outer的边 => 是entrance
                    entrance.add(innerNode);
                    break;
                }
            }
        }

        // Outer Area = 全部设备 - Inner Area
        Set<String> outer = new HashSet<>(devices.keySet());
        outer.removeAll(inner);

        neighborhood.setAreas(inner, outer, entrance);

        System.out.println("[NP-Net] 创建 " + name + ": " +
                          groupDevices.size() + " dstDevices, " +
                          inner.size() + " inner, " +
                          entrance.size() + " entrance, " +
                          outer.size() + " outer");

        return neighborhood;
    }


    /**
     * 获取设备所属的Neighborhood (如果有)
     * @param deviceName 设备名
     * @return 所属Neighborhood, 或null
     */
    public Neighborhood getNeighborhoodForDevice(String deviceName) {
        for (Neighborhood nh : neighborhoods) {
            for (Device d : nh.getDstDevices()) {
                if (d.name.equals(deviceName)) {
                    return nh;
                }
            }
        }
        return null;
    }


    /**
     * 获取不在任何Neighborhood中的edge devices
     * 这些设备仍需使用独立TopoNet验证
     */
    public Set<Device> getStandaloneDevices() {
        Set<String> aggregatedNames = new HashSet<>();
        for (Neighborhood nh : neighborhoods) {
            for (Device d : nh.getDstDevices()) {
                aggregatedNames.add(d.name);
            }
        }

        Set<Device> standalone = new HashSet<>();
        for (Device d : edgeDevices) {
            if (!aggregatedNames.contains(d.name)) {
                standalone.add(d);
            }
        }
        return standalone;
    }


    /**
     * 获取构建的所有邻域
     */
    public List<Neighborhood> getNeighborhoods() {
        return Collections.unmodifiableList(neighborhoods);
    }
}

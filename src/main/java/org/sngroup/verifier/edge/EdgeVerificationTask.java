// EdgeVerificationTask.java - 单个EDGE验证任务
package org.sngroup.verifier.edge;

import org.sngroup.verifier.TopoNet;
import org.sngroup.verifier.Node;
import org.sngroup.verifier.Device;
import org.sngroup.verifier.BDDEngine;
import org.sngroup.util.*;
import java.util.*;
import java.util.concurrent.Callable;

public class EdgeVerificationTask implements Callable<EdgeVerificationResult> {
    private final String edgeNode;
    private final List<String> physicalDevices;
    private final Set<String> allRequiredDevices;
    private final ForwardingTableLoader tableLoader;
    private final TopoNet topoNet;

    public EdgeVerificationTask(String edgeNode, List<String> physicalDevices,
                               Set<String> allRequiredDevices, ForwardingTableLoader tableLoader, TopoNet topoNet) {
        this.edgeNode = edgeNode;
        this.physicalDevices = physicalDevices;
        this.allRequiredDevices = allRequiredDevices;
        this.tableLoader = tableLoader;
        this.topoNet = topoNet;
    }

    @Override
    public EdgeVerificationResult call() throws Exception {
        // 确保topoNet有BDD引擎
        if (topoNet.bddEngine == null) {
            topoNet.bddEngine = new BDDEngine();
        }

        // 使用原有方法加载和编码设备规则
        tableLoader.loadAndEncodeDevices(allRequiredDevices, topoNet);

        // 调用原有验证逻辑
        boolean isReachable = performInternalReachabilityCheck();

        return new EdgeVerificationResult(edgeNode, isReachable, physicalDevices.size());
    }

    private boolean performInternalReachabilityCheck() {
        try {
            // 创建EDGE内部验证子网络
            TopoNet edgeSubNet = createEdgeSubTopology();

            // 验证所有物理设备间的连通性
            boolean allReachable = true;
            for (int i = 0; i < physicalDevices.size() && allReachable; i++) {
                for (int j = i + 1; j < physicalDevices.size() && allReachable; j++) {
                    String srcDevice = physicalDevices.get(i);
                    String dstDevice = physicalDevices.get(j);

                    // 使用原有验证逻辑
                    allReachable = verifyDeviceReachability(edgeSubNet, srcDevice, dstDevice);
                }
            }

            return allReachable;
        } catch (Exception e) {
            System.err.println("Verification failed for " + edgeNode + ": " + e.getMessage());
            return false;
        }
    }

    private TopoNet createEdgeSubTopology() {
        // 创建EDGE内部子拓扑，使用原有TopoNet结构
        TopoNet subNet = new TopoNet(topoNet.dstDevice, topoNet.topoCnt);
        // 确保子网络有BDD引擎
        subNet.bddEngine = topoNet.bddEngine;

        if (topoNet.invariant != null) {
            subNet.setInvariant(topoNet.invariant.getPacketSpace(),
                               topoNet.invariant.getMatch(),
                               topoNet.invariant.getPath());
        }

        // 获取已加载的设备
        Map<String, Device> loadedDevices = tableLoader.getLoadedDevices();

        // 为物理设备创建节点，使用原有Device对象
        for (String deviceName : physicalDevices) {
            Device device = loadedDevices.get(deviceName);
            if (device != null) {
                Node node = new Node(device, subNet);
                node.setBdd(topoNet.bddEngine);
                subNet.nodesTable.put(deviceName, node);
            }
        }

        return subNet;
    }

    private boolean verifyDeviceReachability(TopoNet subNet, String srcDevice, String dstDevice) {
        try {
            Node srcNode = subNet.getDstNodeByName(srcDevice);
            Node dstNode = subNet.getDstNodeByName(dstDevice);

            if (srcNode == null || dstNode == null) {
                return false;
            }

            // 使用原有验证逻辑进行可达性检查
            srcNode.topoNetStart();
            dstNode.topoNetStart();

            // 检查验证结果
            return srcNode.hasResult && dstNode.hasResult;
        } catch (Exception e) {
            return false;
        }
    }
}
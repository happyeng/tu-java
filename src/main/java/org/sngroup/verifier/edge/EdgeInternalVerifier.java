// EdgeInternalVerifier.java - 主验证器
package org.sngroup.verifier.edge;

import org.sngroup.verifier.TopoNet;
import java.util.*;
import java.util.concurrent.*;

public class EdgeInternalVerifier {
    private final TopoNet topoNet;
    private final ForwardingTableLoader tableLoader;
    private final TopologyAnalyzer topologyAnalyzer;
    private final ExecutorService executorService;
    
    public EdgeInternalVerifier(TopoNet topoNet, String forwardingTableDir) {
        this.topoNet = topoNet;
        this.tableLoader = new ForwardingTableLoader(forwardingTableDir);
        this.topologyAnalyzer = new TopologyAnalyzer();
        this.executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }
    
    public List<EdgeVerificationResult> verifyAllEdges(String edgeMappingFile, String physicalTopologyFile) 
            throws Exception {
        
        // 解析EDGE映射
        Map<String, List<String>> edgeMapping = EdgeMappingParser.parseEdgeMapping(edgeMappingFile);
        
        // 解析物理拓扑
        topologyAnalyzer.parseTopology(physicalTopologyFile);
        
        // 创建验证任务
        List<EdgeVerificationTask> tasks = new ArrayList<>();
        
        for (Map.Entry<String, List<String>> entry : edgeMapping.entrySet()) {
            String edgeNode = entry.getKey();
            List<String> physicalDevices = entry.getValue();
            
            // 确定需要加载的所有设备转发表
            Set<String> requiredDevices = determineRequiredDevices(edgeNode, edgeMapping);
            
            EdgeVerificationTask task = new EdgeVerificationTask(
                edgeNode, physicalDevices, requiredDevices, tableLoader, topoNet);
            tasks.add(task);
        }
        
        // 并行执行验证
        List<Future<EdgeVerificationResult>> futures = executorService.invokeAll(tasks);
        
        // 收集结果
        List<EdgeVerificationResult> results = new ArrayList<>();
        for (Future<EdgeVerificationResult> future : futures) {
            results.add(future.get());
        }
        
        return results;
    }
    
    private Set<String> determineRequiredDevices(String edgeNode, Map<String, List<String>> edgeMapping) {
        Set<String> requiredDevices = new HashSet<>();
        
        // 添加EDGE节点内的物理设备
        List<String> edgePhysicalDevices = edgeMapping.get(edgeNode);
        if (edgePhysicalDevices != null) {
            requiredDevices.addAll(edgePhysicalDevices);
        }
        
        // 添加连接的逻辑节点的物理设备
        Set<String> connectedLogicalNodes = topologyAnalyzer.getConnectedLogicalNodes(edgeNode, edgeMapping);
        for (String logicalNode : connectedLogicalNodes) {
            Set<String> physicalDevices = topologyAnalyzer.getAllPhysicalDevicesForLogicalNode(logicalNode);
            requiredDevices.addAll(physicalDevices);
        }
        
        return requiredDevices;
    }
    
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}

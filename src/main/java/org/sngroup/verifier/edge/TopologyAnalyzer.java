// TopologyAnalyzer.java - 拓扑分析器
package org.sngroup.verifier.edge;

import java.io.*;
import java.util.*;

public class TopologyAnalyzer {
    private final Map<String, Set<String>> adjacencyMap = new HashMap<>();
    
    public void parseTopology(String topologyFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(topologyFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String from = parts[0];
                    String to = parts[2];
                    
                    adjacencyMap.computeIfAbsent(from, k -> new HashSet<>()).add(to);
                    adjacencyMap.computeIfAbsent(to, k -> new HashSet<>()).add(from);
                }
            }
        }
    }
    
    public Set<String> getConnectedLogicalNodes(String edgeNode, Map<String, List<String>> edgeMapping) {
        Set<String> connectedNodes = new HashSet<>();
        List<String> physicalDevices = edgeMapping.get(edgeNode);
        
        if (physicalDevices != null) {
            for (String physicalDevice : physicalDevices) {
                Set<String> neighbors = adjacencyMap.get(physicalDevice);
                if (neighbors != null) {
                    for (String neighbor : neighbors) {
                        // 找到连接的逻辑节点
                        String logicalNode = extractLogicalNodeName(neighbor);
                        if (!logicalNode.equals(edgeNode)) {
                            connectedNodes.add(logicalNode);
                        }
                    }
                }
            }
        }
        return connectedNodes;
    }
    
    private String extractLogicalNodeName(String physicalDevice) {
        // 假设物理设备名格式为 logicalNodeNameXXX
        // 需要根据实际命名规则调整
        return physicalDevice.replaceAll("p\\d+$", "");
    }
    
    public Set<String> getAllPhysicalDevicesForLogicalNode(String logicalNode) {
        Set<String> physicalDevices = new HashSet<>();
        for (String device : adjacencyMap.keySet()) {
            if (device.startsWith(logicalNode)) {
                physicalDevices.add(device);
            }
        }
        return physicalDevices;
    }
}
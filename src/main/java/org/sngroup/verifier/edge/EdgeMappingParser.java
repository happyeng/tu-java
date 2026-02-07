// EdgeMappingParser.java - 解析EDGE映射文件
package org.sngroup.verifier.edge;

import java.io.*;
import java.util.*;

public class EdgeMappingParser {
    public static Map<String, List<String>> parseEdgeMapping(String filePath) throws IOException {
        Map<String, List<String>> edgeToPhysical = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("-");
                if (parts.length >= 2) {
                    String edgeNode = parts[0];
                    List<String> physicalDevices = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) {
                        physicalDevices.add(parts[i]);
                    }
                    edgeToPhysical.put(edgeNode, physicalDevices);
                }
            }
        }
        return edgeToPhysical;
    }
}
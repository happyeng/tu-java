// EdgeVerificationMain.java - 主程序入口
package org.sngroup.verifier.edge;

import org.sngroup.verifier.BDDEngine;
import org.sngroup.verifier.TopoNet;
import java.util.List;

public class EdgeVerificationMain {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: EdgeVerificationMain <edgeMappingFile> <physicalTopologyFile> <forwardingTableDir>");
            System.exit(1);
        }

        String edgeMappingFile = args[0];
        String physicalTopologyFile = args[1];
        String forwardingTableDir = args[2];

        // 创建TopoNet验证引擎，直接设置bddEngine字段
        TopoNet topoNet = new TopoNet(null, 0);
        // TopoNet继承自DVNet，bddEngine是public字段，可直接赋值
        topoNet.bddEngine = new BDDEngine();

        // 创建验证器
        EdgeInternalVerifier verifier = new EdgeInternalVerifier(topoNet, forwardingTableDir);

        try {
            System.out.println("Starting EDGE internal verification...");
            long startTime = System.currentTimeMillis();

            List<EdgeVerificationResult> results = verifier.verifyAllEdges(edgeMappingFile, physicalTopologyFile);

            long endTime = System.currentTimeMillis();

            // 输出结果
            System.out.println("\n=== Verification Results ===");
            for (EdgeVerificationResult result : results) {
                System.out.println(result);
            }

            System.out.printf("\nTotal verification time: %d ms\n", endTime - startTime);

            // 统计
            long reachableCount = results.stream().mapToLong(r -> r.isReachable() ? 1 : 0).sum();
            System.out.printf("Reachable EDGE nodes: %d/%d\n", reachableCount, results.size());

        } catch (Exception e) {
            System.err.println("Verification failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            verifier.shutdown();
            // 移除cleanup调用，避免编译错误
        }
    }
}
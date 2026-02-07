// EdgeEvaluator.java - EDGE验证评估器
package org.sngroup.test.evaluator;

import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentGroup;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import org.sngroup.Configuration;
import org.sngroup.test.runner.Runner;
import org.sngroup.verifier.edge.EdgeInternalVerifier;
import org.sngroup.verifier.edge.EdgeVerificationResult;
import org.sngroup.verifier.TopoNet;

import java.util.List;

public class EdgeEvaluator extends Evaluator {
    private String edgeMappingFile;
    private String physicalTopologyFile;
    private String forwardingTableDir;
    
    public EdgeEvaluator() {
        super();
    }
    
    public static void setParser(ArgumentParser parser) {
        // 继承父类设置
        Evaluator.setParser(parser);
        
        // 添加EDGE特有参数
        ArgumentGroup edgeGroup = parser.addArgumentGroup("edge verification");
        edgeGroup.addArgument("--edge_mapping").type(String.class)
                .setDefault("edgeMapping.txt")
                .help("EDGE mapping file path");
        edgeGroup.addArgument("--physical_topology").type(String.class)
                .setDefault("physicalTopology.txt") 
                .help("Physical topology file path");
        edgeGroup.addArgument("--forwarding_table_dir").type(String.class)
                .setDefault("rule/")
                .help("Forwarding table directory");
    }
    
    @Override
    public Evaluator setConfiguration(Namespace namespace) {
        super.setConfiguration(namespace);
        
        // 设置EDGE验证特有配置
        this.edgeMappingFile = namespace.getString("edge_mapping");
        this.physicalTopologyFile = namespace.getString("physical_topology");
        this.forwardingTableDir = namespace.getString("forwarding_table_dir");
        
        return this;
    }
    
    @Override
    public void start(Runner runner) {
        System.out.println("Starting EDGE internal verification...");
        
        try {
            // 创建TopoNet验证引擎
            TopoNet topoNet = new TopoNet(null, 0);
            
            // 创建EDGE验证器
            EdgeInternalVerifier verifier = new EdgeInternalVerifier(topoNet, forwardingTableDir);
            
            long startTime = System.currentTimeMillis();
            
            // 执行验证
            List<EdgeVerificationResult> results = verifier.verifyAllEdges(
                edgeMappingFile, physicalTopologyFile);
            
            long endTime = System.currentTimeMillis();
            
            // 输出结果
            if (Configuration.getConfiguration().isShowResult()) {
                System.out.println("\n=== EDGE Internal Verification Results ===");
                for (EdgeVerificationResult result : results) {
                    System.out.println(result);
                }
                
                System.out.printf("\nTotal verification time: %d ms\n", endTime - startTime);
                
                // 统计
                long reachableCount = results.stream().mapToLong(r -> r.isReachable() ? 1 : 0).sum();
                System.out.printf("Reachable EDGE nodes: %d/%d\n", reachableCount, results.size());
            }
            
            verifier.shutdown();
            
        } catch (Exception e) {
            System.err.println("EDGE verification failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
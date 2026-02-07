// EdgeVerificationResult.java - 验证结果
package org.sngroup.verifier.edge;

public class EdgeVerificationResult {
    private final String edgeNode;
    private final boolean isReachable;
    private final int deviceCount;
    private final long verificationTime;
    
    public EdgeVerificationResult(String edgeNode, boolean isReachable, int deviceCount) {
        this.edgeNode = edgeNode;
        this.isReachable = isReachable;
        this.deviceCount = deviceCount;
        this.verificationTime = System.currentTimeMillis();
    }
    
    // Getters
    public String getEdgeNode() { return edgeNode; }
    public boolean isReachable() { return isReachable; }
    public int getDeviceCount() { return deviceCount; }
    public long getVerificationTime() { return verificationTime; }
    
    @Override
    public String toString() {
        return String.format("Edge %s: %s (%d devices)", 
            edgeNode, isReachable ? "REACHABLE" : "NOT_REACHABLE", deviceCount);
    }
}

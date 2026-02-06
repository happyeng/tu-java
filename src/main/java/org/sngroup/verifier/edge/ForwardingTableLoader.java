// ForwardingTableLoader.java - 转发表加载器
package org.sngroup.verifier.edge;

import org.sngroup.verifier.Device;
import org.sngroup.verifier.DVNet;
import org.sngroup.verifier.BDDEngine;
import org.sngroup.test.runner.TopoRunner;
import org.sngroup.util.ThreadPool;
import org.sngroup.util.Network;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ForwardingTableLoader {
    private final Map<String, Device> deviceCache = new ConcurrentHashMap<>();
    private final String forwardingTableDir;

    public ForwardingTableLoader(String forwardingTableDir) {
        this.forwardingTableDir = forwardingTableDir;
    }

    public synchronized Device loadDevice(String deviceName) {
        if (deviceCache.containsKey(deviceName)) {
            return deviceCache.get(deviceName);
        }

        // 使用TopoRunner的devices如果存在，否则创建新的
        Device device = TopoRunner.devices.get(deviceName);
        if (device == null) {
            // 创建临时的网络和线程池用于设备初始化
            Network network = new Network();
            ThreadPool threadPool = ThreadPool.FixedThreadPool(4);
            TopoRunner runner = new TopoRunner();

            device = new Device(deviceName, network, runner, threadPool);
        }

        String ruleFile = forwardingTableDir + "/" + deviceName;

        // 使用原有的规则读取方法
        device.readOnlyRulesFile(ruleFile);

        deviceCache.put(deviceName, device);
        return device;
    }

    public void loadAndEncodeDevices(Set<String> deviceNames, DVNet dvNet) {
        deviceNames.parallelStream().forEach(deviceName -> {
            Device device = loadDevice(deviceName);
            // 使用原有的规则编码方法
            device.encodeRuleToLecFromScratchToFinish(dvNet);
        });
    }

    public Map<String, Device> getLoadedDevices() {
        return new HashMap<>(deviceCache);
    }
}

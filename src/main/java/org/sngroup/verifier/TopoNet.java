/*
 * This program is free software: you can redistribute it and/or modify it under the terms of
 *  the GNU General Public License as published by the Free Software Foundation, either
 *   version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *   PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this
 *  program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Authors: Chenyang Huang (Xiamen University) <xmuhcy@stu.xmu.edu.cn>
 *          Qiao Xiang     (Xiamen University) <xiangq27@gmail.com>
 *          Ridi Wen       (Xiamen University) <23020211153973@stu.xmu.edu.cn>
 *          Yuxin Wang     (Xiamen University) <yuxxinwang@gmail.com>
 */

package org.sngroup.verifier;

import org.apache.commons.lang3.ObjectUtils;
import org.sngroup.test.runner.Runner;
import org.sngroup.test.runner.TopoRunner;
import org.sngroup.util.*;

import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

public class TopoNet extends DVNet {

    static public Set<Device> edgeDevices = new HashSet<>();

    static public Map<String, HashSet<DevicePort>> devicePortsTopo;

    public int topoCnt;

    public Set<Node> srcNodes;
    public Device dstDevice;

    public static Network network;

    public Invariant invariant;

    public TopoNet(Device dstDevice, int topoCnt) {
        super();
        init();
        this.dstDevice = dstDevice;
        this.topoCnt = topoCnt;
    }

    public void setInvariant(String packetSpace, String match, String path) {
        this.invariant = new Invariant(packetSpace, match, path);
    }

    public void nodeCalIndegree() {
        for (Node node : nodesTable.values()) {
            node.topoNetStart();
        }
    }

    public static void transformDevicePorts(Map<String, Map<String, Set<DevicePort>>> devicePortsOriginal) {
        // 双向连接
        Map<String, HashSet<DevicePort>> devicePortsNew = new HashMap<>();
        for (Map.Entry<String, Map<String, Set<DevicePort>>> entry : devicePortsOriginal.entrySet()) {
            String key = entry.getKey();
            Map<String, Set<DevicePort>> innerMap = entry.getValue();
            HashSet<DevicePort> connectedPorts = new HashSet<>();
            for (Set<DevicePort> portSet : innerMap.values()) {
                for(DevicePort port : portSet){
                    if(!connectedPorts.contains(port)) connectedPorts.add(port);
                }
            }
            devicePortsNew.put(key, connectedPorts);
        }
        devicePortsTopo = devicePortsNew;
    }

    public static void setNextTable() {
        for (Device device : TopoRunner.devices.values()) {
            String deviceName = device.name;
            if (!Node.nextTable.containsKey(deviceName))
                Node.nextTable.put(deviceName, new HashSet<>());
            HashSet<NodePointer> next = Node.nextTable.get(deviceName);
            if (network != null && network.devicePorts.get(device.name) != null) {
                for (Map.Entry<String, Set<DevicePort>> entry : network.devicePorts.get(device.name).entrySet()) {
                    for (DevicePort dp : entry.getValue()) {
                        next.add(new NodePointer(dp.getPortName(), -1));
                    }
                }
            }
        }
    }

    public Node getDstNodeByName(String deviceName) {
        return this.nodesTable.get(deviceName);
    }

    public Boolean getAndSetBddEngine(LinkedBlockingDeque<BDDEngine> sharedQue) {
        boolean reused = false;

        // ===== 关键修复6: 添加队列空值检查 =====
        if (sharedQue == null) {
            System.err.println("[TopoNet] 警告: sharedQue为null，无法获取BDD引擎");
            return false;
        }
        // ===== 修复6结束 =====

        synchronized (sharedQue) {
            if (sharedQue.size() != 0) {
                try {
                    this.bddEngine = sharedQue.take();
                    reused = true;
                    System.out.println("[TopoNet] 从池中获取BDD引擎，剩余: " + sharedQue.size());
                } catch (InterruptedException e) {
                    System.err.println("[TopoNet] 获取BDD引擎被中断: " + e.getMessage());
                    this.bddEngine = null;
                }
            } else {
                // ===== 关键修复7: 队列为空时不创建引擎，由调用方处理 =====
                System.out.println("[TopoNet] BDD引擎池已空，需要调用方创建新引擎");
                this.bddEngine = null;
                // ===== 修复7结束 =====
            }
        }

        return reused;
    }


    public void setNodeBdd() {
        for (Node node : nodesTable.values()) {
            node.setBdd(this.bddEngine);
        }
    }

    /**
     * 修复后的startCount方法，增加空值检查和错误处理
     */
    public void startCount(LinkedBlockingDeque<BDDEngine> sharedQue) {
        try {
            // 检查目标节点是否存在
            Node dstNode = this.getDstNode();
            if (dstNode == null) {
                System.err.println("错误：TopoNet [" + dstDevice.name + "] 的目标节点为空，无法开始验证");
                return;
            }

            // 检查源节点集合是否初始化
            if (srcNodes == null) {
                System.err.println("错误：TopoNet [" + dstDevice.name + "] 的源节点集合未初始化");
                srcNodes = new HashSet<>(); // 创建空集合，避免后续空指针
            }

            System.out.println("开始验证 TopoNet [" + dstDevice.name + "]，目标节点: " + dstNode.device.name);

            Context c = new Context();
            c.topoId = this.topoCnt;

            // 使用 BFS 进行验证
            dstNode.bfsByIteration(c);

            // 显示所有源节点的结果
            System.out.println("TopoNet [" + dstDevice.name + "] 验证完成，处理 " + srcNodes.size() + " 个源节点结果");
            for (Node node : srcNodes) {
                try {
                    node.showResult();
                } catch (Exception e) {
                    System.err.println("显示节点 [" + node.device.name + "] 结果时发生错误: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("TopoNet [" + dstDevice.name + "] 验证过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 确保在任何情况下都归还BDD引擎
            synchronized (sharedQue) {
                try {
                    BDDEngine engine = this.getBddEngine();
                    if (engine != null) {
                        sharedQue.put(engine);
                        System.out.println("TopoNet [" + dstDevice.name + "] BDD引擎已归还到池中");
                    }
                } catch (Exception e) {
                    System.err.println("归还BDD引擎时发生错误: " + e.getMessage());
                }
            }
        }
    }

    public void init() {
        srcNodes = new HashSet<>();
        this.nodesTable = new HashMap<>();
    }
}
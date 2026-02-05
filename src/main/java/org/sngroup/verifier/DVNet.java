package org.sngroup.verifier;

import org.sngroup.util.*;

import org.sngroup.util.CopyHelper.*;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang3.SerializationUtils;

public class DVNet {
    public int netIndex;

//    public List<Node> nodes;

    public Map<String, Node> nodesTable;


    public BDDEngine bddEngine;

    private Node dstNode;

    public int packetSpace;

    // 先分开管理, 验证并行度, 之后再行合并
    // device rule hit
    public Map<String, Map<Rule, Integer>> deviceRuleHit;
    public Map<String, Map<Rule, Integer>> deviceRuleMatch;
    public Map<String, Map<ForwardAction, Integer>> devicePortPredicate;

    public Map<String, HashSet<Lec>> deviceLecs;

    static public Map<String, Integer> devicePacketSpace;
    public Map<String, Map<Rule, List<Integer>>> deviceRuleBlacklist;
    
    // 新增：空间解析状态管理
    private boolean spaceParsed = false;
    
    // 新增：并发控制锁
    private final ReentrantReadWriteLock spaceLock = new ReentrantReadWriteLock();
    private final Object deviceOperationLock = new Object();

    public DVNet(){
        init();
    }

    public DVNet(int netIndex){
        init();
        this.bddEngine = new BDDEngine();
        this.netIndex = netIndex;
    }

    public DVNet(int netIndex, BDDEngine srcBdd){
        init();
        this.bddEngine = srcBdd;
        this.netIndex = netIndex;
    }
    
    // 新增：检查空间是否已解析
    public boolean isSpaceParsed() {
        spaceLock.readLock().lock();
        try {
            return spaceParsed;
        } finally {
            spaceLock.readLock().unlock();
        }
    }
    
    // 新增：设置空间已解析标记
    public void setSpaceParsed(boolean parsed) {
        spaceLock.writeLock().lock();
        try {
            this.spaceParsed = parsed;
        } finally {
            spaceLock.writeLock().unlock();
        }
    }

    public void putDeviceIfAbsent(String name){
        synchronized (deviceOperationLock) {
            if(!deviceRuleHit.containsKey(name)){
                this.deviceRuleHit.put(name, new HashMap<>());
                this.deviceRuleMatch.put(name, new HashMap<>());
                this.devicePortPredicate.put(name, new HashMap<>());
                this.deviceLecs.put(name, new HashSet<>());
                this.deviceRuleBlacklist.put(name, new HashMap<>());
            }
        }
    }

    // put Attribute
    // get Attribute
    public void putDeviceRuleHit(String deviceName, Rule rule, int hit){
        synchronized (deviceOperationLock) {
            deviceRuleHit.get(deviceName).put(rule, hit);
        }
    }

    public void putDeviceRuleMatch(String deviceName, Rule rule, int match){
        synchronized (deviceOperationLock) {
            deviceRuleMatch.get(deviceName).put(rule, match);
        }
    }

    // get Attribute
    public int getDeviceRuleHit(String deviceName, Rule rule){
        synchronized (deviceOperationLock) {
            Map<Rule, Integer> deviceHits = deviceRuleHit.get(deviceName);
            if (deviceHits == null) {
                return 0;
            }
            Integer hit = deviceHits.get(rule);
            return hit != null ? hit : 0;
        }
    }

    public int getDeviceRuleMatch(String deviceName, Rule rule){
        synchronized (deviceOperationLock) {
            Map<Rule, Integer> deviceMatches = deviceRuleMatch.get(deviceName);
            if (deviceMatches == null) {
                return 0;
            }
            Integer match = deviceMatches.get(rule);
            return match != null ? match : 0;
        }
    }

    public HashSet<Lec> getDeviceLecs(String deviceName){
        synchronized (deviceOperationLock) {
            HashSet<Lec> lecs = deviceLecs.get(deviceName);
            return lecs != null ? new HashSet<>(lecs) : new HashSet<>(); // 返回副本避免并发修改
        }
    }

    public void setTrieAndBlacklist(String deviceName, List<Rule> rules){
        synchronized (deviceOperationLock) {
            try {
                if (rules == null || rules.isEmpty()) {
                    return;
                }
                
                // 创建rules的副本以避免并发修改
                List<Rule> rulesCopy = new ArrayList<>(rules);
                
                Trie trie = new Trie();
                trie.addAndGetAllOverlappingAndAddToBlacklist(rulesCopy, this, deviceName);
                trie = null;
            } catch (Exception e) {
                System.err.println("设置Trie和黑名单失败 " + deviceName + ": " + e.getMessage());
            }
        }
    }

    // public void setTrieAndBlacklistIPV6(String deviceName, List<RuleIPV6> rulesIpv6s){
    //     Trie trie = new Trie();
    //     trie.addAndGetAllOverlappingAndAddToBlacklistIPV6(rulesIpv6s, this, deviceName);
    //     trie = null;
    // }

    public void putDeviceRuleBlacklist(String deviceName, Rule rule, Rule blackRule){
        synchronized (deviceOperationLock) {
            if(!deviceRuleBlacklist.get(deviceName).containsKey(rule)){
                this.deviceRuleBlacklist.get(deviceName).put(rule, new ArrayList<>());
            }
            int black = this.deviceRuleMatch.get(deviceName).get(blackRule);
            this.deviceRuleBlacklist.get(deviceName).get(rule).add(black);
        }
    }

    public List<Integer> getDeviceRuleBlacklist(String deviceName, Rule rule){
        synchronized (deviceOperationLock) {
            if(!deviceRuleBlacklist.get(deviceName).containsKey(rule)){
                this.deviceRuleBlacklist.get(deviceName).put(rule, new ArrayList<>());
            }
            List<Integer> blacklist = deviceRuleBlacklist.get(deviceName).get(rule);
            return blacklist != null ? new ArrayList<>(blacklist) : new ArrayList<>(); // 返回副本
        }
    }

    public void srcDvNetParseAllSpace(Map<String, List<IPPrefix>> spaces){
        spaceLock.writeLock().lock();
        try {
            // 检查是否已经解析过
            if (spaceParsed) {
                return;
            }
            
            if (spaces == null || spaces.isEmpty()) {
                System.err.println("空间数据为空，跳过解析");
                return;
            }
            
            BDDEngine bddEngine = this.getBddEngine();
            if (bddEngine == null) {
                System.err.println("BDD引擎为null，无法解析空间");
                return;
            }
            
            synchronized (deviceOperationLock) {
                for(Map.Entry<String, List<IPPrefix>> entry : spaces.entrySet()) {
                    try {
                        String dstDevice = entry.getKey();
                        List<IPPrefix> ipPrefixList = entry.getValue();
                        
                        if (ipPrefixList != null && !ipPrefixList.isEmpty()) {
                            int s = bddEngine.encodeDstIPPrefixList(ipPrefixList);
                            devicePacketSpace.put(dstDevice, s);
                        }
                    } catch (Exception e) {
                        System.err.println("解析设备空间失败 " + entry.getKey() + ": " + e.getMessage());
                    }
                }
            }
            
            // 设置已解析标记
            spaceParsed = true;
            System.out.println("IPv4空间解析完成，设备数量: " + spaces.size());
        } finally {
            spaceLock.writeLock().unlock();
        }
    }

    public void srcDvNetParseAllSpaceIPV6(Map<String, List<IPPrefixIPV6>> spacesIPV6){
        spaceLock.writeLock().lock();
        try {
            // 检查是否已经解析过
            if (spaceParsed) {
                return;
            }
            
            if (spacesIPV6 == null || spacesIPV6.isEmpty()) {
                System.err.println("IPv6空间数据为空，跳过解析");
                return;
            }
            
            BDDEngine bddEngine = this.getBddEngine();
            if (bddEngine == null) {
                System.err.println("BDD引擎为null，无法解析IPv6空间");
                return;
            }
            
            synchronized (deviceOperationLock) {
                for(Map.Entry<String, List<IPPrefixIPV6>> entry : spacesIPV6.entrySet()) {
                    try {
                        String dstDevice = entry.getKey();
                        List<IPPrefixIPV6> ipPrefixList = entry.getValue();
                        
                        if (ipPrefixList != null && !ipPrefixList.isEmpty()) {
                            int s = bddEngine.encodeDstIPPrefixListIPV6(ipPrefixList);
                            devicePacketSpace.put(dstDevice, s);
                        }
                    } catch (Exception e) {
                        System.err.println("解析设备IPv6空间失败 " + entry.getKey() + ": " + e.getMessage());
                    }
                }
            }
            
            // 设置已解析标记
            spaceParsed = true;
            System.out.println("IPv6空间解析完成，设备数量: " + spacesIPV6.size());
        } finally {
            spaceLock.writeLock().unlock();
        }
    }

    public void copyBdd(BDDEngine srcBdd, String copyType) throws Exception {
        synchronized (deviceOperationLock) {
            BDDEngine bddCopy = null;
            if(Objects.equals(copyType, "Reflect")){
                ReflectDeepCopy copyHelper = new ReflectDeepCopy();
                bddCopy = (BDDEngine) copyHelper.deepCopy(srcBdd);
            }
    //        else if(Objects.equals(copyType, "FST")){
    //            FSTDeepCopy copyHelper = new FSTDeepCopy();
    //            bddCopy = copyHelper.deepCopy(srcBdd);
    //        }
    //        else if(Objects.equals(copyType, "Kryo")){
    //            KryoDeepCopy copyHelper = new KryoDeepCopy();
    //            copyHelper.kryoRegister( );
    //            bddCopy = copyHelper.deepCopy(srcBdd);
    //        }
    //        else if(Objects.equals(copyType, "Apache")){  // default to Apache
    //            bddCopy = SerializationUtils.clone(srcBdd);
    //        }
            this.bddEngine = bddCopy;
            for(Node node : nodesTable.values()){
                assert this.bddEngine != null;
                node.setBdd(this.bddEngine);
            }
        }
    }

    public BDDEngine getBddEngine(){
        synchronized (deviceOperationLock) {
            return this.bddEngine;
        }
    }

    public void setPacketSpace(int s) {
        synchronized (deviceOperationLock) {
            this.packetSpace = s;
        }
    }

    public void setDstNode(Node node){
        synchronized (deviceOperationLock) {
            this.dstNode = node;
        }
    }

    public Node getDstNode() {
        synchronized (deviceOperationLock) {
            return this.dstNode;
        }
    }

    public void init(){
        synchronized (deviceOperationLock) {
            this.deviceRuleHit = new HashMap<>();
            this.deviceRuleMatch = new HashMap<>();
            this.devicePortPredicate = new HashMap<>();
            this.deviceLecs = new HashMap<>();
            this.deviceRuleBlacklist = new HashMap<>();
            if (devicePacketSpace == null) {
                devicePacketSpace = new HashMap<>();
            }
        }
    }

}
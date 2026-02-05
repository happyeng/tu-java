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

//import org.sngroup.test.runner.ThreadRunner;z
import jdd.bdd.BDD;
import org.sngroup.test.runner.TopoRunner;
import org.sngroup.test.runner.Runner;
import org.sngroup.util.*;

import java.io.*;
//import java.lang.invoke.DelegatingMethodHandle$Holder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.sngroup.verifier.npbdd.BDDPredicateCache;
import org.sngroup.verifier.npbdd.L2CacheKey;


public class Device {

    public final String name;
    protected Network network2;

    public BDDEngine bddEngine;

    protected ThreadPool threadPool;

    public List<Rule> rules;

    public List<RuleIPV6> rulesIPV6;

    public static Map<String, HashSet<Lec>> globalLecs;

    public static Map<String, List<IPPrefix>> spaces;

    public static Map<String, List<IPPrefixIPV6>> spacesIPV6;

    private final Runner runner;
    
    // 新增：批次验证相关属性
    private boolean rulesLoaded = false;
    private boolean bddTransformed = false;
    private static boolean spaceLoaded = false;
    
    // 新增：设备级别的锁，用于保护rules的并发访问
    public final Object rulesLock = new Object(); // 改为public，供TopoRunner使用
    private final Object rulesIPV6Lock = new Object();

    public Device(String name, Network network, Runner runner, ThreadPool tp) {
        this.name = name;
        this.network2 = network;
        this.runner = runner;
        init();
        this.threadPool = tp;
    }
    
    // 新增：检查规则是否已加载
    public boolean isRulesLoaded() {
        synchronized (rulesLock) {
            return rulesLoaded;
        }
    }
    
    // 新增：设置规则已加载标记
    public void setRulesLoaded(boolean loaded) {
        synchronized (rulesLock) {
            this.rulesLoaded = loaded;
        }
    }
    
    // 新增：检查BDD是否已转换
    public boolean isBddTransformed() {
        synchronized (rulesLock) {
            return bddTransformed;
        }
    }
    
    // 新增：设置BDD已转换标记
    public void setBddTransformed(boolean transformed) {
        synchronized (rulesLock) {
            this.bddTransformed = transformed;
        }
    }
    
    // 新增：检查空间是否已加载
    public static boolean isSpaceLoaded() {
        return spaceLoaded;
    }
    
    // 新增：设置空间已加载标记
    public static void setSpaceLoaded(boolean loaded) {
        spaceLoaded = loaded;
    }
    
    // 新增：清空规则以释放内存
    public void clearRules() {
        synchronized (rulesLock) {
            synchronized (rulesIPV6Lock) {
                if (rules != null) {
                    rules.clear();
                }
                if (rulesIPV6 != null) {
                    rulesIPV6.clear();
                }
                rulesLoaded = false;
                bddTransformed = false;
                
                // 清空设备相关的全局LECs
                if (globalLecs != null && globalLecs.containsKey(name)) {
                    globalLecs.get(name).clear();
                }
                
                // 建议垃圾回收
                System.gc();
            }
        }
    }

    public void readOnlyRulesFile(String filename) {
        synchronized (rulesLock) {
            try {
                // 安全检查filename
                if (filename == null) {
                    System.err.println("设备 " + name + " 的规则文件名为null");
                    return;
                }
                
                File file = new File(filename);
                if (!file.exists()) {
                    System.err.println("设备 " + name + " 的规则文件不存在: " + filename);
                    return;
                }
                
                // 确保rules列表已初始化
                if (rules == null) {
                    rules = new ArrayList<>();
                } else {
                    rules.clear(); // 清空现有规则
                }
                
                InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String line;

                while ((line = br.readLine()) != null) {
                    try {
                        String[] token = line.split("\\s+");
                        if (token[0].equals("fw") || token[0].equals("ALL") || token[0].equals("ANY") || token[0].equals("any")) {
                            Collection<String> forward = new HashSet<>(); // 去掉端口名中"."后的字符
                            for (int i = 3; i < token.length; i++) {
                                if (token[i] != null && !token[i].trim().isEmpty()) {
                                    forward.add(token[i]);
                                }
                            }
                            
                            if (!forward.isEmpty()) {
                                long ip = Long.parseLong(token[1]);
                                int prefix = Integer.parseInt(token[2]);
                                ForwardType ft = token[0].equals("ANY") || token[0].equals("any") ? ForwardType.ANY : ForwardType.ALL;
                                Rule newRule = new Rule(ip, prefix, forward, ft);
                                this.rules.add(newRule);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("解析规则行失败: " + line + ", 错误: " + e.getMessage());
                        // 继续处理下一行
                    }
                }
                
                br.close();

            } catch (IOException e) {
                System.err.println("读取规则文件失败 " + filename + ": " + e.getMessage());
                e.printStackTrace();
            }
            
            // 新增：设置规则已加载标记
            setRulesLoaded(true);
            System.out.println("设备 " + name + " 加载了 " + (rules != null ? rules.size() : 0) + " 条规则");
        }
    }

    public void readOnlyRulesFileIPV4_S(String filename) {
        synchronized (rulesIPV6Lock) {
            try {
                // 安全检查filename
                if (filename == null) {
                    System.err.println("设备 " + name + " 的IPv4_S规则文件名为null");
                    return;
                }
                
                File file = new File(filename);
                if (!file.exists()) {
                    System.err.println("设备 " + name + " 的IPv4_S规则文件不存在: " + filename);
                    return;
                }
                
                // 确保rulesIPV6列表已初始化
                if (rulesIPV6 == null) {
                    rulesIPV6 = new ArrayList<>();
                } else {
                    rulesIPV6.clear(); // 清空现有规则
                }
                
                InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String line;

                while ((line = br.readLine()) != null) {
                    try {
                        String[] token = line.split("\\s+");
                        if (token[0].equals("fw") || token[0].equals("ALL") || token[0].equals("ANY") || token[0].equals("any")) {
                            Collection<String> forward = new HashSet<>(); // 去掉端口名中"."后的字符
                            for (int i = 3; i < token.length; i++) {
                                if (token[i] != null && !token[i].trim().isEmpty()) {
                                    forward.add(token[i].split("\\.", 2)[0]);
                                }
                            }
                            
                            if (!forward.isEmpty()) {
                                String ip = token[1];
                                int prefix = Integer.parseInt(token[2]);
                                ForwardType ft = (token[0].equals("ANY") || token[0].equals("any"))? ForwardType.ANY : ForwardType.ALL;
                                RuleIPV6 newRule = new RuleIPV6(ip, prefix, forward, ft);
                                this.rulesIPV6.add(newRule);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("解析IPv4_S规则行失败: " + line + ", 错误: " + e.getMessage());
                        // 继续处理下一行
                    }
                }
                
                br.close();

            } catch (IOException e) {
                System.err.println("读取IPv4_S规则文件失败 " + filename + ": " + e.getMessage());
                e.printStackTrace();
            }
            // 加入默认路由
            this.rulesIPV6.add(new RuleIPV6("0.0.0.0", 0, ForwardAction.getNullAction()));
            
            // 新增：设置规则已加载标记
            setRulesLoaded(true);
            System.out.println("设备 " + name + " 加载了 " + (rulesIPV6 != null ? rulesIPV6.size() : 0) + " 条IPv4_S规则");
        }
    }

    public void readOnlyRulesFileIPV6(String filename) {
        synchronized (rulesIPV6Lock) {
            try {
                // 安全检查filename
                if (filename == null) {
                    System.err.println("设备 " + name + " 的IPv6规则文件名为null");
                    return;
                }
                
                File file = new File(filename);
                if (!file.exists()) {
                    System.err.println("设备 " + name + " 的IPv6规则文件不存在: " + filename);
                    return;
                }
                
                // 确保rulesIPV6列表已初始化
                if (rulesIPV6 == null) {
                    rulesIPV6 = new ArrayList<>();
                } else {
                    rulesIPV6.clear(); // 清空现有规则
                }
                
                InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String line;

                while ((line = br.readLine()) != null) {
                    try {
                        String[] token = line.split("\\s+");
                        if (token[0].equals("fw") || token[0].equals("ALL") || token[0].equals("ANY") || token[0].equals("any")) {
                            Collection<String> forward = new HashSet<>(); // 去掉端口名中"."后的字符
                            for (int i = 3; i < token.length; i++) {
                                if (token[i] != null && !token[i].trim().isEmpty()) {
                                    forward.add(token[i].split("\\.", 2)[0]);
                                }
                            }
                            
                            if (!forward.isEmpty()) {
                                String ip = token[1];
                                int prefix = Integer.parseInt(token[2]);
                                ForwardType ft = (token[0].equals("ANY") || token[0].equals("any"))? ForwardType.ANY : ForwardType.ALL;
                                RuleIPV6 newRule = new RuleIPV6(ip, prefix, forward, ft);
                                this.rulesIPV6.add(newRule);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("解析IPv6规则行失败: " + line + ", 错误: " + e.getMessage());
                        // 继续处理下一行
                    }
                }
                
                br.close();

            } catch (IOException e) {
                System.err.println("读取IPv6规则文件失败 " + filename + ": " + e.getMessage());
                e.printStackTrace();
            }
            // 加入默认路由
            this.rulesIPV6.add(new RuleIPV6("::", 0, ForwardAction.getNullAction()));
            
            // 新增：设置规则已加载标记
            setRulesLoaded(true);
            System.out.println("设备 " + name + " 加载了 " + (rulesIPV6 != null ? rulesIPV6.size() : 0) + " 条IPv6规则");
        }
    }

    public static void readOnlySpaceFileIPV6(String filename) {
        // 检查是否已经加载过
        if (spaceLoaded && spacesIPV6 != null) {
            return;
        }
        
        synchronized (Device.class) {
            // 双重检查
            if (spaceLoaded && spacesIPV6 != null) {
                return;
            }
            
            Map<String, List<IPPrefixIPV6>> spacesIPV6 = new HashMap<>();
            try {
                InputStreamReader isr = new InputStreamReader(Files.newInputStream(Paths.get(filename)),
                        StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String line;

                while ((line = br.readLine()) != null) {
                    if (line.startsWith("["))
                        continue;
                    String[] token = line.split("\\s+");
                    String device = token[0];
                    String ip = token[1];
                    int prefix = Integer.parseInt(token[2]);
                    IPPrefixIPV6 space = new IPPrefixIPV6(ip, prefix);
                    spacesIPV6.putIfAbsent(device, new LinkedList<>());
                    spacesIPV6.get(device).add(space);
                }
                br.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Device.spacesIPV6 = spacesIPV6;
            
            // 新增：设置空间已加载标记
            setSpaceLoaded(true);
        }
    }

    public void encodeDeviceRule(DVNet dvNet) {
        synchronized (rulesLock) {
            long timePoint = System.currentTimeMillis();
            dvNet.putDeviceIfAbsent(name);
            dvNet.setTrieAndBlacklist(name, rules);
            BDDEngine tmpBddEngine = dvNet.getBddEngine();
            for (Rule rule : rules) {
                int tmpHit = tmpBddEngine.encodeIpWithoutBlacklist(dvNet.getDeviceRuleMatch(name, rule),
                        dvNet.getDeviceRuleBlacklist(name, rule));
                dvNet.putDeviceRuleHit(name, rule, tmpHit);
            }
            long timePoint1 = System.currentTimeMillis();
            // System.out.println("每个Device前缀匹配花费的时间" + (timePoint1 - timePoint) + "ms");
            encodeRuleToLec(dvNet);
            long timePoint2 = System.currentTimeMillis();
            // System.out.println("每个Device转化lec所花费的时间" + (timePoint2 - timePoint1) + "ms");
        }
    }

    public void encodeDeviceRuleIPV6(DVNet dvNet) {
        synchronized (rulesLock) {
            long timePoint = System.currentTimeMillis();
            dvNet.putDeviceIfAbsent(name);
            dvNet.setTrieAndBlacklist(name, rules);
            BDDEngine tmpBddEngine = dvNet.getBddEngine();
            for (Rule rule : rules) {
                int tmpHit = tmpBddEngine.encodeIpWithoutBlacklist(dvNet.getDeviceRuleMatch(name, rule),
                        dvNet.getDeviceRuleBlacklist(name, rule));
                dvNet.putDeviceRuleHit(name, rule, tmpHit);
            }
            long timePoint1 = System.currentTimeMillis();
            // System.out.println("每个Device前缀匹配花费的时间" + (timePoint1 - timePoint) + "ms");
            encodeRuleToLec(dvNet);
            long timePoint2 = System.currentTimeMillis();
            // System.out.println("每个Device转化lec所花费的时间" + (timePoint2 - timePoint1) + "ms");
        }
    }

    public static void readOnlySpaceFile(String filename) {
        // 检查是否已经加载过
        if (spaceLoaded && spaces != null) {
            return;
        }
        
        synchronized (Device.class) {
            // 双重检查
            if (spaceLoaded && spaces != null) {
                return;
            }
            
            Map<String, List<IPPrefix>> spaces = new HashMap<>();
            try {
                InputStreamReader isr = new InputStreamReader(Files.newInputStream(Paths.get(filename)),
                        StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String line;

                while ((line = br.readLine()) != null) {
                    if (line.startsWith("["))
                        continue;
                    String[] token = line.split("\\s+");
                    String device = token[0];
                    long ip = Long.parseLong(token[1]);
                    int prefix = Integer.parseInt(token[2]);
                    IPPrefix space = new IPPrefix(ip, prefix);
                    spaces.putIfAbsent(device, new LinkedList<>());
                    spaces.get(device).add(space);
                }
                br.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Device.spaces = spaces;
            
            // 新增：设置空间已加载标记
            setSpaceLoaded(true);
        }
    }

    public void encodeRuleToLec(DVNet dvNet) {
        synchronized (rulesLock) {
            TSBDD bdd = dvNet.getBddEngine().getBDD();
            Map<ForwardAction, Integer> portPredicate = dvNet.devicePortPredicate.get(name);
            // 按动作进行等价类的合并
            for (Rule rule : rules) {
                Collection<String> tmpPorts = rule.forwardAction.ports;
                ForwardType tmpForwardType = rule.forwardAction.forwardType;
                for(String tmpPort : tmpPorts){
                    ForwardAction tmpForwardAction = new ForwardAction(tmpForwardType, tmpPort) ;
                if (portPredicate.containsKey(tmpForwardAction)) {
                    int newPredicate = bdd.orTo(portPredicate.get(tmpForwardAction), dvNet.getDeviceRuleHit(name, rule));
                    portPredicate.put(tmpForwardAction, newPredicate);
                } else {
                    portPredicate.put(tmpForwardAction, bdd.ref(dvNet.getDeviceRuleHit(name, rule)));
                    // portPredicate.put(rule.forwardAction, dvNet.getDeviceRuleHit(name, rule));
                }
            }
            }
            HashSet<Lec> tmpLecs = new HashSet<>();
            for (Map.Entry<ForwardAction, Integer> kv : portPredicate.entrySet()) {
                tmpLecs.add(new Lec(kv.getKey(), kv.getValue()));
            }
            Device.globalLecs.put(name, tmpLecs);
        }
    }

    public void encodeRuleToLecFromScratchToFinish(DVNet dvNet) {
        // 检查设备是否有规则
        if (rules == null || rules.isEmpty()) {
            System.out.println("设备 [" + name + "] 没有转发规则，跳过BDD编码");
            Device.globalLecs.put(name, new HashSet<>());
            return;
        }

        dvNet.putDeviceIfAbsent(name);
        Collections.sort(rules, prefixLenComparator); // 优先级排序
        Map<ForwardAction, Integer> portPredicate = dvNet.devicePortPredicate.get(name);
        BDDEngine bdd = dvNet.getBddEngine();
        TSBDD tsbdd = bdd.getBDD();

        // 【修正】检查是否启用NP-BDD
        BDDPredicateCache cache = BDDPredicateCache.getInstance();
        boolean useCache = BDDEngine.isNPBDDEnabled();

        boolean isFirst = false;
        int allBdd = 0;
        int lastPrefixLen = 0;
        int ruleCnt = 0;

        for (Rule rule : rules) {
            ruleCnt++;

            // 步骤1: 规则编码（L2缓存检查）
            int tmpMatch;  // 注意：这里保持int类型，因为最终需要int

            if (useCache) {
                // 使用L2-encode缓存
                L2CacheKey l2EncodeKey = L2CacheKey.forEncodeRule(rule.ip, rule.prefixLen);
                Integer cachedMatch = cache.getL2Encode(l2EncodeKey);  // 【修正】使用Integer接收

                if (cachedMatch == null) {
                    // 缓存未命中，执行编码
                    tmpMatch = bdd.encodeDstIPPrefixWithCache(rule.ip, rule.prefixLen);
                    cache.putL2Encode(l2EncodeKey, tmpMatch);
                } else {
                    // 缓存命中
                    tmpMatch = cachedMatch.intValue();  // 【修正】转换为int
                }
            } else {
                // 不使用缓存，直接编码
                tmpMatch = bdd.encodeDstIPPrefix(rule.ip, rule.prefixLen);
            }

            int tmpHit = tmpMatch;

            // 步骤2: 计算有效hit空间（L2缓存检查）
            if (rule.prefixLen == lastPrefixLen) {
                // 前缀长度相同，直接使用match作为hit
                tmpHit = tmpMatch;

                if (useCache) {
                    allBdd = tsbdd.orToWithCache(allBdd, tmpHit);
                } else {
                    allBdd = tsbdd.orTo(allBdd, tmpHit);
                }
            } else {
                if (useCache) {
                    // 使用L2-hit缓存
                    L2CacheKey l2HitKey = L2CacheKey.forCalHit(tmpMatch, allBdd);
                    L2CacheKey.L2HitResult hitResult = cache.getL2Hit(l2HitKey);

                    if (hitResult == null) {
                        // 缓存未命中，计算hit空间
                        int tmp = tsbdd.notWithCache(allBdd);
                        tmpHit = tsbdd.andWithCache(tmpMatch, tmp);
                        int newAllBdd = tsbdd.orWithCache(allBdd, tmpHit);

                        // 缓存结果
                        hitResult = new L2CacheKey.L2HitResult(tmpHit, newAllBdd);
                        cache.putL2Hit(l2HitKey, hitResult);
                    }

                    // 使用缓存的结果
                    tmpHit = hitResult.hitSpaceId;
                    allBdd = hitResult.newUsedSpaceId;
                } else {
                    // 不使用缓存，原有逻辑
                    int tmp = tsbdd.not(allBdd);
                    tmpHit = tsbdd.and(tmpMatch, tmp);
                    allBdd = tsbdd.orTo(allBdd, tmpHit);
                }
            }

            lastPrefixLen = rule.prefixLen;

            // 步骤3: 合并到端口谓词
            if (useCache) {
                // 使用带缓存的操作
                if (portPredicate.containsKey(rule.forwardAction)) {
                    int newPredicate = tsbdd.orToWithCache(
                        portPredicate.get(rule.forwardAction), tmpHit);
                    portPredicate.put(rule.forwardAction, newPredicate);
                } else {
                    portPredicate.put(rule.forwardAction, tsbdd.ref(tmpHit));
                }
            } else {
                // 原有逻辑
                if (portPredicate.containsKey(rule.forwardAction)) {
                    int newPredicate = tsbdd.orTo(
                        portPredicate.get(rule.forwardAction), tmpHit);
                    portPredicate.put(rule.forwardAction, newPredicate);
                } else {
                    portPredicate.put(rule.forwardAction, tsbdd.ref(tmpHit));
                }
            }
        }

        // 生成LEC集合
        HashSet<Lec> tmpLecs = new HashSet<>();
        for (Map.Entry<ForwardAction, Integer> kv : portPredicate.entrySet()) {
            tmpLecs.add(new Lec(kv.getKey(), kv.getValue()));
        }
        Device.globalLecs.put(name, tmpLecs);
    }



   public void encodeRuleToLecFromScratch(DVNet dvNet) {
        // 检查设备是否有规则
        if (rules == null || rules.isEmpty()) {
            System.out.println("设备 [" + name + "] 没有转发规则，跳过BDD编码");
            Device.globalLecs.put(name, new HashSet<>());
            return;
        }

        dvNet.putDeviceIfAbsent(name);
        Collections.sort(rules, prefixLenComparator); // 优先级排序
        Map<ForwardAction, Integer> portPredicate = dvNet.devicePortPredicate.get(name);
        BDDEngine bdd = dvNet.getBddEngine();
        TSBDD tsbdd = bdd.getBDD();

        // 【修正】检查是否启用NP-BDD
        BDDPredicateCache cache = BDDPredicateCache.getInstance();
        boolean useCache = BDDEngine.isNPBDDEnabled();

        HashSet<String> portsSet = new HashSet<>();
        HashMap<String, Integer> portCnt = new HashMap<>();

        // 2、最长前缀匹配
        int allBdd = 0;
        boolean isFirst = true;

        for (Rule rule : rules) {
            // 【修正】规则编码，使用Integer接收缓存结果
            int tmpMatch;

            if (useCache) {
                L2CacheKey l2Key = L2CacheKey.forEncodeRule(rule.ip, rule.prefixLen);
                Integer cachedMatch = cache.getL2Encode(l2Key);  // 【修正】使用Integer接收

                if (cachedMatch == null) {
                    tmpMatch = bdd.encodeDstIPPrefixWithCache(rule.ip, rule.prefixLen);
                    cache.putL2Encode(l2Key, tmpMatch);
                } else {
                    tmpMatch = cachedMatch.intValue();  // 【修正】转换为int
                }
            } else {
                tmpMatch = bdd.encodeDstIPPrefix(rule.ip, rule.prefixLen);
            }

            int tmpHit = tmpMatch;

            // 2. 最长前缀匹配
            if (isFirst) {
                isFirst = false;
                allBdd = tsbdd.ref(tmpMatch);
                tsbdd.ref(tmpMatch);
            } else {
                // 【修正】使用带缓存的操作
                if (useCache) {
                    int tmp = tsbdd.refWithCache(tsbdd.notWithCache(allBdd));
                    tmpHit = tsbdd.refWithCache(tsbdd.andWithCache(tmpMatch, tmp));
                    allBdd = tsbdd.orToWithCache(allBdd, tmpMatch);
                    tsbdd.deref(tmp);
                } else {
                    int tmp = tsbdd.ref(tsbdd.not(allBdd));
                    tmpHit = tsbdd.ref(tsbdd.and(tmpMatch, tmp));
                    allBdd = tsbdd.orTo(allBdd, tmpMatch);
                    tsbdd.deref(tmp);
                }
            }

            dvNet.putDeviceRuleHit(name, rule, tmpHit);
        }

        // 3. 合并为LEC
        this.encodeRuleToLec(dvNet);
    }


    public void encodeRuleToLecFromScratchIPV6(DVNet dvNet) throws UnknownHostException {
        // 检查设备是否有IPv6规则
        if (rulesIPV6 == null || rulesIPV6.isEmpty()) {
            System.out.println("设备 [" + name + "] 没有IPv6转发规则，跳过BDD编码");
            Device.globalLecs.put(name, new HashSet<>());
            return;
        }

        dvNet.putDeviceIfAbsent(name);
        Collections.sort(rulesIPV6, prefixLenComparatorIPV6); // 优先级排序
        Map<ForwardAction, Integer> portPredicate = dvNet.devicePortPredicate.get(name);
        BDDEngine bdd = dvNet.getBddEngine();
        TSBDD tsbdd = bdd.getBDD();

        // 【修正】检查是否启用NP-BDD
        BDDPredicateCache cache = BDDPredicateCache.getInstance();
        boolean useCache = BDDEngine.isNPBDDEnabled();

        boolean isFirst = false;
        int allBdd = 0;

        for (RuleIPV6 ruleIPV6 : rulesIPV6) {
            // 1. BDD转化（带缓存支持）
            int tmpMatch;

            if (useCache) {
                // IPv6暂时不使用L2缓存（如果需要可以添加）
                tmpMatch = bdd.encodeDstIPPrefixIpv6(ruleIPV6.ip, ruleIPV6.prefixLen);
            } else {
                tmpMatch = bdd.encodeDstIPPrefixIpv6(ruleIPV6.ip, ruleIPV6.prefixLen);
            }

            int tmpHit = tmpMatch;

            // 2. 最长前缀匹配
            if (!isFirst) {
                isFirst = true;
                allBdd = tsbdd.ref(tmpMatch);
            } else {
                if (useCache) {
                    tmpHit = tsbdd.diffWithCache(tmpMatch, allBdd);
                    allBdd = tsbdd.orToWithCache(allBdd, tmpMatch);
                } else {
                    tmpHit = tsbdd.diff(tmpMatch, allBdd);
                    allBdd = tsbdd.orTo(allBdd, tmpMatch);
                }
            }

            // 3. 要把每一个 port 拆开，合并为LEC
            Collection<String> tmpPorts = ruleIPV6.forwardAction.ports;
            ForwardType tmpForwardType = ruleIPV6.forwardAction.forwardType;

            for(String tmpPort : tmpPorts){
                ForwardAction tmpForwardAction = new ForwardAction(tmpForwardType, tmpPort);

                if (useCache) {
                    if (portPredicate.containsKey(tmpForwardAction)) {
                        int newPredicate = tsbdd.orToWithCache(
                            portPredicate.get(tmpForwardAction), tmpHit);
                        portPredicate.put(tmpForwardAction, newPredicate);
                    } else {
                        portPredicate.put(tmpForwardAction, tsbdd.ref(tmpHit));
                    }
                } else {
                    if (portPredicate.containsKey(tmpForwardAction)) {
                        int newPredicate = tsbdd.orTo(
                            portPredicate.get(tmpForwardAction), tmpHit);
                        portPredicate.put(tmpForwardAction, newPredicate);
                    } else {
                        portPredicate.put(tmpForwardAction, tsbdd.ref(tmpHit));
                    }
                }
            }
        }

        // 生成LEC集合
        HashSet<Lec> tmpLecs = new HashSet<>();
        for (Map.Entry<ForwardAction, Integer> kv : portPredicate.entrySet()) {
            tmpLecs.add(new Lec(kv.getKey(), kv.getValue()));
        }
        Device.globalLecs.put(name, tmpLecs);
    }

    Comparator<Rule> prefixLenComparator = new Comparator<Rule>() {
        @Override
        public int compare(Rule r1, Rule r2) {
            // 添加空值检查，防止排序异常
            if (r1 == null && r2 == null) return 0;
            if (r1 == null) return 1;
            if (r2 == null) return -1;
            
            // 安全的前缀长度比较
            int prefix1 = r1.prefixLen;
            int prefix2 = r2.prefixLen;
            return Integer.compare(prefix2, prefix1); // 使用Integer.compare避免溢出
        }
    };

    Comparator<RuleIPV6> prefixLenComparatorIPV6 = new Comparator<RuleIPV6>() {
        @Override
        public int compare(RuleIPV6 r1, RuleIPV6 r2) {
            // 添加空值检查，防止排序异常
            if (r1 == null && r2 == null) return 0;
            if (r1 == null) return 1;
            if (r2 == null) return -1;
            
            // 安全的前缀长度比较
            int prefix1 = r1.prefixLen;
            int prefix2 = r2.prefixLen;
            return Integer.compare(prefix2, prefix1); // 使用Integer.compare避免溢出
        }
    };

    public void close() {
    }

    /**
     * 初始化
     */
    protected void init() {
        ForwardType.init();
        rulesIPV6 = new ArrayList<>();
        bddEngine = new BDDEngine();
        rules = new ArrayList<>();
        if (globalLecs == null) {
            globalLecs = new HashMap<>();
        }
        // 确保初始化状态标记
        rulesLoaded = false;
        bddTransformed = false;
    }
}
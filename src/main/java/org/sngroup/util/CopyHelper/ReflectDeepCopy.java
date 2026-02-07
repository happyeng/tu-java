package org.sngroup.util.CopyHelper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.*;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

// 通过反射dfs递归遍历对象的所有内容字段和引用字段,进行深拷贝
public class ReflectDeepCopy {

    // 用于跟踪已经拷贝的对象，防止重复拷贝和处理循环引用
    private Map<Object, Object> copiedObjects = new IdentityHashMap<>();

    /**
     * 不可变类型集合：这些类型的对象不需要深拷贝，直接返回原对象即可。
     * 包括 String、基本类型包装类、以及其他常见的 JDK 不可变类型。
     * 这样可以避免 Java 9+ 模块系统对 java.base 内部类反射访问的限制。
     */
    private static final Set<Class<?>> IMMUTABLE_TYPES = Set.of(
        // 基本类型包装类
        Boolean.class, Integer.class, Character.class,
        Byte.class, Short.class, Double.class,
        Long.class, Float.class, Void.class,
        // String 及相关
        String.class, StringBuilder.class, StringBuffer.class,
        // 数学类型
        BigDecimal.class, BigInteger.class,
        // 时间类型
        LocalDate.class, LocalTime.class, LocalDateTime.class,
        Instant.class, Duration.class, Period.class,
        ZonedDateTime.class, OffsetDateTime.class, OffsetTime.class,
        ZoneId.class, ZoneOffset.class,
        // 其他常见不可变类型
        UUID.class, Pattern.class,
        Class.class
    );

    public Object deepCopy(Object original) throws Exception {
        if (original == null) {
            return null;
        }

        // 如果已经拷贝过该对象，直接返回拷贝的对象
        if (copiedObjects.containsKey(original)) {
            return copiedObjects.get(original);
        }

        Class<?> originalClass = original.getClass();

        // 要注意分情况讨论, 在JAVA中无明确内存概念, 不同数据类型有不同处理方式
        // 如果是数组，单独处理(关键处理, Clone接口不能实现深拷贝的原因, 或在于此)
        if (originalClass.isArray()) {
            int length = Array.getLength(original);
            Object copyArray = Array.newInstance(originalClass.getComponentType(), length);
            copiedObjects.put(original, copyArray);
            for (int i = 0; i < length; i++) {
                Array.set(copyArray, i, deepCopy(Array.get(original, i)));
            }
            return copyArray;
        }

        // 如果是枚举、基本类型包装类、String 或其他不可变类型，直接返回原始对象
        if (originalClass.isEnum() || isImmutableType(originalClass)) {
            return original;
        }

        // 对于 java.base 模块中的其他类型（如 URL、URI 等），也直接返回
        // 避免模块系统限制导致的 InaccessibleObjectException
        if (isJdkInternalType(originalClass)) {
            return original;
        }

        // 创建新的对象实例
        Object copyObject;
        try {
            copyObject = originalClass.getConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            // 没有无参构造函数的类，直接返回原对象（通常是不可变的JDK类）
            return original;
        }
        copiedObjects.put(original, copyObject);

        // 递归拷贝所有字段
        Class<?> currentClass = originalClass;
        while (currentClass != null) {
            for (Field field : currentClass.getDeclaredFields()) {
                // 跳过静态和瞬态字段 (关键处理, Clone接口不能实现深拷贝的原因, 或在于没有丢弃顺态字段)
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(original);
                    Object copiedFieldValue = deepCopy(fieldValue);
                    field.set(copyObject, copiedFieldValue);
                } catch (Exception e) {
                    // 如果某个字段无法访问（模块系统限制），尝试直接赋值原值
                    // 这比整个深拷贝失败要好
                    try {
                        field.setAccessible(true);
                        field.set(copyObject, field.get(original));
                    } catch (Exception ignored) {
                        // 实在无法处理则跳过该字段
                    }
                }
            }
            currentClass = currentClass.getSuperclass();
        }
        return copyObject;
    }

    /**
     * 判断是否为不可变类型（不需要深拷贝的类型）
     */
    private boolean isImmutableType(Class<?> clazz) {
        return IMMUTABLE_TYPES.contains(clazz);
    }

    /**
     * 判断是否为 JDK 内部类型（java.*, javax.*, sun.* 等）
     * 这些类型在 Java 9+ 模块系统下无法通过反射访问内部字段
     */
    private boolean isJdkInternalType(Class<?> clazz) {
        String name = clazz.getName();
        return name.startsWith("java.") 
            || name.startsWith("javax.") 
            || name.startsWith("sun.") 
            || name.startsWith("jdk.");
    }

    /**
     * @deprecated 使用 {@link #isImmutableType(Class)} 代替
     */
    @Deprecated
    private boolean isWrapperType(Class<?> clazz) {
        return clazz.equals(Boolean.class) || clazz.equals(Integer.class) || clazz.equals(Character.class) ||
                clazz.equals(Byte.class) || clazz.equals(Short.class) || clazz.equals(Double.class) ||
                clazz.equals(Long.class) || clazz.equals(Float.class);
    }
}
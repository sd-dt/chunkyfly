package com.chunkyfly;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「原版动态统一缓冲溢出」的防崩 + 定位工具（配合 {@code MixinDynamicUniformStorage} / {@code MixinDynamicUniforms}）。
 * <p>
 * <b>原版 bug</b>：1.21.9+ 的 {@code DynamicUniformStorage} 在"一帧内写入条数超过容量"时扩容，
 * 新缓冲大小按 {@code blockSize * newCapacity} 用 <b>int</b> 计算。blockSize 是每条变换对齐后的大小
 * （160 字节 → 对齐 256），于是容量到 8,388,608 时：{@code 256 × 8388608 = 2^31} → int 溢出成负数 →
 * {@code GpuBuffer} 抛 {@code IllegalArgumentException: Buffer size must be greater than zero} 崩游戏。
 * 用户 2026-09-19 的两次崩溃（01:53 崩在画太阳、12:10 崩在 Xaero 小地图）都是这条路径。
 * <p>
 * <b>谁在写</b>：用户环境里唯一的"批量写入方"是投影（Litematica 0.26.12）的新原理图渲染器
 * （{@code WorldRendererSchematic.prepareBlockLayers} 攒"每个可见原理图区块 × 图层"的变换，最后一次
 * {@code DynamicUniforms.writeAll}）。这里通过采样把批量大小与调用来源记进日志，坐实这一点。
 */
public final class UniformBufferGuard {
   private static final Logger LOGGER = LoggerFactory.getLogger(ChunkyFlyMod.MOD_ID + "/uniform-guard");
   /** 单条写入累计到这么多条就开始采样（正常会话峰值只有几十~几百） */
   private static final int SAMPLE_THRESHOLD = 1 << 13;
   /** 一次批量写入超过这么多条就记录（正常模组批量通常只有几百~几千） */
   private static final int BATCH_WARN = 1 << 12;
   /** 一次批量写入超过这么多条就额外发一条聊天提示 */
   private static final int BATCH_CHAT = 1 << 20;
   /** 容量涨到这个量级就不正常了 */
   private static final int CAPACITY_WARN = 1 << 16;
   private static final long SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
   /** 原版整数溢出的临界容量（256 字节 × 8388608 = 2^31） */
   public static final int OVERFLOW_CAPACITY = 1 << 23;
   private static final AtomicBoolean capacityWarned = new AtomicBoolean();
   private static final AtomicBoolean overflowWarned = new AtomicBoolean();
   private static final AtomicBoolean bigBatchWarned = new AtomicBoolean();
   private static final Map<String, Integer> writers = new ConcurrentHashMap<>();
   private static final Map<String, Integer> batchWriters = new ConcurrentHashMap<>();
   private static volatile long lastSampleNanos;
   private static volatile long lastBatchSampleNanos;
   private static volatile String pendingChat;

   private UniformBufferGuard() {
   }

   /** 扩容到不会溢出的容量时调用（超过阈值会提示一次） */
   public static void noteCapacity(int newCapacity) {
      if (newCapacity >= CAPACITY_WARN && capacityWarned.compareAndSet(false, true)) {
         pendingChat = "原版渲染的「动态变换缓冲」已涨到 "
            + newCapacity
            + " 条（正常只有几十~几千条）：投影的原理图渲染器在按「可见区块 × 图层」批量写入，"
            + "涨到 8388608 时原版会整数溢出崩游戏。建议调小渲染距离 / 关掉原理图叠加渲染 / 缩小投影范围。"
            + "详细来源见 logs/latest.log 的 [uniform-guard]";
         LOGGER.warn("[uniform-guard] 动态变换缓冲容量涨到 {} 条（异常量级）", newCapacity);
         dumpWriters("容量异常");
      }
   }

   /** 即将整数溢出、已拦住（Mixin 里把写指针回绕、不再新建缓冲） */
   public static void noteOverflowAvoided(int blockSize, int newCapacity, int capacity) {
      if (overflowWarned.compareAndSet(false, true)) {
         pendingChat = "已阻止一次原版渲染崩溃（动态变换缓冲扩容整数溢出："
            + blockSize
            + " × "
            + newCapacity
            + " > 2^31）。画面可能有个别错位，建议尽快重启客户端并调小渲染距离/关闭原理图叠加";
      }

      LOGGER.error(
         "[uniform-guard] 拦住原版溢出：blockSize={} newCapacity={} 当前容量={} → 已改为回绕写入（不再新建缓冲）",
         blockSize,
         newCapacity,
         capacity
      );
      dumpWriters("溢出拦截");
   }

   /** 每次单条写入时调用；只在大容量状态下按秒采样调用栈 */
   public static void noteWrite(int capacity) {
      if (capacity < SAMPLE_THRESHOLD) {
         return;
      }

      long now = System.nanoTime();
      if (now - lastSampleNanos < SAMPLE_INTERVAL_NANOS) {
         return;
      }

      lastSampleNanos = now;
      String source = firstModFrame();
      if (source != null) {
         writers.merge(source, 1, Integer::sum);
      }
   }

   /** 每次批量写入（writeAll）时调用：记下"一次写了多少条、来自谁" */
   public static void noteBatch(String kind, int count) {
      if (count < BATCH_WARN) {
         return;
      }

      String source = firstModFrame();
      long now = System.nanoTime();
      boolean throttled = now - lastBatchSampleNanos < SAMPLE_INTERVAL_NANOS;
      if (source != null) {
         batchWriters.merge(source, 1, Integer::sum);
      }

      if (!throttled) {
         lastBatchSampleNanos = now;
         LOGGER.warn("[uniform-guard] 批量{}写入 {} 条，来自 {}", kind, count, source == null ? "（未识别）" : source);
      }

      if (count >= BATCH_CHAT && bigBatchWarned.compareAndSet(false, true)) {
         pendingChat = "检测到一次批量写入 " + count + " 条渲染变换（来自 " + (source == null ? "未知模组" : source)
            + "），这就是缓冲膨胀、最终崩游戏的根源；建议调小渲染距离或关闭该模组的批量渲染";
      }
   }

   /** 取调用栈里第一个"非原版 / 非 Fabric / 非 chunkyfly"的模组帧（用来认领写入来源） */
   private static String firstModFrame() {
      StackTraceElement[] trace = Thread.currentThread().getStackTrace();

      for (StackTraceElement frame : trace) {
         String cls = frame.getClassName();
         if (cls.startsWith("java.")
            || cls.startsWith("jdk.")
            || cls.startsWith("net.minecraft.")
            || cls.startsWith("com.mojang.")
            || cls.startsWith("org.spongepowered.")
            || cls.startsWith("net.fabricmc.")
            || cls.startsWith("com.chunkyfly.")) {
            continue;
         }

         return cls + "#" + frame.getMethodName();
      }

      return null;
   }

   /** 把采样到的写入来源写进日志（Top 8） */
   private static void dumpWriters(String reason) {
      LOGGER.warn("[uniform-guard] {} —— 单条写入来源采样：{}", reason, top(writers));
      LOGGER.warn("[uniform-guard] {} —— 批量写入来源采样：{}", reason, top(batchWriters));
   }

   private static String top(Map<String, Integer> map) {
      StringBuilder sb = new StringBuilder();
      map.entrySet()
         .stream()
         .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
         .limit(8)
         .forEach(entry -> sb.append("\n    ").append(entry.getValue()).append("× ").append(entry.getKey()));
      return sb.length() == 0 ? "（还没采到样本）" : sb.toString();
   }

   /** 由客户端 tick 线程取走待提示文本（返回 null 表示没有） */
   public static String pollChatWarning() {
      String message = pendingChat;
      pendingChat = null;
      return message;
   }
}

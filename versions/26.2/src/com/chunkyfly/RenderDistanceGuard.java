package com.chunkyfly;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;

/**
 * 飞行期间临时压小「渲染距离」，任务结束 / 暂停 / 取消 / 退出世界后自动恢复用户原来的值。
 * <p>
 * <b>为什么需要它</b>：1.21.9+ 的原版渲染会把"每次绘制用的变换"写进一个动态统一缓冲
 * （{@code DynamicUniformStorage}，日志里的 {@code Resizing Dynamic Transforms UBO, capacity limit of N reached
 * during a single frame. New capacity will be M.}）。一帧内写不下就**翻倍扩容**。
 * 飞到 Y=1000 时前方完全没有地形遮挡，一帧内可见的区块段数量会暴涨，这个缓冲被一路翻倍到
 * 4,194,304 → 8,388,608 条（一条约 160 字节，已经 1.2 GB 量级），扩容时分配出一个 0 字节的 GPU 缓冲，
 * 原版直接抛 {@code IllegalArgumentException: Buffer size must be greater than zero} 把游戏崩掉
 * （用户 2026-09-19 01:53 那次崩溃就是这条路径，堆栈全在 net.minecraft.* 里）。
 * <p>
 * chunkyfly 的目的只是让**服务端**加载/生成区块，客户端根本不需要把远处真的画出来，
 * 所以飞行期间把渲染距离压到 8（可配置）既省性能，又直接减少每帧要画的区块段数量。
 * 用 {@code /chunkyfly rd off} 可以关掉这个行为。
 */
public final class RenderDistanceGuard {
   /** 我们改之前用户的值；null = 现在没有被我们改过 */
   private static Integer savedValue;
   /** 我们改成的值（用来判断用户是不是自己又调过） */
   private static int appliedValue = -1;

   private RenderDistanceGuard() {
   }

   /** 当前生效的渲染距离上限；0 = 这个保护被关掉了 */
   public static int limit() {
      int limit = ChunkyFlyConfig.flightRenderDistance();
      if (limit <= 0) {
         return 0;
      }

      return Math.max(ChunkyFlyConfig.MIN_RENDER_DISTANCE, Math.min(ChunkyFlyConfig.MAX_RENDER_DISTANCE, limit));
   }

   /** 现在是不是正被我们压着 */
   public static boolean isClamped() {
      return savedValue != null;
   }

   /** 每 tick 调用：{@code wantClamp} 为真就压小，否则恢复 */
   public static void tick(Minecraft client, boolean wantClamp) {
      if (wantClamp && limit() > 0) {
         clamp(client);
      } else {
         restore(client);
      }
   }

   private static void clamp(Minecraft client) {
      OptionInstance<Integer> option = option(client);
      if (option == null) {
         return;
      }

      int limit = limit();
      int current = option.get();
      if (savedValue == null) {
         if (current <= limit) {
            // 用户本来就设得比我们要压的还小：什么都不用做，也不用记原值
            return;
         }

         savedValue = current;
      }

      if (current > limit) {
         option.set(limit);
         appliedValue = limit;
      }
   }

   /**
    * 恢复用户原来的渲染距离。
    * 如果飞行途中是用户自己改过（当前值不等于我们压下去的值），就尊重他的选择、不再覆盖。
    */
   public static void restore(Minecraft client) {
      Integer target = savedValue;
      int applied = appliedValue;
      savedValue = null;
      appliedValue = -1;
      if (target == null) {
         return;
      }

      OptionInstance<Integer> option = option(client);
      if (option == null) {
         return;
      }

      int current = option.get();
      if (applied >= 0 && current != applied) {
         return;
      }

      if (current != target) {
         option.set(target);
      }
   }

   private static OptionInstance<Integer> option(Minecraft client) {
      return client == null || client.options == null ? null : client.options.renderDistance();
   }
}

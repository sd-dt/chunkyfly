package com.chunkyfly;

import net.minecraft.class_1713;
import net.minecraft.class_1799;
import net.minecraft.class_310;
import net.minecraft.class_746;
import net.minecraft.class_9288;
import net.minecraft.class_9334;

/**
 * 烟花不够时自动从潜影盒里取。
 * <p>
 * 开盒方式与服务器插件一致：在<b>自己的背包界面里右键点潜影盒</b>
 * （{@code clickSlot(syncId, slot, 1, PICKUP, player)}）—— 服务器插件会直接把盒子打开，
 * 不需要把盒子放到地上，所以高空飞行时也能安全操作。
 * <p>
 * 流程：IDLE → OPENING（点一下右键，等界面出现）→ TRANSFER（对盒子里每个非爆炸烟花按 Shift 点击搬进背包）
 * → CLOSING（关掉界面）→ 冷却。整个过程里调用方会暂停补烟花（避免误触），但滑翔与转向照常。
 * <p>
 * 盒子内容用物品的 {@code container} 组件（{@code class_9334.field_49622}）离线读取，
 * 所以能精确挑"装着非爆炸烟花"的那个盒子，不用先打开再找。
 */
public final class ShulkerRestock {
   public enum State {
      IDLE,
      OPENING,
      TRANSFER,
      CLOSING
   }

   private static final int OPEN_TIMEOUT = 40;
   private static final int TRANSFER_TIMEOUT = 200;
   private static final int COOLDOWN = 40;
   /** 每次最多搬这么多下（每下 = 一组） */
   private static final int MAX_MOVES_PER_SECOND = 6;

   private static State state = State.IDLE;
   private static int timer;
   private static int cooldown;
   private static int movesLeft;
   private static int shulkerMenuSlot = -1;
   private static String lastResult = "";

   private ShulkerRestock() {
   }

   public static boolean active() {
      return state != State.IDLE;
   }

   public static State getState() {
      return state;
   }

   public static String getLastResult() {
      return lastResult;
   }

   /** 找一个"装着非爆炸烟花"的潜影盒，返回玩家背包下标（找不到 -1） */
   public static int findShulkerWithRockets(class_746 player) {
      if (player == null) {
         return -1;
      }

      for (int i = 0; i < 36; i++) {
         class_1799 stack = player.method_31548().method_5438(i);
         if (shulkerRocketCount(stack) > 0) {
            return i;
         }
      }

      return -1;
   }

   /** 背包里所有潜影盒中"非爆炸烟花"的总数（HUD 显示用） */
   public static int countRocketsInShulkers(class_746 player) {
      if (player == null) {
         return 0;
      }

      int total = 0;
      for (int i = 0; i < 36; i++) {
         total += shulkerRocketCount(player.method_31548().method_5438(i));
      }

      return total;
   }

   /** 这个潜影盒里有多少个非爆炸烟花（读 container 组件，不开盒） */
   public static int shulkerRocketCount(class_1799 shulker) {
      if (shulker == null || shulker.method_7960()) {
         return 0;
      }

      class_9288 contents = (class_9288)shulker.method_58694(class_9334.field_49622);
      if (contents == null) {
         return 0;
      }

      int total = 0;
      for (class_1799 inner : contents.method_59714()) {
         if (RocketUtils.isSafeRocket(inner)) {
            total += inner.method_7947();
         }
      }

      return total;
   }

   /** 该不该去开盒子补货：手上/背包快没烟花了，而且盒子里有 */
   public static boolean shouldRestock(class_746 player, int threshold) {
      if (player == null || state != State.IDLE || cooldown > 0) {
         return false;
      }

      if (RocketUtils.countRockets(player) > threshold) {
         return false;
      }

      return findShulkerWithRockets(player) >= 0;
   }

   /** 每个 tick 驱动一次；needRestock 由调用方判断 */
   public static void tick(class_746 player, boolean needRestock) {
      if (cooldown > 0) {
         cooldown--;
      }

      if (player == null) {
         reset();
         return;
      }

      switch (state) {
         case IDLE -> {
            if (needRestock) {
               start(player);
            }
         }
         case OPENING -> {
            timer--;
            if (player.field_7512 != player.field_7498) {
               // 盒子界面开了：进入搬运
               state = State.TRANSFER;
               timer = TRANSFER_TIMEOUT;
               movesLeft = MAX_MOVES_PER_SECOND;
               lastResult = "已打开潜影盒，搬运烟花";
            } else if (timer <= 0) {
               lastResult = "潜影盒没打开（服务器可能不支持右键开盒）";
               ChunkyFlyMod.warn(lastResult);
               finish(player);
            }
         }
         case TRANSFER -> {
            timer--;
            if (movesLeft <= 0) {
               movesLeft = MAX_MOVES_PER_SECOND;
            }

            if (player.field_7512 == player.field_7498) {
               // 界面被关掉了（玩家手动关了）：结束
               state = State.IDLE;
               cooldown = COOLDOWN;
            } else if (moveOne(player)) {
               movesLeft--;
            } else {
               state = State.CLOSING;
               timer = 10;
               lastResult = "已把潜影盒里的烟花搬出来";
            }

            if (timer <= 0) {
               state = State.CLOSING;
               timer = 10;
            }
         }
         case CLOSING -> {
            timer--;
            if (timer <= 0 || player.field_7512 == player.field_7498) {
               player.method_7346();
               state = State.IDLE;
               cooldown = COOLDOWN;
            }
         }
      }
   }

   private static void start(class_746 player) {
      int inventorySlot = findShulkerWithRockets(player);
      if (inventorySlot < 0) {
         return;
      }

      class_310 client = class_310.method_1551();
      if (client.field_1761 == null || player.field_7512 != player.field_7498) {
         // 已经有别的界面开着：等它关掉
         return;
      }

      shulkerMenuSlot = RocketUtils.menuSlotFor(inventorySlot);
      // 在自己背包界面里"右键点潜影盒"= 服务器插件开盒
      client.field_1761.method_2906(player.field_7512.field_7763, shulkerMenuSlot, 1, class_1713.field_7790, player);
      state = State.OPENING;
      timer = OPEN_TIMEOUT;
      lastResult = "正在打开潜影盒";
   }

   /**
    * 把盒子里的一组非爆炸烟花按 Shift 点击搬进背包（一次一种）。
    * 容器界面里最后 36 个槽位是玩家背包，前面的都是盒子的槽位。
    */
   private static boolean moveOne(class_746 player) {
      class_310 client = class_310.method_1551();
      if (client.field_1761 == null || player.field_7512 == null) {
         return false;
      }

      int total = player.field_7512.field_7761.size();
      int containerSlots = Math.max(0, total - 36);

      for (int slot = 0; slot < containerSlots; slot++) {
         class_1799 stack = player.field_7512.method_7611(slot).method_7677();
         if (RocketUtils.isSafeRocket(stack)) {
            client.field_1761.method_2906(player.field_7512.field_7763, slot, 0, class_1713.field_7794, player);
            return true;
         }
      }

      return false;
   }

   /** 结束并复位（任务取消/暂停时调用） */
   public static void reset() {
      state = State.IDLE;
      timer = 0;
      cooldown = 0;
      movesLeft = 0;
      shulkerMenuSlot = -1;
   }

   private static void finish(class_746 player) {
      if (player != null && player.field_7512 != player.field_7498) {
         player.method_7346();
      }

      state = State.IDLE;
      cooldown = COOLDOWN;
   }
}

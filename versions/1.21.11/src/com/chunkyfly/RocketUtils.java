package com.chunkyfly;

import net.minecraft.class_1268;
import net.minecraft.class_1657;
import net.minecraft.class_1661;
import net.minecraft.class_1713;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_310;
import net.minecraft.class_746;
import net.minecraft.class_9284;
import net.minecraft.class_9334;

/**
 * 烟花火箭 / 鞘翅 / 换槽相关的工具方法。
 * <p>
 * 只认「非爆炸烟花」：烟花火箭的 {@code fireworks} 组件里 {@code explosions} 为空 —— 也就是合成时
 * 没放烟火之星的那种，飞行时不会炸到自己、也不会互相引爆。
 */
public final class RocketUtils {
   /** 快捷栏槽位数 */
   private static final int HOTBAR = 9;

   private RocketUtils() {
   }

   /** 是不是可安全使用的烟花火箭（有 fireworks 组件且没有爆炸效果） */
   public static boolean isSafeRocket(class_1799 stack) {
      if (stack == null || stack.method_7960() || stack.method_7909() != class_1802.field_8639) {
         return false;
      }

      class_9284 fireworks = (class_9284)stack.method_58694(class_9334.field_49616);
      return fireworks == null || fireworks.comp_2392().isEmpty();
   }

   /** 烟花飞行时长（1~3）：没有组件时按 1 算 */
   public static int flightDuration(class_1799 stack) {
      class_9284 fireworks = (class_9284)stack.method_58694(class_9334.field_49616);
      return fireworks == null ? 1 : Math.max(1, fireworks.comp_2391());
   }

   /** 统计玩家身上（快捷栏 + 背包，不含装备槽）所有非爆炸烟花的总数 */
   public static int countRockets(class_746 player) {
      if (player == null) {
         return 0;
      }

      class_1661 inventory = player.method_31548();
      int total = 0;

      for (int i = 0; i < 36; i++) {
         class_1799 stack = inventory.method_5438(i);
         if (isSafeRocket(stack)) {
            total += stack.method_7947();
         }
      }

      return total;
   }

   /**
    * 统计各档烟花的数量：[0] 不用，[1]/[2]/[3] 分别是一速/二速/三速（飞行时长）的个数。
    * 只看快捷栏 + 背包（不含装备槽）。
    */
   public static int[] countByDuration(class_746 player) {
      int[] counts = new int[4];
      if (player == null) {
         return counts;
      }

      class_1661 inventory = player.method_31548();
      for (int i = 0; i < 36; i++) {
         class_1799 stack = inventory.method_5438(i);
         if (isSafeRocket(stack)) {
            int duration = Math.min(3, Math.max(1, flightDuration(stack)));
            counts[duration] += stack.method_7947();
         }
      }

      return counts;
   }

   /** 找放烟花的槽位：优先"指定档次"，找不到就退而求其次（3→2→1），再找不到用任意非爆炸烟花 */
   public static int findRocketSlot(class_746 player, int preferredDuration) {
      if (player == null) {
         return -1;
      }

      int[] order = switch (Math.min(3, Math.max(1, preferredDuration))) {
         case 1 -> new int[]{1, 2, 3};
         case 2 -> new int[]{2, 3, 1};
         default -> new int[]{3, 2, 1};
      };

      for (int wanted : order) {
         class_1661 inventory = player.method_31548();
         // 快捷栏优先
         for (int i = 0; i < HOTBAR; i++) {
            class_1799 stack = inventory.method_5438(i);
            if (isSafeRocket(stack) && flightDuration(stack) == wanted) {
               return i;
            }
         }

         for (int i = HOTBAR; i < 36; i++) {
            class_1799 stack = inventory.method_5438(i);
            if (isSafeRocket(stack) && flightDuration(stack) == wanted) {
               return i;
            }
         }
      }

      return -1;
   }

   /** 任意档次的烟花槽位（旧接口，等价于 preferredDuration = 3 的搜索顺序） */
   public static int findRocketSlot(class_746 player) {
      return findRocketSlot(player, 3);
   }

   /** 保证手上有非爆炸烟花：没有就换到手上（按指定档次优先，找不到用别的档次） */
   public static boolean ensureRocketInHand(class_746 player, int preferredDuration) {
      if (player == null) {
         return false;
      }

      class_1799 hand = player.method_6047();
      if (isSafeRocket(hand) && flightDuration(hand) == preferredDuration) {
         return true;
      }

      int slot = findRocketSlot(player, preferredDuration);
      if (slot < 0) {
         return false;
      }

      // 已经在手上（只是档次不同）就不用换
      if (slot == player.method_31548().method_67532() && isSafeRocket(hand)) {
         return true;
      }

      return swapToSelected(slot, player);
   }

   /** 兼容旧调用：优先三速 */
   public static boolean ensureRocketInHand(class_746 player) {
      return ensureRocketInHand(player, 3);
   }

   /**
    * 一次助推实际能持续多少 tick。
    * 原版 {@code FireworkRocketEntity} 的寿命是 {@code 10 * 时长 + rand(0..6) + rand(0..7)}，
    * 而助推是"火箭存活期间每 tick 都在推"，所以最保险的窗口是 10*时长+12；
    * 1.0.4 之前用 10*时长 就补射，每次都白烧 0~12 tick（这就是"烟花用得太快"）。
    */
   public static int boostWindowTicks(int duration) {
      return 10 * Math.min(3, Math.max(1, duration)) + 12;
   }

   /** 当前手持烟花的飞行时长（用于估算加速时长） */
   public static int handFlightDuration(class_746 player) {
      return player == null ? 1 : flightDuration(player.method_6047());
   }

   /**
    * 胸甲槽里是否有"能滑翔"的东西。
    * <p>
    * 按 <b>glider 组件</b>判断，而不是"物品是不是 minecraft:elytra" ——
    * 很多服务器有鞘翅+盔甲融合插件，融合后的胸甲是自定义物品，但带着 glider 组件；
    * 原版 1.21.2+ 的 {@code canGlide()} 也是看这个组件，所以按组件判断才和游戏本身一致。
    */
   public static boolean hasGliderEquipped(class_746 player) {
      if (player == null || player.field_7512 == null) {
         return false;
      }

      class_1799 chest = player.field_7512.method_7611(6).method_7677();
      return isGlider(chest);
   }

   /** 这个物品能不能滑翔：带了 glider 组件（原版鞘翅、或融合插件做出来的胸甲） */
   public static boolean isGlider(class_1799 stack) {
      if (stack == null || stack.method_7960()) {
         return false;
      }

      if (stack.method_7909() == class_1802.field_8833) {
         return true;
      }

      return stack.method_58694(class_9334.field_54197) != null;
   }

   /** 兼容旧名字 */
   public static boolean hasElytraEquipped(class_746 player) {
      return hasGliderEquipped(player);
   }

   /** 在背包/快捷栏里找一个能滑翔的物品（返回背包下标，找不到 -1） */
   public static int findGliderSlot(class_746 player) {
      if (player == null) {
         return -1;
      }

      class_1661 inventory = player.method_31548();

      for (int i = 0; i < 36; i++) {
         if (isGlider(inventory.method_5438(i))) {
            return i;
         }
      }

      return -1;
   }

   /**
    * 自动穿上能滑翔的胸甲（原版鞘翅，或带 glider 组件的融合胸甲）：
    * 用原版点击「拾起 → 放进胸甲槽 → 把换下来的放回原槽」。只在没开容器界面时动手。
    * <p>
    * 注意：这只是"顺手帮忙"，找不到东西不算失败 —— 很多服务器的融合插件本身就允许滑翔，
    * 所以调用方不会因为它返回 false 就停任务。
    */
   public static boolean equipGlider(class_746 player) {
      if (player == null) {
         return false;
      }

      if (hasGliderEquipped(player)) {
         return true;
      }

      if (player.field_7512 != player.field_7498 || player.field_7512 == null) {
         return false;
      }

      class_1661 inventory = player.method_31548();
      int slot = findGliderSlot(player);
      if (slot < 0) {
         return false;
      }

      class_310 client = class_310.method_1551();
      if (client.field_1761 == null) {
         return false;
      }

      int syncId = player.field_7512.field_7763;
      int menuSlot = menuSlotFor(slot);
      client.field_1761.method_2906(syncId, menuSlot, 0, class_1713.field_7790, player);
      client.field_1761.method_2906(syncId, 6, 0, class_1713.field_7790, player);
      client.field_1761.method_2906(syncId, menuSlot, 0, class_1713.field_7790, player);
      return true;
   }

   /** 兼容旧名字 */
   public static boolean equipElytra(class_746 player) {
      return equipGlider(player);
   }

   /** 背包下标 → 玩家菜单槽位号（0~8 快捷栏在菜单里是 36~44，9~35 与菜单一致） */
   public static int menuSlotFor(int inventorySlot) {
      return inventorySlot < HOTBAR ? inventorySlot + 36 : inventorySlot;
   }

   /**
    * 把某个背包槽位的物品换到「当前选中的快捷栏槽位」：快捷栏直接选中；
    * 背包槽先本地交换（本 tick 立即生效）再发原版 SWAP 点击让服务端做同样的事。
    */
   public static boolean swapToSelected(int sourceSlot, class_746 player) {
      if (player == null || sourceSlot < 0) {
         return false;
      }

      class_310 client = class_310.method_1551();
      class_1661 inventory = player.method_31548();

      if (player.field_7512 != player.field_7498) {
         return false;
      }

      if (sourceSlot < HOTBAR) {
         inventory.method_61496(sourceSlot);
         if (client.method_1562() != null) {
            client.method_1562().method_52787(new net.minecraft.class_2868(inventory.method_67532()));
         }

         return true;
      }

      int hotbarSlot = inventory.method_67532();
      class_1799 source = inventory.method_5438(sourceSlot);
      if (source.method_7960() || client.field_1761 == null) {
         return false;
      }

      class_1799 hand = inventory.method_5438(hotbarSlot).method_7972();
      inventory.method_5447(sourceSlot, hand);
      inventory.method_5447(hotbarSlot, source.method_7972());
      client.field_1761.method_2906(player.field_7512.field_7763, sourceSlot, hotbarSlot, class_1713.field_7791, player);
      return true;
   }

   /** 使用一次手上的物品（放烟花 / 起跳用） */
   public static void useItemInHand(class_746 player) {
      class_310 client = class_310.method_1551();
      if (client.field_1761 != null && player != null) {
         client.field_1761.method_2919(player, class_1268.field_5808);
      }
   }

   /** 把 0~35 之外的槽位保护一下：只用于调试输出 */
   public static String describeHand(class_746 player) {
      if (player == null) {
         return "-";
      }

      class_1799 hand = player.method_6047();
      return hand.method_7960() ? "空手" : String.valueOf(hand.method_7909());
   }

   /** 物品类型名字（调试用，避免直接打印组件） */
   public static String nameOf(class_1792 item) {
      return item == null ? "-" : item.toString();
   }

   /** 给外部用的常量：鞘翅物品 */
   public static class_1792 elytraItem() {
      return class_1802.field_8833;
   }

   /** 给外部用的常量：烟花火箭物品 */
   public static class_1792 rocketItem() {
      return class_1802.field_8639;
   }

   /** 玩家是否在滑翔 */
   public static boolean isGliding(class_1657 player) {
      return player != null && player.method_6128();
   }
}

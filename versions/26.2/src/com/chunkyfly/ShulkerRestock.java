package com.chunkyfly;

import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.core.component.DataComponents;

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
 * 盒子内容用物品的 {@code container} 组件（{@code DataComponents.CONTAINER}）离线读取，
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
   public static int findShulkerWithRockets(LocalPlayer player) {
      if (player == null) {
         return -1;
      }

      for (int i = 0; i < 36; i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (shulkerRocketCount(stack) > 0) {
            return i;
         }
      }

      return -1;
   }

   /** 背包里所有潜影盒中"非爆炸烟花"的总数（HUD 显示用） */
   public static int countRocketsInShulkers(LocalPlayer player) {
      if (player == null) {
         return 0;
      }

      int total = 0;
      for (int i = 0; i < 36; i++) {
         total += shulkerRocketCount(player.getInventory().getItem(i));
      }

      return total;
   }

   /** 这个潜影盒里有多少个非爆炸烟花（读 container 组件，不开盒） */
   public static int shulkerRocketCount(ItemStack shulker) {
      if (shulker == null || shulker.isEmpty()) {
         return 0;
      }

      ItemContainerContents contents = (ItemContainerContents)shulker.get(DataComponents.CONTAINER);
      if (contents == null) {
         return 0;
      }

      int total = 0;
      for (ItemStack inner : contents.nonEmptyItemCopyStream().toList()) {
         if (RocketUtils.isSafeRocket(inner)) {
            total += inner.getCount();
         }
      }

      return total;
   }

   /** 该不该去开盒子补货：手上/背包快没烟花了，而且盒子里有 */
   public static boolean shouldRestock(LocalPlayer player, int threshold) {
      if (player == null || state != State.IDLE || cooldown > 0) {
         return false;
      }

      if (RocketUtils.countRockets(player) > threshold) {
         return false;
      }

      return findShulkerWithRockets(player) >= 0;
   }

   /** 每个 tick 驱动一次；needRestock 由调用方判断 */
   public static void tick(LocalPlayer player, boolean needRestock) {
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
            if (player.containerMenu != player.inventoryMenu) {
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

            if (player.containerMenu == player.inventoryMenu) {
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
            if (timer <= 0 || player.containerMenu == player.inventoryMenu) {
               player.closeContainer();
               state = State.IDLE;
               cooldown = COOLDOWN;
            }
         }
      }
   }

   private static void start(LocalPlayer player) {
      int inventorySlot = findShulkerWithRockets(player);
      if (inventorySlot < 0) {
         return;
      }

      Minecraft client = Minecraft.getInstance();
      if (client.gameMode == null || player.containerMenu != player.inventoryMenu) {
         // 已经有别的界面开着：等它关掉
         return;
      }

      shulkerMenuSlot = RocketUtils.menuSlotFor(inventorySlot);
      // 在自己背包界面里"右键点潜影盒"= 服务器插件开盒
      client.gameMode.handleContainerInput(player.containerMenu.containerId, shulkerMenuSlot, 1, ContainerInput.PICKUP, player);
      state = State.OPENING;
      timer = OPEN_TIMEOUT;
      lastResult = "正在打开潜影盒";
   }

   /**
    * 把盒子里的一组非爆炸烟花按 Shift 点击搬进背包（一次一种）。
    * 容器界面里最后 36 个槽位是玩家背包，前面的都是盒子的槽位。
    */
   private static boolean moveOne(LocalPlayer player) {
      Minecraft client = Minecraft.getInstance();
      if (client.gameMode == null || player.containerMenu == null) {
         return false;
      }

      int total = player.containerMenu.slots.size();
      int containerSlots = Math.max(0, total - 36);

      for (int slot = 0; slot < containerSlots; slot++) {
         ItemStack stack = player.containerMenu.getSlot(slot).getItem();
         if (RocketUtils.isSafeRocket(stack)) {
            client.gameMode.handleContainerInput(player.containerMenu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
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

   private static void finish(LocalPlayer player) {
      if (player != null && player.containerMenu != player.inventoryMenu) {
         player.closeContainer();
      }

      state = State.IDLE;
      cooldown = COOLDOWN;
   }
}

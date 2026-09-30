package com.chunkyfly;

import net.minecraft.class_1657;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2404;
import net.minecraft.class_2680;
import net.minecraft.class_638;

/**
 * 地形与净空扫描：全部是轻量的方块状态查询（每次扫描几十~几百次，几 tick 一次即可）。
 * <ul>
 *   <li>{@link #skyClear} —— 起飞前检查头顶有没有方块（3×3 柱状向上扫），有就拒绝起飞；</li>
 *   <li>{@link #scan} —— 前方若干距离上的"地形顶面高度 / 是否挡路 / 是否有岩浆 / 头顶天花板"，
 *       用于巡航高度的爬升与绕行。</li>
 * </ul>
 */
public final class TerrainScanner {
   /** 向上扫多少格判断"头顶空不空"（覆盖 320 高度限制的世界绰绰有余） */
   private static final int SKY_SCAN = 400;
   /** 前方采样距离（方块） */
   private static final int[] LOOKAHEAD = {10, 20, 34, 52, 72};
   /** 与地形保持的最小垂直余量 */
   public static final double CLEARANCE = 22.0;
   /** 向下/向上找地形顶面的范围 */
   private static final int DOWN_SCAN = 56;
   private static final int UP_SCAN = 40;

   private TerrainScanner() {
   }

   /** 扫描结果 */
   public static final class Scan {
      /** 前方地形顶面的最高点（用来算需要爬升到多少） */
      public double requiredY = Double.NEGATIVE_INFINITY;
      /** 前方是否有一堵"高过自己"的墙（近距离的实心方块） */
      public boolean wallAhead;
      /** 前方/下方是否有岩浆 */
      public boolean lavaAhead;
      /** 头顶最近的天花板高度（没有就是 +∞） */
      public double ceilingY = Double.POSITIVE_INFINITY;
      /** 正下方最近的地面高度（没有就是 -∞） */
      public double groundY = Double.NEGATIVE_INFINITY;
      /** 绕行建议：往哪边偏航（0 = 不用绕，±1 = 左/右） */
      public int avoidSide;
   }

   /**
    * 头顶净空检查：玩家所在 3×3 柱体向上扫，任一格是"实心方块"就算被挡住。
    * 返回 null 表示净空；否则返回挡住的高度（Y）。
    */
   public static Double blockedAboveY(class_638 world, class_1657 player) {
      if (world == null || player == null) {
         return null;
      }

      int baseX = (int)Math.floor(player.method_23317());
      int baseY = (int)Math.floor(player.method_23318());
      int baseZ = (int)Math.floor(player.method_23321());

      for (int dy = 1; dy <= SKY_SCAN; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               class_2338 pos = new class_2338(baseX + dx, baseY + dy, baseZ + dz);
               if (isSolid(world.method_8320(pos))) {
                  return (double)pos.method_10264();
               }
            }
         }
      }

      return null;
   }

   /** 前方/下方地形扫描（用于爬升与绕行） */
   public static Scan scan(class_638 world, class_1657 player) {
      Scan result = new Scan();
      if (world == null || player == null) {
         return result;
      }

      double px = player.method_23317();
      double py = player.method_23318();
      double pz = player.method_23321();
      double vx = player.method_18798().field_1352;
      double vz = player.method_18798().field_1350;
      double speed = Math.sqrt(vx * vx + vz * vz);
      double dirX;
      double dirZ;
      if (speed > 0.02) {
         dirX = vx / speed;
         dirZ = vz / speed;
      } else {
         // 速度太低：按当前朝向算
         double yaw = Math.toRadians(player.method_36454());
         dirX = -Math.sin(yaw);
         dirZ = Math.cos(yaw);
      }

      // 正下方地面
      result.groundY = columnTop(world, (int)Math.floor(px), (int)Math.floor(pz), (int)Math.floor(py) - 1, DOWN_SCAN, true);

      for (int distance : LOOKAHEAD) {
         int x = (int)Math.floor(px + dirX * distance);
         int z = (int)Math.floor(pz + dirZ * distance);
         Column column = columnInfo(world, x, z, (int)Math.floor(py));
         if (column.topY > result.requiredY) {
            result.requiredY = column.topY;
         }

         if (column.lava) {
            result.lavaAhead = true;
         }

         if (distance <= 34 && column.topY > py + 2.0) {
            result.wallAhead = true;
         }

         if (column.ceilingY < result.ceilingY) {
            result.ceilingY = column.ceilingY;
         }
      }

      // 需要绕行时，左右各采一个点，往地形低的那侧偏
      if (result.wallAhead) {
         double yaw = Math.atan2(-dirX, dirZ);
         double leftTop = terrainTopAtYaw(world, px, py, pz, yaw - Math.toRadians(40.0), 34);
         double rightTop = terrainTopAtYaw(world, px, py, pz, yaw + Math.toRadians(40.0), 34);
         result.avoidSide = leftTop <= rightTop ? -1 : 1;
      }

      if (result.requiredY != Double.NEGATIVE_INFINITY) {
         result.requiredY += CLEARANCE;
      }

      return result;
   }

   private static double terrainTopAtYaw(class_638 world, double px, double py, double pz, double yaw, int distance) {
      int x = (int)Math.floor(px - Math.sin(yaw) * distance);
      int z = (int)Math.floor(pz + Math.cos(yaw) * distance);
      return columnInfo(world, x, z, (int)Math.floor(py)).topY;
   }

   /** 某一列的信息：地形顶面 / 是否有岩浆 / 头顶最近天花板 */
   private static Column columnInfo(class_638 world, int x, int z, int baseY) {
      Column column = new Column();
      int from = baseY - DOWN_SCAN;
      int to = baseY + UP_SCAN;

      for (int y = from; y <= to; y++) {
         class_2680 state = world.method_8320(new class_2338(x, y, z));
         if (state.method_26204() == class_2246.field_10164) {
            column.lava = true;
            if ((double)y > column.topY) {
               column.topY = y;
            }
            continue;
         }

         if (isSolid(state)) {
            if ((double)y > column.topY) {
               column.topY = y;
            }

            if ((double)y > baseY + 1.0 && (double)y < column.ceilingY) {
               column.ceilingY = y;
            }
         }
      }

      return column;
   }

   /** 从 startY 往下的第一个实心方块（用于"离地高度"） */
   private static double columnTop(class_638 world, int x, int z, int startY, int range, boolean downward) {
      for (int i = 0; i <= range; i++) {
         int y = startY - i;
         if (isSolid(world.method_8320(new class_2338(x, y, z)))) {
            return y + 1.0;
         }
      }

      return Double.NEGATIVE_INFINITY;
   }

   /** 实心地形方块：非空气、非流体、并且有硬度（排除草、花这类零硬度装饰） */
   private static boolean isSolid(class_2680 state) {
      if (state.method_26215()) {
         return false;
      }

      if (state.method_26204() instanceof class_2404) {
         return false;
      }

      return state.method_26204().method_36555() > 0.0F;
   }

   /** 列信息 */
   private static final class Column {
      double topY = Double.NEGATIVE_INFINITY;
      double ceilingY = Double.POSITIVE_INFINITY;
      boolean lava;
   }
}

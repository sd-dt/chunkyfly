package com.chunkyfly;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.multiplayer.ClientLevel;

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
   /** 前方采样距离（方块）：最前面两项是近距离，用来在下降/俯冲时尽早发现贴脸的墙 */
   private static final int[] LOOKAHEAD = {5, 10, 20, 34, 52, 72};
   /** 与地形保持的最小垂直余量 */
   public static final double CLEARANCE = 22.0;
   /** 预测会撞上时，希望"擦过这一列时"至少留出的垂直余量 */
   public static final double RISK_MARGIN = 6.0;
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
      /**
       * 按当前速度<b>预测</b>会不会撞上：对每个采样列算"到达它时自己会在多高"，
       * 若那一列的地形顶面高出这个预测高度（不足 {@link #RISK_MARGIN} 的余量），就算有撞墙风险。
       * <p>
       * 为什么不能只看"地形顶面是否高过当前高度"：俯冲时每秒能掉几十格，
       * 等地形真的高过自己时已经来不及拉起来了 —— 这就是"下降时有概率撞墙"的根因。
       */
      public boolean impactRisk;
      /** 有撞墙风险中最近的那一列的距离（方块；没有风险时是 +∞） */
      public double impactDistance = Double.POSITIVE_INFINITY;
      /** 有撞墙风险中最高那一列的地形顶面（方块；没有风险时是 -∞） */
      public double impactTopY = Double.NEGATIVE_INFINITY;
   }

   /**
    * 头顶净空检查：玩家所在 3×3 柱体向上扫，任一格是"实心方块"就算被挡住。
    * 返回 null 表示净空；否则返回挡住的高度（Y）。
    */
   public static Double blockedAboveY(ClientLevel world, Player player) {
      if (world == null || player == null) {
         return null;
      }

      int baseX = (int)Math.floor(player.getX());
      int baseY = (int)Math.floor(player.getY());
      int baseZ = (int)Math.floor(player.getZ());

      for (int dy = 1; dy <= SKY_SCAN; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               BlockPos pos = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
               if (isSolid(world.getBlockState(pos))) {
                  return (double)pos.getY();
               }
            }
         }
      }

      return null;
   }

   /** 前方/下方地形扫描（用于爬升与绕行） */
   public static Scan scan(ClientLevel world, Player player) {
      Scan result = new Scan();
      if (world == null || player == null) {
         return result;
      }

      double px = player.getX();
      double py = player.getY();
      double pz = player.getZ();
      double vx = player.getDeltaMovement().x;
      double vz = player.getDeltaMovement().z;
      double speed = Math.sqrt(vx * vx + vz * vz);
      double dirX;
      double dirZ;
      if (speed > 0.02) {
         dirX = vx / speed;
         dirZ = vz / speed;
      } else {
         // 速度太低：按当前朝向算
         double yaw = Math.toRadians(player.getYRot());
         dirX = -Math.sin(yaw);
         dirZ = Math.cos(yaw);
      }

      // 正下方地面
      result.groundY = columnTop(world, (int)Math.floor(px), (int)Math.floor(pz), (int)Math.floor(py) - 1, DOWN_SCAN, true);

      // 预测用：当前水平/垂直速度（方块每 tick）
      double hSpeed = Math.sqrt(vx * vx + vz * vz);
      double vy = player.getDeltaMovement().y;

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

         // 预测碰撞：按当前速度到这一列要几 tick、那时自己多高；地形顶面比预测高度还高（且余量不足）就是撞墙风险。
         // 这一条管的是"俯冲/下降时来不及拉起"——只看当前高度是发现不了的。
         if (column.topY != Double.NEGATIVE_INFINITY) {
            double ticks = hSpeed > 0.05 ? distance / hSpeed : Double.POSITIVE_INFINITY;
            double predictedY = Double.isInfinite(ticks) ? py : py + vy * ticks;
            if (column.topY + RISK_MARGIN > predictedY) {
               result.impactRisk = true;
               if (distance < result.impactDistance) {
                  result.impactDistance = distance;
               }

               if (column.topY > result.impactTopY) {
                  result.impactTopY = column.topY;
               }
            }
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

   private static double terrainTopAtYaw(ClientLevel world, double px, double py, double pz, double yaw, int distance) {
      int x = (int)Math.floor(px - Math.sin(yaw) * distance);
      int z = (int)Math.floor(pz + Math.cos(yaw) * distance);
      return columnInfo(world, x, z, (int)Math.floor(py)).topY;
   }

   /** 某一列的信息：地形顶面 / 是否有岩浆 / 头顶最近天花板 */
   private static Column columnInfo(ClientLevel world, int x, int z, int baseY) {
      Column column = new Column();
      int from = baseY - DOWN_SCAN;
      int to = baseY + UP_SCAN;

      for (int y = from; y <= to; y++) {
         BlockState state = world.getBlockState(new BlockPos(x, y, z));
         if (state.getBlock() == Blocks.LAVA) {
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
   private static double columnTop(ClientLevel world, int x, int z, int startY, int range, boolean downward) {
      for (int i = 0; i <= range; i++) {
         int y = startY - i;
         if (isSolid(world.getBlockState(new BlockPos(x, y, z)))) {
            return y + 1.0;
         }
      }

      return Double.NEGATIVE_INFINITY;
   }

   /** 实心地形方块：非空气、非流体、并且有硬度（排除草、花这类零硬度装饰） */
   private static boolean isSolid(BlockState state) {
      if (state.isAir()) {
         return false;
      }

      if (state.getBlock() instanceof LiquidBlock) {
         return false;
      }

      return state.getBlock().defaultDestroyTime() > 0.0F;
   }

   /** 列信息 */
   private static final class Column {
      double topY = Double.NEGATIVE_INFINITY;
      double ceilingY = Double.POSITIVE_INFINITY;
      boolean lava;
   }
}

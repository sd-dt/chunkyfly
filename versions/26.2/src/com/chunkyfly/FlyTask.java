package com.chunkyfly;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次「飞行加载区块」任务。
 * <p>
 * 区域可以是「以出发点为圆心的正方形」（{@code /chunkyfly <半径>}），
 * 也可以是「任意矩形」（{@code /chunkyfly corner <x> <z>}，两个对角点所在的区块）。
 * 玩家沿着<b>蛇形航线</b>飞，每飞过一处就把「当前区块 ± markRadius 内的所有区块」记为已覆盖 ——
 * 行距取 2*markRadius，于是相邻两行扫过的带刚好拼满，既保证真的走到（服务端因此加载/生成）又不重复飞。
 * <p>
 * <b>为什么航点要"飞到头"：</b>如果一进航点周围 40 格就换下一个航点，飞机在离航点还有 2.5 个区块时就开始转弯，
 * 每一行的实际航线都会比规划线偏、最外那一行永远飞不到 —— 表现就是"偶尔有一条单区块宽的带没被加载"。
 * 所以现在由 {@link #shouldAdvance} 判断：<b>越过本段终点所在的横截面</b>（或真的贴到航点上）才算走完这一段。
 */
public final class FlyTask {
   public enum State {
      RUNNING,
      PAUSED,
      DONE
   }

   /** 阶段：爬升 → 区块遍历 → 返航 → 盘旋降落 */
   public enum Phase {
      CLIMBING,
      TRAVERSE,
      RETURNING,
      LANDING
   }

   /**
    * 航线在目标区域之外再外扩这么多区块。
    * <p>
    * 航点是"走到/越过就算过"，但转弯本身要花距离：外扩之后，转弯和收敛都发生在区域<b>外面</b>，
    * 区域最外那一圈才会被真正飞过（不外扩时最外圈可能只被擦到，留下一格宽的漏网带）。
    */
   private static final int ROUTE_MARGIN = 1;

   /** 主航线的"到达"判定半径（方块）：离航点这么近就换下一个，飞得顺、不来回摆 */
   private static final double WAYPOINT_REACH = 40.0;

   /** 补漏航线的"到达"判定半径（方块）：要真的贴到那一格上，确保它被扫进覆盖带 */
   private static final double FILL_REACH = 12.0;

   /** 补漏最多补几轮（每轮结束后重新检查一遍，还没盖到就再来一轮） */
   private static final int MAX_FILL_ROUNDS = 3;

   /** 覆盖判定用的半径（区块数）：由客户端渲染距离与"实际收到的区块半径"推出的保守值 */
   private final int markRadius;
   /** 出发坐标（返航点）：下达指令时玩家所在的位置 */
   public final int centerX;
   public final int centerZ;
   /** 降落中心：默认就是出发坐标；如果关掉"飞完返回出发点"，会在收尾时改成飞完的那一点 */
   private int landingX;
   private int landingZ;
   /** 要加载的区域（区块坐标，含两端） */
   public final int minChunkX;
   public final int maxChunkX;
   public final int minChunkZ;
   public final int maxChunkZ;
   /** 航线范围（区块坐标）：在区域外再扩 ROUTE_MARGIN */
   private final int routeMinX;
   private final int routeMaxX;
   private final int routeMinZ;
   private final int routeMaxZ;
   private final int totalChunks;
   private final Set<Long> covered = new HashSet<>();
   private final List<int[]> waypoints = new ArrayList<>();
   private int rowCount;
   private int waypointIndex;
   /** 补漏航点从哪个下标开始（这些航点用更严格的"到达"判定） */
   private int fillStartIndex = Integer.MAX_VALUE;
   private int fillRounds;
   public State state = State.RUNNING;
   /** 当前阶段（爬升 / 遍历） */
   public Phase phase = Phase.CLIMBING;
   /** 统计：已用烟花、已飞距离、最近一次的高度（估算用） */
   public int rocketsUsed;
   public double blocksFlown;
   public double lastY;

   private FlyTask(int homeX, int homeZ, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, int markRadiusChunks) {
      this.centerX = homeX;
      this.centerZ = homeZ;
      this.landingX = homeX;
      this.landingZ = homeZ;
      this.markRadius = Math.max(1, markRadiusChunks);
      this.minChunkX = minChunkX;
      this.maxChunkX = maxChunkX;
      this.minChunkZ = minChunkZ;
      this.maxChunkZ = maxChunkZ;
      this.routeMinX = minChunkX - ROUTE_MARGIN;
      this.routeMaxX = maxChunkX + ROUTE_MARGIN;
      this.routeMinZ = minChunkZ - ROUTE_MARGIN;
      this.routeMaxZ = maxChunkZ + ROUTE_MARGIN;
      this.totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
      this.buildWaypoints();
   }

   /** 正方形区域：以出发位置为圆心、边长 2*radiusBlocks 格（{@code /chunkyfly <半径>}） */
   public static FlyTask square(int homeX, int homeZ, int radiusBlocks, int markRadiusChunks) {
      int r = Math.max(16, radiusBlocks);
      return new FlyTask(
         homeX, homeZ, (homeX - r) >> 4, (homeX + r) >> 4, (homeZ - r) >> 4, (homeZ + r) >> 4, markRadiusChunks
      );
   }

   /** 矩形区域：两个对角区块之间（{@code /chunkyfly corner <x> <z>}，另一个角是玩家所在区块） */
   public static FlyTask rectangle(int homeX, int homeZ, int chunkX1, int chunkZ1, int chunkX2, int chunkZ2, int markRadiusChunks) {
      return new FlyTask(
         homeX,
         homeZ,
         Math.min(chunkX1, chunkX2),
         Math.max(chunkX1, chunkX2),
         Math.min(chunkZ1, chunkZ2),
         Math.max(chunkZ1, chunkZ2),
         markRadiusChunks
      );
   }

   private static long key(int cx, int cz) {
      return ((long)cx << 32) ^ (cz & 0xFFFFFFFFL);
   }

   /**
    * 蛇形航线：每行两个航点（行首、行尾），行距 = 2*markRadius 个区块。
    * 行从 {@code routeMinZ} 一直排到 {@code routeMaxZ}（两侧各外扩 ROUTE_MARGIN），
    * 保证覆盖带把整个区域包住（最外圈的带不会被"擦边"擦掉）。
    */
   private void buildWaypoints() {
      int step = Math.max(1, this.markRadius * 2);

      List<Integer> rows = new ArrayList<>();
      for (int cz = this.routeMinZ; cz < this.routeMaxZ; cz += step) {
         rows.add(cz);
      }

      if (rows.isEmpty() || rows.get(rows.size() - 1) != this.routeMaxZ) {
         rows.add(this.routeMaxZ);
      }

      this.rowCount = rows.size();
      boolean leftToRight = true;

      for (int rowZ : rows) {
         int x1 = leftToRight ? this.routeMinX : this.routeMaxX;
         int x2 = leftToRight ? this.routeMaxX : this.routeMinX;
         this.waypoints.add(new int[]{x1, rowZ});
         this.waypoints.add(new int[]{x2, rowZ});
         leftToRight = !leftToRight;
      }
   }

   public int getMarkRadius() {
      return this.markRadius;
   }

   /** 降落中心（盘旋/最后进近都围着它转） */
   public int getLandingX() {
      return this.landingX;
   }

   public int getLandingZ() {
      return this.landingZ;
   }

   /** 把降落中心挪到别处（"飞完不返航"时就地下降时用） */
   public void setLandingCenter(int x, int z) {
      this.landingX = x;
      this.landingZ = z;
   }

   public int getTotalChunks() {
      return this.totalChunks;
   }

   public int getCoveredChunks() {
      return this.covered.size();
   }

   public int getMinChunkX() {
      return this.minChunkX;
   }

   public int getMaxChunkX() {
      return this.maxChunkX;
   }

   public int getMinChunkZ() {
      return this.minChunkZ;
   }

   public int getMaxChunkZ() {
      return this.maxChunkZ;
   }

   public int getRegionChunksX() {
      return this.maxChunkX - this.minChunkX + 1;
   }

   public int getRegionChunksZ() {
      return this.maxChunkZ - this.minChunkZ + 1;
   }

   public double getProgress() {
      return this.totalChunks <= 0 ? 1.0 : Math.min(1.0, (double)this.covered.size() / (double)this.totalChunks);
   }

   public boolean isInside(int cx, int cz) {
      return cx >= this.minChunkX && cx <= this.maxChunkX && cz >= this.minChunkZ && cz <= this.maxChunkZ;
   }

   /** 把玩家当前区块周围 markRadius 内的区域标为已覆盖（只统计区域内的） */
   public void markAround(int playerChunkX, int playerChunkZ) {
      for (int cx = playerChunkX - this.markRadius; cx <= playerChunkX + this.markRadius; cx++) {
         if (cx < this.minChunkX || cx > this.maxChunkX) {
            continue;
         }

         for (int cz = playerChunkZ - this.markRadius; cz <= playerChunkZ + this.markRadius; cz++) {
            if (cz >= this.minChunkZ && cz <= this.maxChunkZ) {
               this.covered.add(key(cx, cz));
            }
         }
      }
   }

   /** 当前航点（区块坐标），没有则返回 null */
   public int[] currentWaypoint() {
      return this.waypointIndex < this.waypoints.size() ? this.waypoints.get(this.waypointIndex) : null;
   }

   /**
    * 当前航点是否已经飞完。
    * <p>
    * 判定：① 离航点足够近（主航线 40 格 / 补漏航线 12 格）→ 算飞完（这样飞得顺、不会来回摆）；
    * ② 或者已经<b>越过本段终点所在的横截面</b>（把位置投影到"上一航点 → 本航点"的方向上，投影 ≥ 0）——
    * 这一条是给"绕障/被吹偏后飞过头"兜底的，避免为了回头再飞一遍而浪费时间。
    */
   public boolean shouldAdvance(double px, double pz) {
      int[] cur = this.currentWaypoint();
      if (cur == null) {
         return false;
      }

      double wx = cur[0] * 16.0 + 8.0;
      double wz = cur[1] * 16.0 + 8.0;
      double dx = wx - px;
      double dz = wz - pz;
      double reach = this.waypointIndex >= this.fillStartIndex ? FILL_REACH : WAYPOINT_REACH;
      if (dx * dx + dz * dz <= reach * reach) {
         return true;
      }

      if (this.waypointIndex <= 0) {
         return false;
      }

      int[] prev = this.waypoints.get(this.waypointIndex - 1);
      double ux = wx - (prev[0] * 16.0 + 8.0);
      double uz = wz - (prev[1] * 16.0 + 8.0);
      double len = Math.sqrt(ux * ux + uz * uz);
      if (len < 1.0E-6) {
         return false;
      }

      double along = (px - wx) * (ux / len) + (pz - wz) * (uz / len);
      return along >= 0.0;
   }

   /**
    * 主航线飞完之后，把<b>还没被扫到</b>的区块再补一条航线（在区域内直接飞过它们）。
    * <p>
    * 这是"单区块宽的带没被加载到"的兜底修复：不管漏的那条带是转弯切角、被风吹偏还是转场时抖出来的，
    * 只要 {@link #markAround} 没把它记上，这里就会补飞一次；一轮下来还有漏的就再来一轮（最多 {@link #MAX_FILL_ROUNDS} 轮）。
    *
    * @return true 表示已经追加了补漏航点（调用方应继续遍历而不是返航）
    */
   public boolean buildFillWaypoints() {
      if (this.fillRounds >= MAX_FILL_ROUNDS) {
         return false;
      }

      List<int[]> missing = new ArrayList<>();
      for (int cx = this.minChunkX; cx <= this.maxChunkX; cx++) {
         for (int cz = this.minChunkZ; cz <= this.maxChunkZ; cz++) {
            if (!this.covered.contains(key(cx, cz))) {
               missing.add(new int[]{cx, cz});
            }
         }
      }

      if (missing.isEmpty()) {
         return false;
      }

      this.fillRounds++;
      if (this.fillStartIndex == Integer.MAX_VALUE) {
         this.fillStartIndex = this.waypoints.size();
      }

      // 按行（z）分组：同一行里从最左的漏点到最右的漏点连成一段，行与行之间蛇形串联
      missing.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
      boolean leftToRight = true;
      int i = 0;

      while (i < missing.size()) {
         int rowZ = missing.get(i)[1];
         int minX = missing.get(i)[0];
         int maxX = minX;
         int j = i;

         while (j < missing.size() && missing.get(j)[1] == rowZ) {
            maxX = Math.max(maxX, missing.get(j)[0]);
            j++;
         }

         int x1 = leftToRight ? minX : maxX;
         int x2 = leftToRight ? maxX : minX;
         this.waypoints.add(new int[]{x1, rowZ});
         if (x2 != x1) {
            this.waypoints.add(new int[]{x2, rowZ});
         }

         leftToRight = !leftToRight;
         i = j;
      }

      ChunkyFlyMod.info(
         "主航线飞完还有 " + missing.size() + " 个区块没扫到（第 " + this.fillRounds + " 轮补漏），已插入补漏航线"
      );
      return true;
   }

   /** 补漏用掉了几轮 */
   public int getFillRounds() {
      return this.fillRounds;
   }

   /** 还没被扫到的区块数（用于收尾提示） */
   public int countUncovered() {
      int count = 0;

      for (int cx = this.minChunkX; cx <= this.maxChunkX; cx++) {
         for (int cz = this.minChunkZ; cz <= this.maxChunkZ; cz++) {
            if (!this.covered.contains(key(cx, cz))) {
               count++;
            }
         }
      }

      return count;
   }

   public void advanceWaypoint() {
      this.waypointIndex++;
   }

   /** 崩溃恢复用：直接把航点下标设到存档位置 */
   public void setWaypointIndex(int index) {
      this.waypointIndex = Math.max(0, Math.min(index, this.waypoints.size()));
   }

   /**
    * 崩溃恢复用：把"前 {@code upToIndex} 个航点所覆盖的区块"预先标成已覆盖。
    * <p>
    * 航线是确定的（蛇形），所以直接沿每段采样一遍、按行距把带标出来，
    * 进度百分比就不会从 0 重新开始（实际飞行会有偏差，这里只是尽量接近）。
    */
   public void premarkRoute(int upToIndex) {
      int limit = Math.max(0, Math.min(upToIndex, this.waypoints.size()));

      for (int i = 0; i + 1 < limit; i++) {
         int[] from = this.waypoints.get(i);
         int[] to = this.waypoints.get(i + 1);
         double fx = from[0] * 16.0 + 8.0;
         double fz = from[1] * 16.0 + 8.0;
         double tx = to[0] * 16.0 + 8.0;
         double tz = to[1] * 16.0 + 8.0;
         double length = Math.sqrt((tx - fx) * (tx - fx) + (tz - fz) * (tz - fz));
         int samples = (int)Math.max(1.0, length / 8.0);

         for (int k = 0; k <= samples; k++) {
            double t = (double)k / samples;
            this.markAround((int)Math.floor((fx + (tx - fx) * t) / 16.0), (int)Math.floor((fz + (tz - fz) * t) / 16.0));
         }
      }
   }

   public int getWaypointIndex() {
      return this.waypointIndex;
   }

   public int getWaypointCount() {
      return this.waypoints.size();
   }

   public boolean allWaypointsDone() {
      return this.waypointIndex >= this.waypoints.size();
   }

   /** 航线总长度（方块），用于估算烟花数量 */
   public double getTotalPathBlocks() {
      double step = Math.max(1, this.markRadius * 2) * 16.0;
      double rowLength = (this.routeMaxX - this.routeMinX + 1) * 16.0;
      int rows = Math.max(1, this.rowCount);
      return rows * (rowLength + step);
   }

   public String describe() {
      return String.format(
         "区域 %d×%d 区块（约 %d×%d 格）| 出发 %d,%d | 覆盖 %d/%d 区块 | 航点 %d/%d",
         this.getRegionChunksX(),
         this.getRegionChunksZ(),
         this.getRegionChunksX() * 16,
         this.getRegionChunksZ() * 16,
         this.centerX,
         this.centerZ,
         this.covered.size(),
         this.totalChunks,
         Math.min(this.waypointIndex, this.waypoints.size()),
         this.waypoints.size()
      );
   }
}

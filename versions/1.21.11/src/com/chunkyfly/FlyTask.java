package com.chunkyfly;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次「飞行加载区块」任务。
 * <p>
 * 区域是一个以中心为原点的正方形（与 Chunky 默认的方形一致），玩家沿着<b>蛇形航线</b>飞，
 * 每飞过一处就把「当前区块 ± markRadius 内的所有区块」记为已覆盖 —— 行距取 2*markRadius，
 * 于是相邻两行扫过的带刚好拼满，既保证真的走到（服务端因此加载/生成）又不重复飞。
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

   /** 覆盖判定用的半径（区块数）：由客户端渲染距离推出的保守值 */
   private final int markRadius;
   public final int centerX;
   public final int centerZ;
   /** 半径（方块）：区域边长 = 2*radius */
   public final int radius;
   private final int minChunkX;
   private final int maxChunkX;
   private final int minChunkZ;
   private final int maxChunkZ;
   private final int totalChunks;
   private final Set<Long> covered = new HashSet<>();
   private final List<int[]> waypoints = new ArrayList<>();
   private int waypointIndex;
   public State state = State.RUNNING;
   /** 当前阶段（爬升 / 遍历） */
   public Phase phase = Phase.CLIMBING;
   /** 统计：已用烟花、已飞距离、最近一次的高度（估算用） */
   public int rocketsUsed;
   public double blocksFlown;
   public double lastY;

   public FlyTask(int centerX, int centerZ, int radiusBlocks, int markRadiusChunks) {
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.radius = Math.max(16, radiusBlocks);
      this.markRadius = Math.max(1, markRadiusChunks);
      this.minChunkX = (centerX - this.radius) >> 4;
      this.maxChunkX = (centerX + this.radius) >> 4;
      this.minChunkZ = (centerZ - this.radius) >> 4;
      this.maxChunkZ = (centerZ + this.radius) >> 4;
      this.totalChunks = (this.maxChunkX - this.minChunkX + 1) * (this.maxChunkZ - this.minChunkZ + 1);
      this.buildWaypoints();
   }

   private static long key(int cx, int cz) {
      return ((long)cx << 32) ^ (cz & 0xFFFFFFFFL);
   }

   /** 蛇形航线：每行两个航点（行首、行尾），行距 = 2*markRadius 个区块 */
   private void buildWaypoints() {
      int step = Math.max(1, this.markRadius * 2);
      boolean leftToRight = true;

      for (int cz = this.minChunkZ; cz <= this.maxChunkZ + step; cz += step) {
         int rowZ = Math.min(cz, this.maxChunkZ);
         if (rowZ < this.minChunkZ) {
            continue;
         }

         int x1 = leftToRight ? this.minChunkX : this.maxChunkX;
         int x2 = leftToRight ? this.maxChunkX : this.minChunkX;
         this.waypoints.add(new int[]{x1, rowZ});
         this.waypoints.add(new int[]{x2, rowZ});
         leftToRight = !leftToRight;
         if (rowZ == this.maxChunkZ) {
            break;
         }
      }
   }

   public int getMarkRadius() {
      return this.markRadius;
   }

   public int getTotalChunks() {
      return this.totalChunks;
   }

   public int getCoveredChunks() {
      return this.covered.size();
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
      while (this.waypointIndex < this.waypoints.size()) {
         int[] wp = this.waypoints.get(this.waypointIndex);
         if (this.isInside(wp[0], wp[1]) || this.covered.size() < this.totalChunks) {
            return wp;
         }

         this.waypointIndex++;
      }

      return null;
   }

   public void advanceWaypoint() {
      this.waypointIndex++;
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
      double rowLength = (this.maxChunkX - this.minChunkX + 1) * 16.0;
      int rows = (int)Math.ceil((this.maxChunkZ - this.minChunkZ + 1) / (double)Math.max(1, this.markRadius * 2)) + 1;
      return rows * (rowLength + step);
   }

   public String describe() {
      return String.format(
         "中心 %d,%d 半径 %d | 区块 %d/%d | 航点 %d/%d",
         this.centerX,
         this.centerZ,
         this.radius,
         this.covered.size(),
         this.totalChunks,
         Math.min(this.waypointIndex, this.waypoints.size()),
         this.waypoints.size()
      );
   }
}

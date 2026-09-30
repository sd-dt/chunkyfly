package com.chunkyfly;

import net.minecraft.class_310;
import net.minecraft.class_638;
import net.minecraft.class_746;

/**
 * 飞行控制器：每个客户端 tick 驱动一次。
 * <p>
 * 两个阶段：
 * <ol>
 *   <li><b>爬升</b>（起飞后先做）：抬头 55° + 连续续烟花，一路爬到 {@link FlyTask#cruiseY}（默认 Y=1000）。
 *       高空没有任何方块可撞，所以这一段不需要避障；起飞前会检查头顶净空，被挡住就拒绝起飞并提示玩家换地方。</li>
 *   <li><b>遍历</b>：在巡航高度上沿蛇形航线高速飞，把区域内的区块一格格标成已覆盖。
 *       万一高度掉得比较多（烟花不够/被打断），会启用前方地形扫描：按需爬升、必要时左右绕行、躲开岩浆与虚空。</li>
 * </ol>
 * 飞行风格：上一发烟花加速将尽就续下一发（最快），加速时俯角 −25°、纯滑翔 −3°。
 */
public final class FlyController {
   public static final FlyController INSTANCE = new FlyController();
   /** 巡航高度（用户要求：起飞后先飞到 1000 格再开始遍历） */
   public static final int CRUISE_Y = 1000;
   /**
    * 爬升卡住多久（tick）就放弃爬到 Y=1000，改用当前高度巡航。
    * 有些维度（末地）或服务器的反飞行限制根本到不了目标高度，
    * 一直按目标爬只会贴着上限白烧烟花 —— 5 秒完全没有升高就认输。
    */
   private static final int CLIMB_STALL_TICKS = 100;
   /** 帧率保护：低于这个 FPS 且持续 {@link #LOW_FPS_TICKS} 就暂停任务（避免把原版渲染缓冲顶爆） */
   private static final int LOW_FPS_THRESHOLD = 8;
   private static final int LOW_FPS_TICKS = 20 * 15;
   /** 掉到这个高度以下才开始做地形避障（高空用不到） */
   private static final double AVOID_BELOW = CRUISE_Y - 200.0;
   /**
    * 俯仰角常数。注意 MC 的约定：<b>pitch 正值 = 低头（看向地面），负值 = 抬头（看向天空）</b>
    * （例如放站立告示牌要"低头"用的是 pitch = +90）。1.0.3 之前这里符号写反了：
    * 想爬升却给 +55 → 实际变成 55° 俯冲，就是"视角莫名其妙往下飞"的原因。
    */
   private static final float PITCH_CLIMB = -80.0F;
   private static final float PITCH_BOOST = 8.0F;
   private static final float PITCH_GLIDE = -2.0F;
   private static final float PITCH_AVOID = -32.0F;
   private static final float PITCH_DIVE = 18.0F;
   private static final double WAYPOINT_REACH = 40.0;
   private static final double BOOST_MIN_REMAINING = 32.0;
   private static final float AVOID_YAW_OFFSET = 40.0F;
   private static final int AVOID_HOLD_TICKS = 30;
   /** 背包里的非爆炸烟花少于这个数就从潜影盒里补 */
   private static final int RESTOCK_THRESHOLD = 16;
   /** 返航：水平距离小于这个值就算到了出发坐标上空 */
   private static final double RETURN_REACH = 48.0;
   /** 降落盘旋的目标半径（方块） */
   private static final double ORBIT_RADIUS = 70.0;
   /** 返航 / 降落的超时（tick） */
   private static final int RETURN_TIMEOUT = 20 * 120;
   private static final int LANDING_TIMEOUT = 20 * 180;
   /**
    * 降落的俯角阶梯：越高俯得越狠。从 Y=1000 一路用 18° 慢慢往下挪要一百多秒，容易撞上降落超时；
    * 分段放大俯角几十秒就能到低空，最后再把角度收小，稳稳贴地。
    */
   private static final float PITCH_LAND_STEEP = 45.0F;
   private static final float PITCH_LAND_MID = 30.0F;
   private static final float PITCH_LAND_LOW = 10.0F;
   private static final float PITCH_LAND_FINAL = 5.0F;
   /** 降落时判定"贴地"的离地高度（方块） */
   private static final double LANDING_TOUCH_AGL = 2.0;

   private FlyTask task;
   /** 本次任务的目标巡航高度：默认 {@link #CRUISE_Y}，爬不上去时会自动降到能达到的高度 */
   private double cruiseY = CRUISE_Y;
   /** 爬升阶段记录过的最高点 + 连续没升高的 tick 数（判断"爬不动了"） */
   private double climbBestY;
   private int climbStallTicks;
   /** 帧率保护：连续低帧率的 tick 数，以及每个任务只提示一次 */
   private int lowFpsTicks;
   private boolean lowFpsWarned;
   private int boostTicks;
   private int jumpCooldown;
   private int maintenanceCooldown;
   private int scanCooldown;
   private int avoidTicks;
   private int avoidSide;
   private TerrainScanner.Scan cachedScan;
   private boolean prevInit;
   private double prevX;
   private double prevZ;
   /** 只提示一次的标记（滑翔装备 / 起不来），不阻断任务 */
   private boolean gliderHintShown;
   private boolean launchHintShown;
   private int launchFailTicks;
   /** 跳跃键是不是我们按下的（只有我们自己按的才负责松开，避免把玩家的按键状态弄乱） */
   private boolean jumpKeyOwned;
   /** 动作栏进度兜底的计时 */
   private int overlayTicks;
   /** 爬升阶段是否已经对准过方向（只对一次，避免螺旋） */
   private boolean climbAimed;
   /** 返航 / 降落计时 */
   private int returnTicks;
   private int landingTicks;

   private FlyController() {
   }

   public FlyTask getTask() {
      return this.task;
   }

   /** 开始新任务：先检查头顶净空，再按「爬升 → 遍历」跑 */
   public void start(class_746 player, int radiusBlocks) {
      if (player == null) {
         return;
      }

      class_310 client = class_310.method_1551();
      Double blocked = TerrainScanner.blockedAboveY(client.field_1687, player);
      if (blocked != null) {
         ChunkyFlyMod.warn(
            String.format("头顶 %.0f 处有方块，无法起飞。请到「头顶无方块」的露天位置再执行指令（例如空地、海面、房顶上方）。", blocked)
         );
         return;
      }

      int markRadius = 4;
      if (client.field_1769 != null) {
         markRadius = Math.max(2, Math.min(8, (int)client.field_1769.method_34812() - 2));
      }

      this.task = new FlyTask((int)player.method_23317(), (int)player.method_23321(), radiusBlocks, markRadius);
      this.cruiseY = ChunkyFlyConfig.cruiseY();
      this.climbBestY = player.method_23318();
      this.climbStallTicks = 0;
      this.boostTicks = 0;
      this.avoidTicks = 0;
      this.cachedScan = null;
      this.scanCooldown = 0;
      this.prevInit = false;
      this.gliderHintShown = false;
      this.launchHintShown = false;
      this.launchFailTicks = 0;
      this.lowFpsTicks = 0;
      this.lowFpsWarned = false;
      ChunkyFlyMod.info("任务开始：" + this.task.describe() + String.format("（覆盖判定半径 %d 区块/行距 %d 区块）", markRadius, markRadius * 2));
      ChunkyFlyMod.info(
         "第一步：先爬到 Y=" + (int)this.cruiseY + "（这个世界到不了这么高时会自动改用能达到的高度；想改目标用 /chunkyfly cruise <Y>），到了再开始区块遍历"
      );
      ChunkyFlyMod.info("预计需要烟花约 " + this.estimateRockets() + " 个，当前身上有 " + RocketUtils.countRockets(player) + " 个");
      // 渲染压力提示：1.21.9+ 原版的动态变换缓冲（日志里的 "Resizing Dynamic Transforms UBO"）会随
      // "一帧内可见区块段数量"翻倍扩容，飞到 Y=1000（完全没有遮挡）时最容易顶爆 → 原版分配 0 字节缓冲崩游戏。
      // chunkyfly 只要服务端加载区块，客户端不必真画出来，所以飞行期间会临时压小渲染距离。
      if (RenderDistanceGuard.limit() > 0) {
         ChunkyFlyMod.info(
            "飞行期间会把渲染距离临时压到 " + RenderDistanceGuard.limit() + "（暂停/结束/取消后自动恢复；用 /chunkyfly rd off 关掉这个保护）"
         );
      } else {
         ChunkyFlyMod.info("提示：飞行期间建议把渲染距离调小（chunkyfly 只需要服务端加载区块，客户端不必真的画出来）");
      }

      if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")) {
         ChunkyFlyMod.warn(
            "检测到 Voxy：远距离 LOD 会成倍增加每帧要画的区块，飞行时建议关掉 Voxy 渲染或调小它的远景距离，否则可能触发原版渲染崩溃（Buffer size must be greater than zero）"
         );
      }

      if (radiusBlocks >= 1500) {
         ChunkyFlyMod.info("半径 " + radiusBlocks + " 格范围很大，任务会飞很久；中途可以 /chunkyfly pause 再 continue");
      }
   }

   public void pause() {
      if (this.task != null && this.task.state == FlyTask.State.RUNNING) {
         this.task.state = FlyTask.State.PAUSED;
         ChunkyFlyMod.info("已暂停（继续：/chunkyfly continue）");
      }
   }

   public void resume() {
      if (this.task != null && this.task.state == FlyTask.State.PAUSED) {
         this.task.state = FlyTask.State.RUNNING;
         this.lowFpsTicks = 0;
         this.lowFpsWarned = false;
         ChunkyFlyMod.info("继续任务");
      }
   }

   public void cancel() {
      if (this.task != null) {
         ChunkyFlyMod.info("任务已取消（进度 " + String.format("%.1f%%", this.task.getProgress() * 100.0) + "）");
      }

      this.task = null;
      this.boostTicks = 0;
      this.avoidTicks = 0;
      this.cachedScan = null;
      this.gliderHintShown = false;
      this.launchHintShown = false;
      this.launchFailTicks = 0;
      ShulkerRestock.reset();
      // 关键：把我们按下的跳跃键松开，否则"停止后无法跳跃"
      this.releaseJump(class_310.method_1551());
   }

   public int estimateRockets() {
      if (this.task == null) {
         return 0;
      }

      double perRocket;
      if (this.task.rocketsUsed >= 3 && this.task.blocksFlown > 0.0) {
         perRocket = Math.max(15.0, this.task.blocksFlown / this.task.rocketsUsed);
      } else {
         // 优先用三速，所以按"身上还有的最好档次"估每发能飞多远
         class_746 player = class_310.method_1551().field_1724;
         int[] tiers = RocketUtils.countByDuration(player);
         int duration = tiers[3] > 0 ? 3 : (tiers[2] > 0 ? 2 : 1);
         perRocket = 14.0 * duration + 8.0;
      }

      double climb = Math.max(0.0, this.cruiseY - (this.task.lastY <= 0.0 ? this.cruiseY : this.task.lastY));
      double remaining = this.task.getTotalPathBlocks() * (1.0 - this.task.getProgress()) + climb;
      return (int)Math.ceil(remaining / perRocket) + 1;
   }

   public void tick(class_310 client) {
      FlyTask current = this.task;
      if (current == null) {
         return;
      }

      class_746 player = client.field_1724;
      class_638 world = client.field_1687;
      if (player == null || world == null) {
         // 退出世界/切维度时把渲染距离还回去
         RenderDistanceGuard.restore(client);
         return;
      }

      // 渲染压力保护：飞行中把渲染距离临时压小（详见 RenderDistanceGuard 的说明），任务结束/暂停/取消自动恢复
      RenderDistanceGuard.tick(client, current.state == FlyTask.State.RUNNING);

      if (this.jumpCooldown > 0) {
         this.jumpCooldown--;
      }

      if (this.maintenanceCooldown > 0) {
         this.maintenanceCooldown--;
      }

      if (this.scanCooldown > 0) {
         this.scanCooldown--;
      }

      if (player.method_6032() <= 0.0F) {
         ChunkyFlyMod.warn("玩家已死亡，任务结束");
         this.task = null;
         return;
      }

      if (current.state == FlyTask.State.DONE) {
         this.releaseJump(client);
         return;
      }

      // 帧率保护：持续 15 秒低于 8 FPS 说明渲染已经扛不住了。1.21.9+ 的动态变换缓冲会随着
      // "一帧内可见区块段数量"一路翻倍扩容，顶到几百万条时原版扩容会分配 0 字节缓冲直接崩游戏
      // （用户 01:53 那次崩溃前的征兆就是这个：缓冲从 8192 一路翻倍到 8388608）。这里主动暂停、让玩家先降压。
      if (current.state == FlyTask.State.RUNNING) {
         int fps = client.method_47599();
         if (fps > 0 && fps < LOW_FPS_THRESHOLD) {
            this.lowFpsTicks++;
            if (this.lowFpsTicks > LOW_FPS_TICKS && !this.lowFpsWarned) {
               this.lowFpsWarned = true;
               this.lowFpsTicks = 0;
               current.state = FlyTask.State.PAUSED;
               ChunkyFlyMod.warn(
                  "帧率持续低于 "
                     + LOW_FPS_THRESHOLD
                     + " FPS（渲染压力过大），已暂停任务。建议调低渲染距离 / Voxy 远景，或降低巡航高度（/chunkyfly cruise 400），然后 /chunkyfly continue"
               );
               this.releaseJump(client);
               return;
            }
         } else {
            this.lowFpsTicks = 0;
         }
      } else {
         this.lowFpsTicks = 0;
      }

      double x = player.method_23317();
      double y = player.method_23318();
      double z = player.method_23321();
      current.lastY = y;
      // 覆盖统计：把玩家当前区块 ± markRadius 内的区块记成已覆盖（1.0.6 之前这一行漏了，所以进度一直是 0%）
      current.markAround(((int)x) >> 4, ((int)z) >> 4);
      if (this.prevInit) {
         current.blocksFlown = current.blocksFlown + Math.sqrt((x - this.prevX) * (x - this.prevX) + (z - this.prevZ) * (z - this.prevZ));
      }

      this.prevX = x;
      this.prevZ = z;
      this.prevInit = true;

      if (current.state == FlyTask.State.PAUSED) {
         this.releaseJump(client);
         return;
      }

      // 兜底：动作栏每秒刷一次进度（左上角 HUD 被别的模组/设置挡住时也看得到）
      if (++this.overlayTicks >= 20) {
         this.overlayTicks = 0;
         if (client.field_1705 != null) {
            String phase = current.phase == FlyTask.Phase.CLIMBING ? "爬升" : "遍历";
            client.field_1705
               .method_1758(
                  net.minecraft.class_2561.method_43470(
                     String.format(
                        "ChunkyFly %s %.1f%%  Y=%d  烟花 3速%d/2速%d/1速%d",
                        phase,
                        current.getProgress() * 100.0,
                        (int)y,
                        RocketUtils.countByDuration(player)[3],
                        RocketUtils.countByDuration(player)[2],
                        RocketUtils.countByDuration(player)[1]
                     )
                  ),
                  false
               );
         }
      }

      // 潜影盒补货：烟花快没了就自动开盒取（你服务器有右键开盒插件，整个流程不用落地）
      boolean needRestock = ShulkerRestock.shouldRestock(player, RESTOCK_THRESHOLD);
      ShulkerRestock.tick(player, needRestock);
      if (ShulkerRestock.active()) {
         // 开盒/搬运期间不要补烟花，也不要动背包，免得和界面操作打架；滑翔与转向照常
         this.releaseJump(client);
         if (current.phase == FlyTask.Phase.CLIMBING && y < this.cruiseY) {
            player.method_36457(PITCH_CLIMB);
         }

         return;
      }

      if (this.maintenanceCooldown <= 0) {
         this.maintenanceCooldown = 10;
         if (!RocketUtils.hasGliderEquipped(player)) {
            // 服务器可能有"鞘翅+盔甲融合"插件：融合胸甲不是原版鞘翅，但带着 glider 组件。
            // 这里只按 glider 组件判断，而且找不到也只提示一次、**不阻断任务**。
            if (RocketUtils.equipGlider(player)) {
               ChunkyFlyMod.info("已自动换上能滑翔的胸甲");
            } else if (!this.gliderHintShown) {
               this.gliderHintShown = true;
               ChunkyFlyMod.info("胸甲槽没检测到可滑翔物品（服务器有鞘翅融合插件的话可以忽略；否则请自己穿上鞘翅）");
            }
         }

         if (!RocketUtils.ensureRocketInHand(player, this.preferredDuration(current))) {
            if (ShulkerRestock.countRocketsInShulkers(player) > 0) {
               // 背包空了但盒子里还有：交给上面的自动补货流程，不暂停
               return;
            }

            ChunkyFlyMod.warn("背包和潜影盒里都没有可用的非爆炸烟花了，任务暂停（补充后 /chunkyfly continue）");
            current.state = FlyTask.State.PAUSED;
            return;
         }
      }

      // 还没起飞（比如落地了）：先确认头顶净空再起跳
      if (!RocketUtils.isGliding(player)) {
         Double blocked = TerrainScanner.blockedAboveY(world, player);
         if (blocked != null) {
            ChunkyFlyMod.warn(String.format("头顶 %.0f 处有方块，无法起飞（任务暂停）。请移动到露天位置后 /chunkyfly continue。", blocked));
            current.state = FlyTask.State.PAUSED;
            this.releaseJump(client);
            return;
         }

         this.launch(client, player);
         this.launchFailTicks++;
         if (this.launchFailTicks > 300 && !this.launchHintShown) {
            this.launchHintShown = true;
            ChunkyFlyMod.info("一直没能进入滑翔：请确认胸甲槽有鞘翅/融合胸甲，且不是在方块里或水里（会继续尝试，不阻断任务）");
         }
      } else {
         this.launchFailTicks = 0;
         this.releaseJump(client);
      }

      // ---------- 阶段一：爬升到巡航高度 ----------
      if (current.phase == FlyTask.Phase.CLIMBING && y < this.cruiseY) {
         // 只在开始爬升时对准一次第一个航点方向，之后**不再改偏航**：
         // 之前每 tick 都追航点，半径小的时候航点就在脚下，偏航角来回翻 → 一直在螺旋。
         if (!this.climbAimed) {
            this.climbAimed = true;
            int[] first = current.currentWaypoint();
            if (first != null) {
               double dx = first[0] * 16.0 + 8.0 - x;
               double dz = first[1] * 16.0 + 8.0 - z;
               if (dx * dx + dz * dz > 4.0) {
                  player.method_36456((float)Math.toDegrees(Math.atan2(-dx, dz)));
               }
            }
         }

         player.method_36457(PITCH_CLIMB);
         if (this.shouldBoost(player, current, true)) {
            RocketUtils.useItemInHand(player);
            this.boostTicks = RocketUtils.boostWindowTicks(RocketUtils.handFlightDuration(player));
            current.rocketsUsed++;
         }

         if (this.boostTicks > 0) {
            this.boostTicks--;
         }

         // 爬不上去就别死磕：连续 5 秒没有升高（维度高度上限 / 服务器反飞行 / 被卡住）→ 改用当前高度巡航
         if (RocketUtils.isGliding(player)) {
            if (y > this.climbBestY + 1.0) {
               this.climbBestY = y;
               this.climbStallTicks = 0;
            } else if (++this.climbStallTicks > CLIMB_STALL_TICKS) {
               this.climbStallTicks = 0;
               this.cruiseY = Math.max(70.0, y - 5.0);
               ChunkyFlyMod.warn(String.format(
                  "一直爬不到 Y=%d（可能被维度高度或服务器限制卡住），改用当前高度 Y=%d 巡航",
                  CRUISE_Y,
                  (int)this.cruiseY
               ));
               current.phase = FlyTask.Phase.TRAVERSE;
               return;
            }
         } else {
            // 还没进滑翔（起跳还没成功）：不累加"爬不动"计数，免得在地面上就放弃
            this.climbBestY = Math.max(this.climbBestY, y);
            this.climbStallTicks = 0;
         }

         if (y >= this.cruiseY - 2.0) {
            current.phase = FlyTask.Phase.TRAVERSE;
            ChunkyFlyMod.info("已到 Y=" + (int)y + "，开始区块遍历");
         }

         return;
      }

      if (current.phase == FlyTask.Phase.CLIMBING) {
         current.phase = FlyTask.Phase.TRAVERSE;
      }

      // ---------- 阶段三：返航到出发坐标 ----------
      if (current.phase == FlyTask.Phase.RETURNING) {
         this.tickReturn(client, player, world, current, x, y, z);
         return;
      }

      // ---------- 阶段四：在出发坐标上空盘旋下降 ----------
      if (current.phase == FlyTask.Phase.LANDING) {
         this.tickLanding(client, player, world, current, x, y, z);
         return;
      }

      // ---------- 阶段二：巡航 + 遍历 ----------
      int[] waypoint = current.currentWaypoint();
      double wx = 0.0;
      double wz = 0.0;
      double dx = 0.0;
      double dz = 0.0;
      // 一次 tick 里可能连续到达好几个航点（半径小的时候航点很密），循环推进，避免原地打转
      for (int guard = 0; guard < 8; guard++) {
         if (waypoint == null) {
            break;
         }

         wx = waypoint[0] * 16.0 + 8.0;
         wz = waypoint[1] * 16.0 + 8.0;
         dx = wx - x;
         dz = wz - z;
         if (Math.sqrt(dx * dx + dz * dz) > WAYPOINT_REACH) {
            break;
         }

         current.advanceWaypoint();
         waypoint = current.currentWaypoint();
      }

      if (waypoint == null) {
         this.beginReturn(current);
         return;
      }

      float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));

      // 高度掉了才开地形扫描（高空没有方块，扫了也没用）
      TerrainScanner.Scan scan = null;
      boolean needAvoid = y < AVOID_BELOW;
      if (needAvoid) {
         if (this.scanCooldown <= 0 || this.cachedScan == null) {
            this.cachedScan = TerrainScanner.scan(world, player);
            this.scanCooldown = 4;
         }

         scan = this.cachedScan;
      }

      // 撞墙且爬升来不及：左右绕行（选地形低的一侧，保持 30 tick 免得来回摆）
      if (scan != null && scan.wallAhead && scan.requiredY - y > 30.0 && this.avoidTicks <= 0) {
         this.avoidSide = scan.avoidSide == 0 ? 1 : scan.avoidSide;
         this.avoidTicks = AVOID_HOLD_TICKS;
      }

      if (this.avoidTicks > 0) {
         this.avoidTicks--;
         yaw += this.avoidSide * AVOID_YAW_OFFSET;
      }

      boolean boost = this.boostTicks > 0;
      if (boost) {
         this.boostTicks--;
      }

      boolean needClimb;
      if (scan != null && scan.requiredY != Double.NEGATIVE_INFINITY && scan.requiredY > y) {
         needClimb = true;
      } else {
         needClimb = y < this.cruiseY - 30.0 || y < 70.0;
      }

      float pitch;
      if (scan != null && scan.lavaAhead) {
         // 前方有岩浆：立刻爬升（负 pitch = 抬头），宁可多花烟花
         pitch = PITCH_AVOID - 8.0F;
         needClimb = true;
      } else if (needClimb) {
         // 差得多就大角度爬升，差得少就小角度
         pitch = (y < this.cruiseY - 120.0) ? PITCH_CLIMB : PITCH_AVOID;
      } else if (y > this.cruiseY + 60.0) {
         // 飞太高了：稍微压一点高度（pitch 正 = 低头）
         pitch = PITCH_DIVE;
      } else if (boost) {
         pitch = PITCH_BOOST;
      } else {
         pitch = PITCH_GLIDE;
      }

      // 天花板保护：头顶 12 格内有方块就别再抬头（把负 pitch 收回到水平）
      if (scan != null && scan.ceilingY - y < 12.0 && pitch < 0.0F) {
         pitch = 0.0F;
      }

      player.method_36456(yaw);
      player.method_36457(pitch);

      double remaining = current.getTotalPathBlocks() * (1.0 - current.getProgress());
      boolean wantBoost = remaining > BOOST_MIN_REMAINING || needClimb;
      if (wantBoost && this.shouldBoost(player, current, needClimb)) {
         RocketUtils.useItemInHand(player);
         this.boostTicks = RocketUtils.boostWindowTicks(RocketUtils.handFlightDuration(player));
         current.rocketsUsed++;
      }

      if (current.allWaypointsDone() || current.getProgress() >= 1.0) {
         this.beginReturn(current);
      }
   }

   /** 遍历结束 → 返航 */
   private void beginReturn(FlyTask current) {
      if (current.phase == FlyTask.Phase.RETURNING || current.phase == FlyTask.Phase.LANDING) {
         return;
      }

      current.phase = FlyTask.Phase.RETURNING;
      this.returnTicks = 0;
      this.landingTicks = 0;
      ChunkyFlyMod.info(
         String.format(
            "遍历完成（%d/%d 区块，%.1f%%），开始返航到出发坐标 %d, %d",
            current.getCoveredChunks(),
            current.getTotalChunks(),
            current.getProgress() * 100.0,
            current.centerX,
            current.centerZ
         )
      );
   }

   /** 返航：直线飞回出发坐标上空（沿途走避障逻辑，别撞地形） */
   private void tickReturn(class_310 client, class_746 player, class_638 world, FlyTask task, double x, double y, double z) {
      this.returnTicks++;
      double dx = task.centerX + 0.5 - x;
      double dz = task.centerZ + 0.5 - z;
      double distance = Math.sqrt(dx * dx + dz * dz);
      if (distance <= RETURN_REACH) {
         task.phase = FlyTask.Phase.LANDING;
         this.landingTicks = 0;
         ChunkyFlyMod.info("已回到出发坐标上空，开始盘旋下降");
         return;
      }

      if (this.returnTicks > RETURN_TIMEOUT) {
         task.phase = FlyTask.Phase.LANDING;
         this.landingTicks = 0;
         ChunkyFlyMod.warn("返航超时，就地开始下降");
         return;
      }

      // 高度：返航路上尽量保持在高处；低了、或者正前方地形比我们高（比如返航要过一座山）就爬
      TerrainScanner.Scan scan = this.scanThrottled(world, player, 4);
      boolean terrainAhead = scan.requiredY != Double.NEGATIVE_INFINITY && scan.requiredY > y + 20.0;
      boolean needClimb = y < this.cruiseY - 120.0 || terrainAhead || scan.lavaAhead;
      boolean boosting = this.boostTicks > 0;

      float pitch;
      if (y < this.cruiseY - 120.0) {
         pitch = PITCH_CLIMB;
      } else if (needClimb) {
         pitch = PITCH_AVOID;
      } else if (boosting) {
         pitch = PITCH_BOOST;
      } else {
         pitch = PITCH_GLIDE;
      }

      // 天花板保护：头顶 12 格内有方块就别再抬头
      if (scan.ceilingY - y < 12.0 && pitch < 0.0F) {
         pitch = 0.0F;
      }

      player.method_36456((float)Math.toDegrees(Math.atan2(-dx, dz)));
      player.method_36457(pitch);

      if (this.shouldBoost(player, task, needClimb) && (needClimb || horizontalSpeed(player) < 18.0)) {
         RocketUtils.useItemInHand(player);
         this.boostTicks = RocketUtils.boostWindowTicks(RocketUtils.handFlightDuration(player));
         task.rocketsUsed++;
      }

      if (this.boostTicks > 0) {
         this.boostTicks--;
      }
   }

   /**
    * 降落：以出发坐标为中心**螺旋下降**，圈子随高度收紧（高空绕大圈、低空绕小圈），
    * 最后正好落在出发坐标上方，把控制权还给玩家。
    * <ul>
    *   <li>俯角按离地高度分档（45° → 30° → 18° → 10° → 5°），保证在超时前落下去，末段又不会砸地；</li>
    *   <li>每 2 tick 用 {@link TerrainScanner} 看一眼前方：地形比自己高就抬头爬过去（盘旋时最容易撞山/撞房）；</li>
    *   <li>岩浆同样先爬升；头顶 12 格有天花板就不抬头；</li>
    *   <li>速度掉到 14 m/s 以下且还有高度时补一发烟花，避免失速直接砸下来；</li>
    *   <li>落地 / 离地 ≤2 格 / 已经不在滑翔（掉水里、撞墙、鞘翅坏了）/ 超时 → 结束并交还控制。</li>
    * </ul>
    */
   private void tickLanding(class_310 client, class_746 player, class_638 world, FlyTask task, double x, double y, double z) {
      this.landingTicks++;
      double dx = task.centerX + 0.5 - x;
      double dz = task.centerZ + 0.5 - z;
      double distance = Math.sqrt(dx * dx + dz * dz);

      TerrainScanner.Scan scan = this.scanThrottled(world, player, 2);
      double groundY = scan.groundY == Double.NEGATIVE_INFINITY ? 0.0 : scan.groundY;
      double agl = y - groundY;

      // 目标盘旋半径随高度收紧：越高圈越大，越低圈越小 → 螺旋而不是原地打转
      double orbit = Math.min(ORBIT_RADIUS, Math.max(12.0, agl * 0.8));
      double baseYaw = Math.toDegrees(Math.atan2(-dx, dz));
      // 距离太远就多朝圆心、太近就多切向 → 稳定在 orbit 附近绕圈
      double offset = distance > orbit * 1.5 ? 35.0 : (distance < orbit * 0.6 ? 115.0 : 80.0);
      player.method_36456((float)(baseYaw + offset));

      float pitch;
      if (agl > 400.0) {
         pitch = PITCH_LAND_STEEP;
      } else if (agl > 150.0) {
         pitch = PITCH_LAND_MID;
      } else if (agl > 60.0) {
         pitch = PITCH_DIVE;
      } else if (agl > 20.0) {
         pitch = PITCH_LAND_LOW;
      } else {
         pitch = PITCH_LAND_FINAL;
      }

      // 前方地形比我们高 → 先抬头绕/爬过去，别一头撞进山体或建筑
      boolean terrainAhead = scan.requiredY != Double.NEGATIVE_INFINITY && scan.requiredY > y + 20.0;
      if (scan.lavaAhead) {
         pitch = PITCH_AVOID;
      } else if (terrainAhead) {
         pitch = Math.min(pitch, PITCH_AVOID);
      }

      // 天花板保护：头顶 12 格内有方块就别再抬头
      if (scan.ceilingY - y < 12.0 && pitch < 0.0F) {
         pitch = 0.0F;
      }

      player.method_36457(pitch);

      // 快失速了才补一发：贴地前不再点火，让它自然飘下去
      if (this.shouldBoost(player, task, terrainAhead) && (terrainAhead || (agl > 40.0 && horizontalSpeed(player) < 14.0))) {
         RocketUtils.useItemInHand(player);
         this.boostTicks = RocketUtils.boostWindowTicks(RocketUtils.handFlightDuration(player));
         task.rocketsUsed++;
      }

      if (this.boostTicks > 0) {
         this.boostTicks--;
      }

      // 落地 / 贴地 / 已经不在滑翔（掉水里、撞墙、鞘翅被打断）/ 超时 → 结束
      if (player.method_24828()
         || agl <= LANDING_TOUCH_AGL
         || !RocketUtils.isGliding(player)
         || this.landingTicks > LANDING_TIMEOUT) {
         this.finish(task);
      }
   }

   /** 带冷却的地形扫描（冷却在每 tick 开头统一递减，这里只负责到期重建） */
   private TerrainScanner.Scan scanThrottled(class_638 world, class_746 player, int cooldown) {
      if (this.scanCooldown <= 0 || this.cachedScan == null) {
         this.cachedScan = TerrainScanner.scan(world, player);
         this.scanCooldown = cooldown;
      }

      return this.cachedScan;
   }

   private static double horizontalSpeed(class_746 player) {
      double vx = player.method_18798().field_1352;
      double vz = player.method_18798().field_1350;
      return Math.sqrt(vx * vx + vz * vz) * 20.0;
   }

   private void finish(FlyTask current) {
      current.state = FlyTask.State.DONE;
      this.boostTicks = 0;
      ChunkyFlyMod.info(
         String.format(
            "任务完成：%d 个区块全部覆盖（%.1f%%），已回到出发坐标附近，用了 %d 个烟花，飞行 %.0f 格",
            current.getCoveredChunks(),
            current.getProgress() * 100.0,
            current.rocketsUsed,
            current.blocksFlown
         )
      );
      ChunkyFlyMod.info("已交还控制权，可以自己走两步了。");
   }

   /**
    * 起跳/起飞：按一下跳跃键（脉冲，不是一直按住）——地面会跳起来，空中由原版发出"开始滑翔"的包。
    * <p>
    * 只按一 tick 就松开，避免"跳跃键卡住"：键一旦卡在按下状态，玩家之后就跳不起来，
    * 也会一直触发自动跳跃。{@link #jumpKeyOwned} 记录"这个键是我们按下的"，
    * 之后在滑翔成功/暂停/完成/取消时一定会松开。
    */
   /**
    * 起飞。
    * <ul>
    *   <li><b>站在地面</b>：脉冲按一下跳跃键 —— 原版要求"新按下"才起跳，
    *       一直按住只会跳一次（1.0.5~1.0.7 就是因为一直按住，跳过一次后再也不跳）。</li>
    *   <li><b>空中且在下落</b>：直接发送原版的 {@code START_FALL_FLYING} 包并让本地立即进入滑翔。
    *       原版的按键触发条件是"空中 + 正在下落 + 该帧有一份新的跳跃输入"，
    *       靠按住/定时脉冲经常一次都踩不中，就会表现为"无法自动起飞"（手动按一下反而能成功）。
    *       直接发包是确定的，不再依赖任何按键时序。</li>
    * </ul>
    */
   private void launch(class_310 client, class_746 player) {
      if (player.method_24828()) {
         if (this.jumpKeyOwned) {
            this.releaseJump(client);
         }

         if (this.jumpCooldown <= 0) {
            client.field_1690.field_1903.method_23481(true);
            this.jumpKeyOwned = true;
            this.jumpCooldown = 8;
         }

         return;
      }

      this.releaseJump(client);
      if (player.method_18798().field_1351 <= 0.0 && client.method_1562() != null) {
         client.method_1562()
            .method_52787(new net.minecraft.class_2848(player, net.minecraft.class_2848.class_2849.field_12982));
         player.method_23669();
      }
   }

   /** 松开我们按下的跳跃键（只在自己按过的时候动，绝不干扰玩家自己按的键） */
   private void releaseJump(class_310 client) {
      if (this.jumpKeyOwned) {
         client.field_1690.field_1903.method_23481(false);
         this.jumpKeyOwned = false;
      }
   }

   /**
    * 现在该不该补一发烟花。
    * <ul>
    *   <li>必须正在滑翔、手上有非爆炸烟花；</li>
    *   <li>助推窗口（10*时长+12 tick）还没走完就不补 —— 原版火箭存活期间一直在推，提前补就是浪费；</li>
    *   <li>例外：水平速度掉到 15 m/s 以下说明这一发提前没了（比如撞地形炸了），允许提前补。</li>
    * </ul>
    */
   private boolean shouldBoost(class_746 player, FlyTask task, boolean urgent) {
      if (!RocketUtils.isGliding(player) || !RocketUtils.isSafeRocket(player.method_6047())) {
         return false;
      }

      if (this.boostTicks > 0) {
         double vx = player.method_18798().field_1352;
         double vz = player.method_18798().field_1350;
         double speed = Math.sqrt(vx * vx + vz * vz) * 20.0;
         if (speed >= 15.0 || this.boostTicks > 6) {
            return false;
         }
      }

      return true;
   }

   /**
    * 优先用哪一档烟花：一律优先三速 ——
    * 一速只推 10~22 tick、三速推 30~42 tick，飞同样距离三速消耗的发数最少（用户反馈"用得太快"）。
    * 三速用完后自动退到二速、一速（见 RocketUtils.findRocketSlot 的顺序）。
    */
   private int preferredDuration(FlyTask task) {
      return 3;
   }

   public String[] hudLines() {
      FlyTask current = this.task;
      if (current == null) {
         return null;
      }

      String stateText = switch (current.state) {
         case RUNNING -> switch (current.phase) {
            case CLIMBING -> "爬升中";
            case TRAVERSE -> "遍历中";
            case RETURNING -> "返航中";
            case LANDING -> "降落中";
         };
         case PAUSED -> "已暂停";
         case DONE -> "已完成";
      };
      class_746 player = class_310.method_1551().field_1724;
      int[] tiers = RocketUtils.countByDuration(player);
      String line1 = String.format("ChunkyFly  %.1f%%", current.getProgress() * 100.0);
      String line2 = String.format(
         "%s | 区块 %d/%d | Y=%d | 烟花 3速%d 2速%d 1速%d | 盒中%d（还需约 %d，已用 %d）",
         stateText,
         current.getCoveredChunks(),
         current.getTotalChunks(),
         (int)player.method_23318(),
         tiers[3],
         tiers[2],
         tiers[1],
         ShulkerRestock.countRocketsInShulkers(player),
         this.estimateRockets(),
         current.rocketsUsed
      );
      if (RenderDistanceGuard.isClamped()) {
         line2 = line2 + " | 渲染" + RenderDistanceGuard.limit();
      }

      return new String[]{line1, line2};
   }

   public String statusText() {
      FlyTask current = this.task;
      if (current == null) {
         return "当前没有任务";
      }

      return current.describe() + String.format(
         " | 阶段 %s | 进度 %.1f%% | 烟花 已用 %d 还需约 %d",
         switch (current.phase) {
            case CLIMBING -> "爬升到 Y=" + (int)this.cruiseY;
            case TRAVERSE -> "巡航遍历";
            case RETURNING -> "返航到出发坐标";
            case LANDING -> "盘旋降落";
         },
         current.getProgress() * 100.0,
         current.rocketsUsed,
         this.estimateRockets()
      );
   }

   public boolean hasTask() {
      return this.task != null;
   }
}

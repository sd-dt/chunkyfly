package com.chunkyfly;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

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
   /** 航点"到达/越过"的判定在 {@link FlyTask#shouldAdvance} 里；这里只保留长时间毫无进展时的兜底 */
   private static final int WAYPOINT_STALL_TICKS = 20 * 20;
   /** 量"实际收到的区块半径"时最多往外看这么多区块 */
   private static final int MAX_MEASURE_CHUNKS = 12;
   private static final double BOOST_MIN_REMAINING = 32.0;
   private static final float AVOID_YAW_OFFSET = 40.0F;
   private static final int AVOID_HOLD_TICKS = 30;
   /** 背包里的非爆炸烟花少于这个数就从潜影盒里补 */
   private static final int RESTOCK_THRESHOLD = 16;
   /** 返航：水平距离小于这个值就算到了出发坐标上空 */
   private static final double RETURN_REACH = 48.0;
   /** 降落盘旋的目标半径（方块） */
   /** 低空盘旋的最大半径：小一点，别绕着大盘旋 */
   private static final double ORBIT_RADIUS = 28.0;
   /** 高于这个离地高度就"直接朝落点飞"，不做盘旋；到了这以下才开始小半径盘旋滑行 */
   private static final double SPIRAL_AGL = 90.0;
   /** 返航 / 降落的超时（tick） */
   private static final int RETURN_TIMEOUT = 20 * 120;
   private static final int LANDING_TIMEOUT = 20 * 180;
   /**
    * 降落的俯角阶梯：越高俯得越狠。从 Y=1000 一路用 18° 慢慢往下挪要一百多秒，容易撞上降落超时；
    * 分段放大俯角几十秒就能到低空，最后再把角度收小，稳稳贴地。
    */
   private static final float PITCH_LAND_STEEP = 20.0F;
   private static final float PITCH_LAND_MID = 16.0F;
   private static final float PITCH_LAND_LOW = 6.0F;
   private static final float PITCH_LAND_FINAL = 3.0F;
   /** 降落时判定"贴地"的离地高度（方块） */
   private static final double LANDING_TOUCH_AGL = 2.0;

   /**
    * 降落接受半径（格）：<b>落在这个半径以内就算成功</b>，允许高度差（落在旁边的房顶/山坡上都行）。
    * 飞机降落时会被"圈"在这个半径的圆环里一直降到贴地，不再做最后进近/复飞，
    * 所以不会出现"快到点了又拉起来绕圈、结果落不下去"的情况。
    */
   private static final double LANDING_ACCEPT_RADIUS = 16.0;

   /** 预测到撞墙风险时"大幅拉起"的俯仰角（负 = 抬头），比常规避让更猛 */
   private static final float PITCH_IMPACT = -45.0F;
   /** 障碍近到这个距离就大幅拉起 + 侧转绕开；更远的只用温和抬头 */
   private static final double IMPACT_NEAR_DIST = 40.0;
   /** 侧转避让的保持 tick 数（免得来回摆） */
   private static final int IMPACT_AVOID_TICKS = 20;

   /** 圈内保持用的偏角（度）：朝里收 / 往外切 / 切向绕圈 */
   private static final double RING_INWARD_OFFSET = 45.0;
   private static final double RING_TANGENT_OFFSET = 80.0;
   private static final double RING_OUTWARD_OFFSET = 115.0;
   /** 贴地到这个离地高度就不再避让/复飞，直接落（否则树/矮墙会让它一直拉起来，落不下去） */
   private static final double LANDING_COMMIT_AGL = 8.0;
   /** 降落阶段最多避让几次（防止一直"拉起绕开"落不了地） */
   private static final int MAX_LANDING_AVOIDS = 6;
   /** 强制下降时的固定俯角（温和） */
   private static final float FORCE_DESCENT_PITCH = 8.0F;
   /** 强制下降最多再撑多久（20*240 = 4 分钟），仍降不下来才放弃并在提示里说明 */
   private static final int LANDING_FORCE_TIMEOUT = 20 * 240;

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
   /** 航点推进的兜底：距离长时间不缩短就跳过当前航点，避免卡死 */
   private int wpStallIndex = -1;
   private double wpStallDist = Double.MAX_VALUE;
   private int wpStallTicks;
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
   /** 降落阶段的侧转避让还要保持多少 tick */
   private int landingAvoidTicks;
   /** 降落阶段已经避让了几次（有上限，避免一直避让落不了地） */
   private int landingAvoids;
   /** 是否已切到"强制下降"（降落超时后不再交还控制权，一路降到落地） */
   private boolean landingForceDescent;
   /** 强制下降已经持续了多少 tick */
   private int landingHardTicks;
   /** 距离下次把任务进度写盘还有多少 tick（崩溃续跑用） */
   private int saveCooldown;

   private FlyController() {
   }

   public FlyTask getTask() {
      return this.task;
   }

   /** 开始新任务（方形区域）：先检查头顶净空，再按「爬升 → 遍历」跑 */
   public void start(LocalPlayer player, int radiusBlocks) {
      if (!this.checkClearance(player)) {
         return;
      }

      Minecraft client = Minecraft.getInstance();
      int markRadius = resolveMarkRadius(client, player);
      this.beginTask(client, player, FlyTask.square((int)player.getX(), (int)player.getZ(), radiusBlocks, markRadius), markRadius);
      if (radiusBlocks >= 1500) {
         ChunkyFlyMod.info("半径 " + radiusBlocks + " 格范围很大，任务会飞很久；中途可以 /chunkyfly pause 再 continue");
      }
   }

   /**
    * 开始新任务（矩形区域）：两个角点 (x1,z1) 与 (x2,z2) 所在区块组成的轴对齐矩形。
    * 指令：{@code /chunkyfly corner <x1> <z1> <x2> <z2>}
    */
   public void startCorner(LocalPlayer player, int blockX1, int blockZ1, int blockX2, int blockZ2) {
      if (!this.checkClearance(player)) {
         return;
      }

      Minecraft client = Minecraft.getInstance();
      int chunkX1 = blockX1 >> 4;
      int chunkZ1 = blockZ1 >> 4;
      int chunkX2 = blockX2 >> 4;
      int chunkZ2 = blockZ2 >> 4;
      if (chunkX1 == chunkX2 && chunkZ1 == chunkZ2) {
         ChunkyFlyMod.warn(
            "两个角点落在同一个区块里（" + chunkX1 + "," + chunkZ1 + "），没有需要飞的范围；请把两个角点拉开一些。"
         );
         return;
      }

      int markRadius = resolveMarkRadius(client, player);
      this.beginTask(
         client,
         player,
         FlyTask.rectangle((int)player.getX(), (int)player.getZ(), chunkX1, chunkZ1, chunkX2, chunkZ2, markRadius),
         markRadius
      );
      FlyTask current = this.task;
      ChunkyFlyMod.info(String.format(
         "矩形区域：角点 (%d,%d) 到 (%d,%d) → 区块 (%d,%d) 到 (%d,%d)，共 %d×%d 区块（约 %d×%d 格）",
         blockX1,
         blockZ1,
         blockX2,
         blockZ2,
         Math.min(chunkX1, chunkX2),
         Math.min(chunkZ1, chunkZ2),
         Math.max(chunkX1, chunkX2),
         Math.max(chunkZ1, chunkZ2),
         current.getRegionChunksX(),
         current.getRegionChunksZ(),
         current.getRegionChunksX() * 16,
         current.getRegionChunksZ() * 16
      ));
      long area = (long)current.getRegionChunksX() * current.getRegionChunksZ();
      if (area >= 4096L) {
         ChunkyFlyMod.info("这个矩形很大（" + area + " 个区块），任务会飞很久；中途可以 /chunkyfly pause 再 continue");
      }
   }

   /**
    * 崩溃续跑：读回上次没飞完的任务，从存档的航点接着飞（{@code /chunkyfly recover}）。
    * <p>
    * 航点之前的行会按航线几何预先补标，所以进度不会从 0 开始；起飞前照样要过
    * "头顶净空 / 鞘翅 / 烟花"这几道检查（和正常起飞完全一样）。
    */
   public void recover() {
      if (this.task != null && this.task.state != FlyTask.State.DONE) {
         ChunkyFlyMod.warn("当前已经有一个任务在跑了；想换任务先 /chunkyfly cancel。");
         return;
      }

      if (!TaskStore.hasSaved()) {
         ChunkyFlyMod.warn("没有找到可恢复的任务存档（只有崩溃/退出前的任务才会留下）。");
         return;
      }

      LocalPlayer player = Minecraft.getInstance().player;
      if (!this.checkClearance(player)) {
         return;
      }

      FlyTask restored = TaskStore.load();
      if (restored == null) {
         ChunkyFlyMod.warn("任务存档读不出来（可能已损坏），已忽略。");
         return;
      }

      Minecraft client = Minecraft.getInstance();
      int markRadius = restored.getMarkRadius();
      this.beginTask(client, player, restored, markRadius);
      ChunkyFlyMod.info(String.format(
         "已从上次的位置继续：航点 %d/%d，进度约 %.1f%%（起飞后从这一行接着飞）",
         restored.getWaypointIndex(),
         restored.getWaypointCount(),
         restored.getProgress() * 100.0
      ));
   }

   /** 丢弃崩溃续跑存档 */
   public void forgetSaved() {
      if (!TaskStore.hasSaved()) {
         ChunkyFlyMod.info("当前没有可恢复的任务存档。");
         return;
      }

      TaskStore.clear();
      ChunkyFlyMod.info("已丢弃上次的任务存档。");
   }

   /** 起飞前检查头顶净空；返回 false 表示不能起飞（已经提示过原因） */
   private boolean checkClearance(LocalPlayer player) {
      if (player == null) {
         return false;
      }

      Minecraft client = Minecraft.getInstance();
      Double blocked = TerrainScanner.blockedAboveY(client.level, player);
      if (blocked != null) {
         ChunkyFlyMod.warn(
            String.format("头顶 %.0f 处有方块，无法起飞。请到「头顶无方块」的露天位置再执行指令（例如空地、海面、房顶上方）。", blocked)
         );
         return false;
      }

      return true;
   }

   /** 建好任务、重置本 tick 状态、把开飞提示打出来（方形/矩形两种模式共用） */
   private void beginTask(Minecraft client, LocalPlayer player, FlyTask task, int markRadius) {
      this.task = task;
      this.cruiseY = ChunkyFlyConfig.cruiseY();
      this.climbBestY = player.getY();
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
      this.wpStallIndex = -1;
      this.wpStallDist = Double.MAX_VALUE;
      this.wpStallTicks = 0;
      this.saveCooldown = 20 * 5;
      ChunkyFlyMod.info("任务开始：" + task.describe() + String.format("（覆盖判定半径 %d 区块/行距 %d 区块）", markRadius, markRadius * 2));
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

      // 崩溃提示 + 光敏性癫痫提示：放在任务真正开始跑之前（也是这一批提示的最后两条，动作栏会停在它们上面）
      ChunkyFlyMod.warnCrashNotice();
      ChunkyFlyMod.warnPhotosensitivity();
   }

   /**
    * 覆盖判定半径（区块）：取「飞行时客户端渲染距离」和「实际收到的区块半径」的<b>较小值</b>，再留 1 个区块余量。
    * <p>
    * 为什么要量实际半径：服务端视距比客户端渲染距离小的时候，服务端只会把那么远的区块发过来。
    * 如果行距仍然按客户端渲染距离算，相邻两行扫过的带之间就会<b>成排地漏</b>，
    * 表现正是"有一条单区块宽的带没被加载到"。量出来再留一格余量，行距就一定小于等于真实带宽。
    */
   private static int resolveMarkRadius(Minecraft client, LocalPlayer player) {
      int byRender = client.options.renderDistance().get() - 2;
      int flightLimit = RenderDistanceGuard.limit();
      if (flightLimit > 0) {
         byRender = Math.min(byRender, flightLimit - 1);
      }

      Integer measured = measureLoadedRadius(client, player);
      // 量到的半径太小（1~2 区块）通常是"刚进世界、区块还没加载完"的误测：这种时候宁可退回按渲染距离算，
      // 否则行距会被压得极密，白飞好几倍路程
      int radius = measured != null && measured >= 3 ? Math.min(byRender, measured - 1) : byRender;
      return Math.max(2, Math.min(8, radius));
   }

   /** 量一下玩家四周"客户端确实持有区块"的半径（四个方向取较小值）；量不到返回 null */
   private static Integer measureLoadedRadius(Minecraft client, LocalPlayer player) {
      if (client.level == null) {
         return null;
      }

      int cx = player.getBlockX() >> 4;
      int cz = player.getBlockZ() >> 4;
      int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      int best = Integer.MAX_VALUE;

      for (int[] dir : dirs) {
         int reach = 0;

         for (int i = 1; i <= MAX_MEASURE_CHUNKS; i++) {
            if (client.level.getChunkSource().getChunk(cx + dir[0] * i, cz + dir[1] * i, ChunkStatus.FULL, false) == null) {
               break;
            }

            reach = i;
         }

         best = Math.min(best, reach);
      }

      return best == Integer.MAX_VALUE || best <= 0 ? null : best;
   }

   public void pause() {
      if (this.task != null && this.task.state == FlyTask.State.RUNNING) {
         this.task.state = FlyTask.State.PAUSED;
         TaskStore.save(this.task);
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

      TaskStore.clear();

      this.task = null;
      this.boostTicks = 0;
      this.avoidTicks = 0;
      this.cachedScan = null;
      this.gliderHintShown = false;
      this.launchHintShown = false;
      this.launchFailTicks = 0;
      ShulkerRestock.reset();
      // 关键：把我们按下的跳跃键松开，否则"停止后无法跳跃"
      this.releaseJump(Minecraft.getInstance());
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
         LocalPlayer player = Minecraft.getInstance().player;
         int[] tiers = RocketUtils.countByDuration(player);
         int duration = tiers[3] > 0 ? 3 : (tiers[2] > 0 ? 2 : 1);
         perRocket = 14.0 * duration + 8.0;
      }

      double climb = Math.max(0.0, this.cruiseY - (this.task.lastY <= 0.0 ? this.cruiseY : this.task.lastY));
      double remaining = this.task.getTotalPathBlocks() * (1.0 - this.task.getProgress()) + climb;
      return (int)Math.ceil(remaining / perRocket) + 1;
   }

   public void tick(Minecraft client) {
      ChunkyFlyMod.tickNotice();

      FlyTask current = this.task;

      // 进世界后提示一次"有上次没飞完的任务"（崩溃重进时用 /chunkyfly recover 接着飞）
      if (!TaskStore.isJoinNoticeShown() && client.player != null && client.level != null) {
         TaskStore.markJoinNoticeShown();
         if (this.task == null && TaskStore.hasSaved()) {
            ChunkyFlyMod.warn("检测到上次没飞完的任务：" + TaskStore.describeSaved());
            ChunkyFlyMod.warn("输入 /chunkyfly recover 接着飞；不想继续就 /chunkyfly forget 丢弃。");
         }
      }

      if (current == null) {
         return;
      }

      LocalPlayer player = client.player;
      ClientLevel world = client.level;
      if (player == null || world == null) {
         // 退出世界/切维度时把渲染距离还回去
         RenderDistanceGuard.restore(client);
         return;
      }

      // 每 5 秒把进度写一次盘：万一崩了，重进游戏还能接着飞
      if (current.state == FlyTask.State.RUNNING && --this.saveCooldown <= 0) {
         this.saveCooldown = 20 * 5;
         TaskStore.save(current);
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

      if (player.getHealth() <= 0.0F) {
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
         int fps = client.getFps();
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

      double x = player.getX();
      double y = player.getY();
      double z = player.getZ();
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
         if (client.gui != null) {
            String phase = current.phase == FlyTask.Phase.CLIMBING ? "爬升" : "遍历";
            client.gui.hud
               .setOverlayMessage(
                  net.minecraft.network.chat.Component.literal(
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
            player.setXRot(PITCH_CLIMB);
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
                  player.setYRot((float)Math.toDegrees(Math.atan2(-dx, dz)));
               }
            }
         }

         player.setXRot(PITCH_CLIMB);
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
      this.tickWaypointStall(current);

      int[] waypoint = current.currentWaypoint();
      // 主航线飞完了：如果还有没扫到的区块，先补一条补漏航线（这是"单区块宽的带没被加载到"的兜底）
      if (waypoint == null && current.buildFillWaypoints()) {
         waypoint = current.currentWaypoint();
      }

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
         // 「飞完这一段」= 越过本段终点所在的横截面（见 FlyTask.shouldAdvance）：
         // 这样每一行都会被完整飞过，不会因为提前 2.5 个区块转弯而漏掉一条带
         if (!current.shouldAdvance(x, z)) {
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
      if (scan != null && (scan.wallAhead || scan.impactRisk) && scan.requiredY - y > 30.0 && this.avoidTicks <= 0) {
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
      if (scan != null && (scan.impactRisk || (scan.requiredY != Double.NEGATIVE_INFINITY && scan.requiredY > y))) {
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

      player.setYRot(yaw);
      player.setXRot(pitch);

      double remaining = current.getTotalPathBlocks() * (1.0 - current.getProgress());
      boolean wantBoost = remaining > BOOST_MIN_REMAINING || needClimb;
      if (wantBoost && this.shouldBoost(player, current, needClimb)) {
         RocketUtils.useItemInHand(player);
         this.boostTicks = RocketUtils.boostWindowTicks(RocketUtils.handFlightDuration(player));
         current.rocketsUsed++;
      }

      if (current.getProgress() >= 1.0) {
         this.beginReturn(current);
      } else if (current.allWaypointsDone() && !current.buildFillWaypoints()) {
         this.beginReturn(current);
      }
   }

   /**
    * 航点推进的兜底：万一某个航点因为风向/绕障/卡住而长时间靠不近，就跳过它，避免任务永远卡在这一段。
    * 判据是"到当前航点的距离连续 20 秒没有缩短"，与航段长短无关。
    */
   private void tickWaypointStall(FlyTask current) {
      int[] waypoint = current.currentWaypoint();
      if (waypoint == null) {
         this.wpStallIndex = -1;
         this.wpStallDist = Double.MAX_VALUE;
         this.wpStallTicks = 0;
         return;
      }

      if (current.getWaypointIndex() != this.wpStallIndex) {
         this.wpStallIndex = current.getWaypointIndex();
         this.wpStallDist = Double.MAX_VALUE;
         this.wpStallTicks = 0;
      }

      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null) {
         return;
      }

      double dx = waypoint[0] * 16.0 + 8.0 - player.getX();
      double dz = waypoint[1] * 16.0 + 8.0 - player.getZ();
      double distance = Math.sqrt(dx * dx + dz * dz);
      if (distance < this.wpStallDist - 1.0) {
         this.wpStallDist = distance;
         this.wpStallTicks = 0;
         return;
      }

      if (++this.wpStallTicks > WAYPOINT_STALL_TICKS) {
         this.wpStallTicks = 0;
         this.wpStallDist = Double.MAX_VALUE;
         current.advanceWaypoint();
         ChunkyFlyMod.warn("有航点长时间飞不到，已跳过它继续飞（区段会被后面的航线补上）");
      }
   }

   /** 遍历结束 → 返航（若关掉了"飞完返回出发点"，就地把降落中心设成当前点，直接下降） */
   private void beginReturn(FlyTask current) {
      if (current.phase == FlyTask.Phase.RETURNING || current.phase == FlyTask.Phase.LANDING) {
         return;
      }

      if (!ChunkyFlyConfig.returnToStart()) {
         LocalPlayer player = Minecraft.getInstance().player;
         if (player != null) {
            current.setLandingCenter((int)Math.floor(player.getX()), (int)Math.floor(player.getZ()));
         }

         ChunkyFlyMod.info(String.format(
            "遍历完成（%d/%d 区块，%.1f%%）。已关闭返航，就地下降（降落中心 %d, %d）",
            current.getCoveredChunks(),
            current.getTotalChunks(),
            current.getProgress() * 100.0,
            current.getLandingX(),
            current.getLandingZ()
         ));
         this.enterLanding(current, "开始就地盘旋下降");
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

   /** 进入降落阶段：重置降落相关的所有状态并提示一句 */
   private void enterLanding(FlyTask task, String message) {
      task.phase = FlyTask.Phase.LANDING;
      this.landingTicks = 0;
      this.landingAvoidTicks = 0;
      this.landingAvoids = 0;
      this.landingForceDescent = false;
      this.landingHardTicks = 0;
      ChunkyFlyMod.info(message);
   }

   /** 返航：直线飞回出发坐标上空（沿途走避障逻辑，别撞地形） */
   private void tickReturn(Minecraft client, LocalPlayer player, ClientLevel world, FlyTask task, double x, double y, double z) {
      this.returnTicks++;
      double dx = task.getLandingX() + 0.5 - x;
      double dz = task.getLandingZ() + 0.5 - z;
      double distance = Math.sqrt(dx * dx + dz * dz);
      if (distance <= RETURN_REACH) {
         this.enterLanding(task, "已回到出发坐标上空，开始下降");
         return;
      }

      if (this.returnTicks > RETURN_TIMEOUT) {
         this.enterLanding(task, "返航超时，就地开始下降");
         return;
      }

      // 高度：返航路上尽量保持在高处；低了、或者正前方地形比我们高（比如返航要过一座山）就爬
      TerrainScanner.Scan scan = this.scanThrottled(world, player, 4);
      boolean terrainAhead = scan.requiredY != Double.NEGATIVE_INFINITY && scan.requiredY > y + 20.0;
      // 返航路上同样用预测式判定：下降/俯冲时只比"当前高度"是来不及的
      boolean needClimb = y < this.cruiseY - 120.0 || terrainAhead || scan.impactRisk || scan.lavaAhead;
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

      player.setYRot((float)Math.toDegrees(Math.atan2(-dx, dz)));
      player.setXRot(pitch);

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
   /**
    * 降落：<b>只要落到降落点周围 {@link #LANDING_ACCEPT_RADIUS} 格以内就算成功</b>（允许高度差，
    * 落在旁边的房顶、树上、山坡上都行）。
    * <p>
    * 做法很直接：把飞机"圈"在降落点周围的圆环里 —— 远了一直朝落点飞、略远斜着收进来、
    * 太近往外切、圈内就切向绕圈 —— 同时按离地高度分档温和下降，一直降到贴地为止。
    * 从 2.2.2 起<b>不再做"最后进近 / 冲过头复飞"</b>：那套逻辑在楼多、树多的地方会反复
    * "快到点了又拉起来"，反而落不下去。
    * <p>
    * 只有预测到"真的要撞墙"时才大幅拉起 + 侧转避让（有次数上限；贴地 {@link #LANDING_COMMIT_AGL}
    * 格以内、或已进入强制下降时不再避让）。
    */
   private void tickLanding(Minecraft client, LocalPlayer player, ClientLevel world, FlyTask task, double x, double y, double z) {
      this.landingTicks++;
      double dx = task.getLandingX() + 0.5 - x;
      double dz = task.getLandingZ() + 0.5 - z;
      double distance = Math.sqrt(dx * dx + dz * dz);

      TerrainScanner.Scan scan = this.scanThrottled(world, player, 2);
      double groundY = scan.groundY == Double.NEGATIVE_INFINITY ? 0.0 : scan.groundY;
      double agl = y - groundY;
      double baseYaw = Math.toDegrees(Math.atan2(-dx, dz));
      // 贴地了：不再避让，直接落 —— 否则树/矮墙会让它一直拉起来，落不下去
      boolean commit = agl <= LANDING_COMMIT_AGL;

      // 超时保护：不交还控制权，切成强制下降（较陡但固定的俯角），一路降到贴地
      if (!this.landingForceDescent && this.landingTicks > LANDING_TIMEOUT) {
         this.landingForceDescent = true;
         this.landingHardTicks = 0;
         ChunkyFlyMod.warn("下降花的时间偏长，改为强制下降（会一直降到落地才交还控制权）");
      }

      if (this.landingForceDescent) {
         this.landingHardTicks++;
      }

      // 预测式避让：只有"真的会撞上"才拉起 + 侧转；贴地、强制下降或次数用完都不再避让
      boolean impact = scan.impactRisk && !commit && !this.landingForceDescent;
      boolean impactNear = impact && scan.impactDistance <= IMPACT_NEAR_DIST;
      if (impact && this.landingAvoids < MAX_LANDING_AVOIDS) {
         this.avoidSide = scan.avoidSide == 0 ? 1 : scan.avoidSide;
         if (impactNear) {
            this.landingAvoidTicks = IMPACT_AVOID_TICKS;
            this.landingAvoids++;
         }
      } else {
         impact = false;
         impactNear = false;
      }

      float yaw;
      float pitch;
      if (impactNear) {
         // 真要撞上了：大幅抬头拉起来（下面会立刻补烟花），同时侧转绕开
         yaw = (float)baseYaw;
         pitch = PITCH_IMPACT;
      } else if (this.landingForceDescent) {
         // 强制下降：仍然圈在落点附近，但用固定俯角一路压下去
         yaw = ringYaw(distance, baseYaw);
         pitch = FORCE_DESCENT_PITCH;
      } else {
         // 常规下降：把飞机圈在落点周围 LANDING_ACCEPT_RADIUS 的圆环里，同时按高度分档温和下降
         yaw = ringYaw(distance, baseYaw);
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
      }

      // 侧转避让：本 tick 判定有风险，或还在保持期内，都往同一侧偏（免得来回摆）
      boolean avoiding = impactNear || this.landingAvoidTicks > 0;
      if (this.landingAvoidTicks > 0) {
         this.landingAvoidTicks--;
      }

      if (avoiding && !this.landingForceDescent) {
         yaw += this.avoidSide * AVOID_YAW_OFFSET;
      }

      player.setYRot(yaw);

      // 前方地形比我们高 → 先抬头绕/爬过去，别一头撞进山体或建筑
      boolean terrainAhead = scan.requiredY != Double.NEGATIVE_INFINITY && scan.requiredY > y + 20.0;
      if (!this.landingForceDescent && !commit) {
         if (scan.lavaAhead) {
            pitch = PITCH_AVOID;
         } else if (impact && !impactNear) {
            pitch = Math.min(pitch, PITCH_AVOID);
         } else if (terrainAhead) {
            pitch = Math.min(pitch, PITCH_AVOID);
         }
      }

      // 快贴地时抬头"拉平"：把下降率收小，避免以高速拍在地上
      if (!impactNear && agl <= 3.0 && pitch > 0.0F && !terrainAhead) {
         pitch = PITCH_LAND_FINAL - 6.0F;
      }

      // 天花板保护：头顶 12 格内有方块就别再抬头
      if (scan.ceilingY - y < 12.0 && pitch < 0.0F) {
         pitch = 0.0F;
      }

      player.setXRot(pitch);

      // 快失速了才补一发；撞墙风险要主动补（拉起全靠它），贴地前不点火
      boolean needRocket = impact || (agl > 40.0 && horizontalSpeed(player) < 14.0);
      if (this.shouldBoost(player, task, terrainAhead) && needRocket) {
         RocketUtils.useItemInHand(player);
         this.boostTicks = RocketUtils.boostWindowTicks(RocketUtils.handFlightDuration(player));
         task.rocketsUsed++;
      }

      if (this.boostTicks > 0) {
         this.boostTicks--;
      }

      // 落地 / 贴地（离地 ≤2 格，落点高度不限）/ 已经不在滑翔 → 交还控制权。
      // 只有强制下降也始终降不下来（极端地形）时才在超时后放弃，并会提示还在空中。
      if (player.onGround()
         || agl <= LANDING_TOUCH_AGL
         || !RocketUtils.isGliding(player)
         || (this.landingForceDescent && this.landingHardTicks > LANDING_FORCE_TIMEOUT)) {
         this.finish(task);
      }
   }

   /**
    * 把飞机圈在降落点周围 {@link #LANDING_ACCEPT_RADIUS} 格的圆环里：
    * 太远就直接朝落点飞、略远斜着收进来、太近往外切、圈内就切向绕圈。
    * 只要一直在这个环里下降，落点自然就在接受半径以内（允许高度差）。
    */
   private static float ringYaw(double distance, double baseYaw) {
      if (distance > LANDING_ACCEPT_RADIUS * 1.5) {
         return (float)baseYaw;
      }

      if (distance > LANDING_ACCEPT_RADIUS) {
         return (float)(baseYaw + RING_INWARD_OFFSET);
      }

      if (distance < LANDING_ACCEPT_RADIUS * 0.5) {
         return (float)(baseYaw + RING_OUTWARD_OFFSET);
      }

      return (float)(baseYaw + RING_TANGENT_OFFSET);
   }

   /** 带冷却的地形扫描（冷却在每 tick 开头统一递减，这里只负责到期重建） */
   private TerrainScanner.Scan scanThrottled(ClientLevel world, LocalPlayer player, int cooldown) {
      if (this.scanCooldown <= 0 || this.cachedScan == null) {
         this.cachedScan = TerrainScanner.scan(world, player);
         this.scanCooldown = cooldown;
      }

      return this.cachedScan;
   }

   private static double horizontalSpeed(LocalPlayer player) {
      double vx = player.getDeltaMovement().x;
      double vz = player.getDeltaMovement().z;
      return Math.sqrt(vx * vx + vz * vz) * 20.0;
   }

   private void finish(FlyTask current) {
      current.state = FlyTask.State.DONE;
      this.boostTicks = 0;
      // 正常收工：清掉崩溃续跑存档
      TaskStore.clear();
      LocalPlayer player = Minecraft.getInstance().player;
      double landingOffset = player == null
         ? 0.0
         : Math.sqrt(
            (current.centerX + 0.5 - player.getX()) * (current.centerX + 0.5 - player.getX())
               + (current.centerZ + 0.5 - player.getZ()) * (current.centerZ + 0.5 - player.getZ())
         );
      int uncovered = current.countUncovered();
      ChunkyFlyMod.info(
         String.format(
            "任务完成：%d/%d 个区块覆盖（%.1f%%），落地偏离降落点 %.1f 格（接受范围 %.0f 格内、允许高度差），用了 %d 个烟花，飞行 %.0f 格",
            current.getCoveredChunks(),
            current.getTotalChunks(),
            current.getProgress() * 100.0,
            landingOffset,
            LANDING_ACCEPT_RADIUS,
            current.rocketsUsed,
            current.blocksFlown
         )
      );

      if (player != null && !player.onGround()) {
         ChunkyFlyMod.warn(
            "注意：交还控制权时你还在空中（Y=" + (int)player.getY() + "），请自己滑翔/落地。"
         );
      }

      if (uncovered > 0) {
         ChunkyFlyMod.warn(
            "还有 " + uncovered + " 个区块没能覆盖到（可能一直飞不进去）；可以再用 /chunkyfly corner <x1> <z1> <x2> <z2> 单独补一小块"
         );
      } else if (player == null || player.onGround()) {
         ChunkyFlyMod.info("已交还控制权，可以自己走两步了。");
      }
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
   private void launch(Minecraft client, LocalPlayer player) {
      if (player.onGround()) {
         if (this.jumpKeyOwned) {
            this.releaseJump(client);
         }

         if (this.jumpCooldown <= 0) {
            client.options.keyJump.setDown(true);
            this.jumpKeyOwned = true;
            this.jumpCooldown = 8;
         }

         return;
      }

      this.releaseJump(client);
      if (player.getDeltaMovement().y <= 0.0 && client.getConnection() != null) {
         client.getConnection()
            .send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
         player.startFallFlying();
      }
   }

   /** 松开我们按下的跳跃键（只在自己按过的时候动，绝不干扰玩家自己按的键） */
   private void releaseJump(Minecraft client) {
      if (this.jumpKeyOwned) {
         client.options.keyJump.setDown(false);
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
   private boolean shouldBoost(LocalPlayer player, FlyTask task, boolean urgent) {
      if (!RocketUtils.isGliding(player) || !RocketUtils.isSafeRocket(player.getMainHandItem())) {
         return false;
      }

      if (this.boostTicks > 0) {
         double vx = player.getDeltaMovement().x;
         double vz = player.getDeltaMovement().z;
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
      LocalPlayer player = Minecraft.getInstance().player;
      int[] tiers = RocketUtils.countByDuration(player);
      String line1 = String.format("ChunkyFly  %.1f%%", current.getProgress() * 100.0);
      String line2 = String.format(
         "%s | 区块 %d/%d | Y=%d | 烟花 3速%d 2速%d 1速%d | 盒中%d（还需约 %d，已用 %d）",
         stateText,
         current.getCoveredChunks(),
         current.getTotalChunks(),
         (int)player.getY(),
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

      // 第三行：任务运行期间一直显示的两条安全提示（光敏性癫痫 + 崩溃可续跑）
      String line3 = ChunkyFlyMod.PERSISTENT_NOTICE;

      return new String[]{line1, line2, line3};
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

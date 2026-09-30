package com.chunkyfly;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务进度存档：{@code config/chunkyfly-task.json}。
 * <p>
 * 用途是<b>崩溃后续跑</b>：这个模组在飞大范围时可能把游戏搞崩（渲染压力 / 原版动态缓冲那类问题），
 * 崩了以后重进游戏，只要进度文件还在，就能用 {@code /chunkyfly recover} 从上次飞到的航点接着飞，
 * 不用从头再来。
 * <p>
 * 只存"重新飞起来所必需"的少量整数：区域范围、出发坐标、覆盖判定半径、当前航点下标。
 * 进度百分比会用航线几何反推着补上（见 {@link FlyTask#premarkRoute}）。
 * 和 {@link ChunkyFlyConfig} 一样不引 gson，只用几行正则读写。
 */
public final class TaskStore {
   private static final Logger LOGGER = LoggerFactory.getLogger(ChunkyFlyMod.MOD_ID + "/task");
   private static final String FILE_NAME = "chunkyfly-task.json";

   private static final Pattern HOME_X = Pattern.compile("\"homeX\"\\s*:\\s*(-?\\d+)");
   private static final Pattern HOME_Z = Pattern.compile("\"homeZ\"\\s*:\\s*(-?\\d+)");
   private static final Pattern MIN_CX = Pattern.compile("\"minChunkX\"\\s*:\\s*(-?\\d+)");
   private static final Pattern MAX_CX = Pattern.compile("\"maxChunkX\"\\s*:\\s*(-?\\d+)");
   private static final Pattern MIN_CZ = Pattern.compile("\"minChunkZ\"\\s*:\\s*(-?\\d+)");
   private static final Pattern MAX_CZ = Pattern.compile("\"maxChunkZ\"\\s*:\\s*(-?\\d+)");
   private static final Pattern MARK_RADIUS = Pattern.compile("\"markRadius\"\\s*:\\s*(\\d+)");
   private static final Pattern WAYPOINT = Pattern.compile("\"waypointIndex\"\\s*:\\s*(\\d+)");
   private static final Pattern SAVED_AT = Pattern.compile("\"savedAt\"\\s*:\\s*(\\d+)");

   private static Path file;
   /** 这次启动是否已经提示过"有可恢复的任务" */
   private static boolean joinNoticeShown;

   private TaskStore() {
   }

   public static Path file() {
      if (file == null) {
         file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
      }

      return file;
   }

   /** 有没有存档 */
   public static boolean hasSaved() {
      try {
         return Files.exists(file());
      } catch (Throwable t) {
         return false;
      }
   }

   /** 存一份进度（飞行中每几秒调一次；失败只记日志，绝不影响飞行） */
   public static void save(FlyTask task) {
      if (task == null) {
         return;
      }

      try {
         Path path = file();
         Files.createDirectories(path.getParent());
         String text = "{\n"
            + "  \"homeX\": " + task.centerX + ",\n"
            + "  \"homeZ\": " + task.centerZ + ",\n"
            + "  \"minChunkX\": " + task.getMinChunkX() + ",\n"
            + "  \"maxChunkX\": " + task.getMaxChunkX() + ",\n"
            + "  \"minChunkZ\": " + task.getMinChunkZ() + ",\n"
            + "  \"maxChunkZ\": " + task.getMaxChunkZ() + ",\n"
            + "  \"markRadius\": " + task.getMarkRadius() + ",\n"
            + "  \"waypointIndex\": " + task.getWaypointIndex() + ",\n"
            + "  \"waypointCount\": " + task.getWaypointCount() + ",\n"
            + "  \"phase\": \"" + task.phase.name() + "\",\n"
            + "  \"fillRounds\": " + task.getFillRounds() + ",\n"
            + "  \"coveredChunks\": " + task.getCoveredChunks() + ",\n"
            + "  \"totalChunks\": " + task.getTotalChunks() + ",\n"
            + "  \"savedAt\": " + System.currentTimeMillis() + ",\n"
            + "  \"_comment\": \"崩溃后重进游戏，用 /chunkyfly recover 从这里接着飞；/chunkyfly forget 丢弃。\"\n"
            + "}\n";
         Files.write(path, text.getBytes(StandardCharsets.UTF_8));
      } catch (Throwable t) {
         LOGGER.warn("保存任务进度失败：{}", t.toString());
      }
   }

   /**
    * 读回上次的任务；没有/坏了返回 null。
    * <p>
    * 读回来的任务会把航点下标恢复到存档位置，并用航线几何把"已经飞过的那些行"对应的区块
    * 预先标成已覆盖 —— 这样进度百分比不会从 0 开始。
    */
   public static FlyTask load() {
      try {
         Path path = file();
         if (!Files.exists(path)) {
            return null;
         }

         String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
         int homeX = readInt(HOME_X, text, 0);
         int homeZ = readInt(HOME_Z, text, 0);
         int minCx = readInt(MIN_CX, text, 0);
         int maxCx = readInt(MAX_CX, text, 0);
         int minCz = readInt(MIN_CZ, text, 0);
         int maxCz = readInt(MAX_CZ, text, 0);
         int markRadius = Math.max(1, readInt(MARK_RADIUS, text, 4));
         int waypointIndex = Math.max(0, readInt(WAYPOINT, text, 0));
         if (maxCx < minCx || maxCz < minCz) {
            return null;
         }

         FlyTask task = FlyTask.rectangle(homeX, homeZ, minCx, minCz, maxCx, maxCz, markRadius);
         task.premarkRoute(waypointIndex);
         task.setWaypointIndex(waypointIndex);
         return task;
      } catch (Throwable t) {
         LOGGER.warn("读取任务进度失败（忽略这份存档）：{}", t.toString());
         return null;
      }
   }

   /** 存档的时间戳（毫秒；读不到返回 0） */
   public static long savedAt() {
      try {
         String text = new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
         return readInt(SAVED_AT, text, 0);
      } catch (Throwable t) {
         return 0L;
      }
   }

   /** 丢掉存档（任务正常完成 / 被取消 / 玩家主动丢弃时调） */
   public static void clear() {
      try {
         Files.deleteIfExists(file());
      } catch (Throwable t) {
         LOGGER.warn("删除任务进度失败：{}", t.toString());
      }
   }

   /** 这次启动是否已经提示过"有可恢复的任务" */
   public static boolean isJoinNoticeShown() {
      return joinNoticeShown;
   }

   public static void markJoinNoticeShown() {
      joinNoticeShown = true;
   }

   /** 给提示用的一句话描述 */
   public static String describeSaved() {
      try {
         String text = new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
         int minCx = readInt(MIN_CX, text, 0);
         int maxCx = readInt(MAX_CX, text, 0);
         int minCz = readInt(MIN_CZ, text, 0);
         int maxCz = readInt(MAX_CZ, text, 0);
         int waypoints = readInt(Pattern.compile("\"waypointCount\"\\s*:\\s*(\\d+)"), text, 0);
         int index = readInt(WAYPOINT, text, 0);
         int covered = readInt(Pattern.compile("\"coveredChunks\"\\s*:\\s*(\\d+)"), text, 0);
         int total = readInt(Pattern.compile("\"totalChunks\"\\s*:\\s*(\\d+)"), text, 0);
         return String.format(
            "区域 %d×%d 区块（%d,%d ~ %d,%d），航点 %d/%d，上次记录覆盖 %d/%d 区块",
            maxCx - minCx + 1,
            maxCz - minCz + 1,
            minCx,
            minCz,
            maxCx,
            maxCz,
            index,
            waypoints,
            covered,
            total
         );
      } catch (Throwable t) {
         return "（存档内容读不出来）";
      }
   }

   private static int readInt(Pattern pattern, String text, int fallback) {
      Matcher matcher = pattern.matcher(text);
      return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
   }
}

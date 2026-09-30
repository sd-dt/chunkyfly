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
 * 配置：{@code config/chunkyfly.json}。
 * <p>
 * 为了不给低版本 Fabric Loader 增加任何依赖，这里**不用 gson**，只用几行正则读写这几个整数：
 * 文件坏了/缺失就回到默认值，绝不影响模组运行。改动用指令完成并立即存盘：
 * <ul>
 *   <li>{@code /chunkyfly hud <x> <y>} —— 左上角进度文字的像素偏移（hudX 向右、hudY 向下）；</li>
 *   <li>{@code /chunkyfly cruise <Y>} —— 巡航高度，默认 1000（低一点能大幅减少可见区块、省性能）；</li>
 *   <li>{@code /chunkyfly rd <n|off>} —— 飞行期间临时压小的渲染距离，默认 8，{@code off}=0 关闭该保护；</li>
 *   <li>{@code /chunkyfly return <on|off>} —— 飞完之后要不要返回出发点（默认 on；off = 就地下降落地）。</li>
 * </ul>
 */
public final class ChunkyFlyConfig {
   private static final Logger LOGGER = LoggerFactory.getLogger(ChunkyFlyMod.MOD_ID + "/config");
   /** HUD 默认位置（原版左上角，像素） */
   public static final int DEFAULT_HUD_X = 4;
   public static final int DEFAULT_HUD_Y = 4;
   /** 允许的偏移范围（像素） */
   public static final int MIN_OFFSET = 0;
   public static final int MAX_OFFSET = 4000;
   /** 巡航高度：默认 1000，范围 100~2000 */
   public static final int DEFAULT_CRUISE_Y = 1000;
   public static final int MIN_CRUISE_Y = 100;
   public static final int MAX_CRUISE_Y = 2000;
   /** 飞行期间临时压到的渲染距离：默认 8；0 = 不改（关掉这个保护） */
   public static final int DEFAULT_FLIGHT_RENDER_DISTANCE = 8;
   public static final int MIN_RENDER_DISTANCE = 4;
   public static final int MAX_RENDER_DISTANCE = 32;
   /** 飞完之后是否返回出发点：默认 true；false = 就地（飞完的那一点）盘旋下降落地 */
   public static final boolean DEFAULT_RETURN_TO_START = true;
   private static final Pattern HUD_X = Pattern.compile("\"hudX\"\\s*:\\s*(-?\\d+)");
   private static final Pattern HUD_Y = Pattern.compile("\"hudY\"\\s*:\\s*(-?\\d+)");
   private static final Pattern CRUISE_Y = Pattern.compile("\"cruiseY\"\\s*:\\s*(-?\\d+)");
   private static final Pattern FLIGHT_RD = Pattern.compile("\"flightRenderDistance\"\\s*:\\s*(-?\\d+)");
   private static final Pattern RETURN_HOME = Pattern.compile("\"returnToStart\"\\s*:\\s*(true|false)");
   private static int hudX = DEFAULT_HUD_X;
   private static int hudY = DEFAULT_HUD_Y;
   private static int cruiseY = DEFAULT_CRUISE_Y;
   private static int flightRenderDistance = DEFAULT_FLIGHT_RENDER_DISTANCE;
   private static boolean returnToStart = DEFAULT_RETURN_TO_START;
   private static Path file;

   private ChunkyFlyConfig() {
   }

   public static int hudX() {
      return hudX;
   }

   public static int hudY() {
      return hudY;
   }

   /** 目标巡航高度（方块） */
   public static int cruiseY() {
      return cruiseY;
   }

   /** 飞行期间要压到的渲染距离；0 = 关闭这个保护（不改用户的渲染距离） */
   public static int flightRenderDistance() {
      return flightRenderDistance;
   }

   /** 飞完之后是否返回出发点 */
   public static boolean returnToStart() {
      return returnToStart;
   }

   /** 设置"飞完之后是否返回出发点"并立即存盘（下次 /chunkyfly <半径> 生效） */
   public static void setReturnToStart(boolean value) {
      returnToStart = value;
      save();
   }

   /** 设置 HUD 偏移（自动夹到合法范围）并立即存盘 */
   public static void setHud(int x, int y) {
      hudX = clamp(x, MIN_OFFSET, MAX_OFFSET);
      hudY = clamp(y, MIN_OFFSET, MAX_OFFSET);
      save();
   }

   public static void resetHud() {
      hudX = DEFAULT_HUD_X;
      hudY = DEFAULT_HUD_Y;
      save();
   }

   /** 设置巡航高度并立即存盘（下次 {@code /chunkyfly <半径>} 生效） */
   public static void setCruiseY(int y) {
      cruiseY = clamp(y, MIN_CRUISE_Y, MAX_CRUISE_Y);
      save();
   }

   /** 设置"飞行期间压小的渲染距离"；0 = 关闭 */
   public static void setFlightRenderDistance(int n) {
      flightRenderDistance = n <= 0 ? 0 : clamp(n, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
      save();
   }

   /** 从磁盘读配置；没有就写一份默认的，坏了就用默认值 */
   public static void load() {
      try {
         Path path = file();
         if (!Files.exists(path)) {
            save();
            return;
         }

         String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
         Matcher x = HUD_X.matcher(text);
         if (x.find()) {
            hudX = clamp(Integer.parseInt(x.group(1)), MIN_OFFSET, MAX_OFFSET);
         }

         Matcher y = HUD_Y.matcher(text);
         if (y.find()) {
            hudY = clamp(Integer.parseInt(y.group(1)), MIN_OFFSET, MAX_OFFSET);
         }

         Matcher cruise = CRUISE_Y.matcher(text);
         if (cruise.find()) {
            cruiseY = clamp(Integer.parseInt(cruise.group(1)), MIN_CRUISE_Y, MAX_CRUISE_Y);
         }

         Matcher home = RETURN_HOME.matcher(text);
         if (home.find()) {
            returnToStart = Boolean.parseBoolean(home.group(1));
         }

         Matcher rd = FLIGHT_RD.matcher(text);
         if (rd.find()) {
            int value = Integer.parseInt(rd.group(1));
            flightRenderDistance = value <= 0 ? 0 : clamp(value, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
         }
      } catch (Throwable t) {
         LOGGER.warn("读取 chunkyfly.json 失败，改用默认配置：{}", t.toString());
      }
   }

   /** 存盘（失败只记日志，不影响游戏） */
   public static void save() {
      try {
         Path path = file();
         Files.createDirectories(path.getParent());
         String text = "{\n"
            + "  \"hudX\": " + hudX + ",\n"
            + "  \"hudY\": " + hudY + ",\n"
            + "  \"cruiseY\": " + cruiseY + ",\n"
            + "  \"flightRenderDistance\": " + flightRenderDistance + ",\n"
            + "  \"_comment\": \"hudX/hudY：左上角进度文字的像素偏移（向右/向下）。cruiseY：巡航高度。"
            + "flightRenderDistance：飞行期间临时压到的渲染距离，0=不改。returnToStart：飞完是否返回出发点（false=就地下降）。指令：/chunkyfly hud|cruise|rd|return\"\n"
            + "}\n";
         Files.write(path, text.getBytes(StandardCharsets.UTF_8));
      } catch (Throwable t) {
         LOGGER.warn("保存 chunkyfly.json 失败：{}", t.toString());
      }
   }

   public static Path file() {
      if (file == null) {
         file = FabricLoader.getInstance().getConfigDir().resolve("chunkyfly.json");
      }

      return file;
   }

   private static int clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }
}

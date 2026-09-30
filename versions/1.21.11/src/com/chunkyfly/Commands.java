package com.chunkyfly;

import net.minecraft.class_2561;
import net.minecraft.class_310;

/**
 * 指令解析（在客户端聊天/指令发出前拦下来，不会发到服务器）。
 * <p>
 * 支持的写法：
 * <pre>
 * chunkyfly &lt;半径方块数&gt;            从当前位置开始，自动飞行加载该半径内的区块
 * chunkyfly radius &lt;半径方块数&gt;     同上（可加 chunks 表示按区块计）
 * chunkyfly pause / stop            暂停（保留任务，可 continue）
 * chunkyfly continue / resume       继续上次任务
 * chunkyfly cancel                  取消所有任务
 * chunkyfly status                  查看进度
 * chunkyfly help                    帮助
 * </pre>
 * 直接输入 {@code /chunkyfly ...} 也一样（发送指令的路径同样被拦截）。
 */
public final class Commands {
   public static final String PREFIX = "chunkyfly";

   private Commands() {
   }

   /** 是否是本模组的指令 */
   public static boolean matches(String message) {
      if (message == null) {
         return false;
      }

      String text = message.trim();
      if (text.startsWith("/")) {
         text = text.substring(1).trim();
      }

      return text.equalsIgnoreCase(PREFIX) || text.toLowerCase().startsWith(PREFIX + " ");
   }

   /** 处理指令；返回 true 表示已处理（调用方应取消这条消息） */
   public static boolean handle(String message) {
      String text = message.trim();
      if (text.startsWith("/")) {
         text = text.substring(1).trim();
      }

      String[] parts = text.split("\\s+");
      if (parts.length == 0 || !parts[0].equalsIgnoreCase(PREFIX)) {
         return false;
      }

      class_310 client = class_310.method_1551();
      FlyController controller = FlyController.INSTANCE;
      if (parts.length == 1) {
         printHelp();
         return true;
      }

      String sub = parts[1].toLowerCase();
      switch (sub) {
         case "help", "?" -> printHelp();
         case "pause", "stop" -> controller.pause();
         case "continue", "resume" -> controller.resume();
         case "cancel" -> controller.cancel();
         case "status", "info" -> ChunkyFlyMod.info(controller.statusText());
         case "hud" -> handleHud(parts);
         case "cruise", "cruisey" -> handleCruise(parts);
         case "rd", "renderdistance" -> handleRenderDistance(parts);
         case "radius", "start", "fly" -> {
            if (parts.length < 3) {
               ChunkyFlyMod.warn("用法：" + PREFIX + " radius <半径方块数> [chunks]");
               return true;
            }

            Integer radius = parseInt(parts[2]);
            if (radius == null) {
               ChunkyFlyMod.warn("半径必须是数字，例如：" + PREFIX + " radius 1000");
               return true;
            }

            boolean chunks = parts.length >= 4 && parts[3].equalsIgnoreCase("chunks");
            controller.start(client.field_1724, chunks ? radius * 16 : radius);
         }
         default -> {
            Integer radius = parseInt(sub);
            if (radius == null) {
               ChunkyFlyMod.warn("未知子指令：" + sub + "（用 " + PREFIX + " help 查看用法）");
            } else {
               controller.start(client.field_1724, radius);
            }
         }
      }

      return true;
   }

   private static Integer parseInt(String text) {
      try {
         int value = Integer.parseInt(text);
         return value > 0 ? value : null;
      } catch (NumberFormatException e) {
         return null;
      }
   }

   /**
    * {@code /chunkyfly hud [<x> <y> | reset]}：查看/修改左上角进度文字的像素偏移。
    * 和 MiniHUD 之类的 HUD 挤在一起时，把它挪开（hudX 向右、hudY 向下），改完立即存进 config/chunkyfly.json。
    */
   private static void handleHud(String[] parts) {
      if (parts.length >= 3 && parts[2].equalsIgnoreCase("reset")) {
         ChunkyFlyConfig.resetHud();
         printHud();
         return;
      }

      if (parts.length >= 4) {
         Integer x = parseOffset(parts[2]);
         Integer y = parseOffset(parts[3]);
         if (x == null || y == null) {
            ChunkyFlyMod.warn("用法：" + PREFIX + " hud <x> <y>（0~" + ChunkyFlyConfig.MAX_OFFSET + " 像素），或 " + PREFIX + " hud reset");
            return;
         }

         ChunkyFlyConfig.setHud(x, y);
      } else if (parts.length == 3) {
         ChunkyFlyMod.warn("用法：" + PREFIX + " hud <x> <y>（0~" + ChunkyFlyConfig.MAX_OFFSET + " 像素），或 " + PREFIX + " hud reset");
         return;
      }

      printHud();
   }

   /** 打印当前 HUD 位置 */
   public static void printHud() {
      ChunkyFlyMod.info(String.format(
         "HUD 位置：hudX=%d hudY=%d（像素偏移，屏幕左上角为 0,0；已存 config/chunkyfly.json）",
         ChunkyFlyConfig.hudX(),
         ChunkyFlyConfig.hudY()
      ));
   }

   /** 偏移解析：允许 0，允许负数（会夹到 0） */
   private static Integer parseOffset(String text) {
      try {
         return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
         return null;
      }
   }

   /** {@code /chunkyfly cruise [<Y>]}：查看/设置巡航高度（默认 1000） */
   private static void handleCruise(String[] parts) {
      if (parts.length >= 3) {
         Integer y = parseOffset(parts[2]);
         if (y == null) {
            ChunkyFlyMod.warn(
               "用法：" + PREFIX + " cruise <Y>（" + ChunkyFlyConfig.MIN_CRUISE_Y + "~" + ChunkyFlyConfig.MAX_CRUISE_Y + "）"
            );
            return;
         }

         ChunkyFlyConfig.setCruiseY(y);
      }

      printCruise();
   }

   /** 打印巡航高度 */
   public static void printCruise() {
      ChunkyFlyMod.info("巡航高度：Y=" + ChunkyFlyConfig.cruiseY() + "（下次 " + PREFIX + " <半径> 时生效）");
   }

   /** {@code /chunkyfly rd [<n>|off]}：查看/设置"飞行期间临时压到的渲染距离" */
   private static void handleRenderDistance(String[] parts) {
      if (parts.length >= 3) {
         String value = parts[2];
         if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false") || value.equals("0")) {
            ChunkyFlyConfig.setFlightRenderDistance(0);
            printRenderDistance();
            return;
         }

         Integer n = parseOffset(value);
         if (n == null) {
            ChunkyFlyMod.warn(
               "用法："
                  + PREFIX
                  + " rd <n> 或 "
                  + PREFIX
                  + " rd off（n = "
                  + ChunkyFlyConfig.MIN_RENDER_DISTANCE
                  + "~"
                  + ChunkyFlyConfig.MAX_RENDER_DISTANCE
                  + "）"
            );
            return;
         }

         ChunkyFlyConfig.setFlightRenderDistance(n);
      }

      printRenderDistance();
   }

   /** 打印"飞行期间压小的渲染距离"设置 */
   public static void printRenderDistance() {
      int limit = ChunkyFlyConfig.flightRenderDistance();
      if (limit <= 0) {
         ChunkyFlyMod.info("飞行期间不改渲染距离（该保护已关闭）。想开启：" + PREFIX + " rd 8");
      } else {
         ChunkyFlyMod.info(
            "飞行期间会把渲染距离临时压到 " + limit + "，暂停/结束/取消后自动恢复（关闭：" + PREFIX + " rd off）"
         );
      }
   }

   /** 打印帮助（指令 /chunkyfly help 与聊天拦截共用） */
   public static void printHelp() {
      ChunkyFlyMod.info("===== ChunkyFly " + ChunkyFlyMod.version() + " 用法 =====");
      ChunkyFlyMod.info(PREFIX + " <半径>            例：" + PREFIX + " 1000（方块半径，从当前位置开始）");
      ChunkyFlyMod.info(PREFIX + " radius <半径> [chunks]   同上；带 chunks 表示按区块算半径");
      ChunkyFlyMod.info(PREFIX + " pause / stop      暂停飞行（保留任务）");
      ChunkyFlyMod.info(PREFIX + " continue          继续上次任务");
      ChunkyFlyMod.info(PREFIX + " cancel            取消所有任务");
      ChunkyFlyMod.info(PREFIX + " status            查看进度");
      ChunkyFlyMod.info(PREFIX + " hud <x> <y>       挪动左上角进度文字（像素偏移；" + PREFIX + " hud reset 复位）");
      ChunkyFlyMod.info(PREFIX + " cruise <Y>        设置巡航高度（默认 1000；越低可见区块越少、越省性能）");
      ChunkyFlyMod.info(PREFIX + " rd <n|off>        飞行期间临时压到的渲染距离（默认 8，off 关闭该保护）");
      ChunkyFlyMod.info("说明：需要穿鞘翅 + 背包里有非爆炸烟花；会自动从快捷栏/背包/潜影盒取烟花并按最快方式飞行。");
   }

   /** 给客户端聊天栏发一条本地消息（不会发到服务器） */
   public static void chat(String text) {
      class_310 client = class_310.method_1551();
      if (client.field_1705 != null) {
         client.field_1705.method_1743().method_1812(class_2561.method_43470(text));
      }
   }
}

package com.chunkyfly;

import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

/**
 * 指令解析（在客户端聊天/指令发出前拦下来，不会发到服务器）。
 * <p>
 * 支持的写法：
 * <pre>
 * chunkyfly &lt;半径方块数&gt;            从当前位置开始，自动飞行加载该半径内的区块
 * chunkyfly radius &lt;半径方块数&gt;     同上（可加 chunks 表示按区块计）
 * chunkyfly corner &lt;x1&gt; &lt;z1&gt; &lt;x2&gt; &lt;z2&gt;   加载矩形区域（两个角点的方块坐标）
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

      Minecraft client = Minecraft.getInstance();
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
         case "return", "back", "home" -> handleReturn(parts);
         case "corner" -> handleCorner(parts);
         case "recover", "restore" -> FlyController.INSTANCE.recover();
         case "forget", "discard" -> FlyController.INSTANCE.forgetSaved();
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
            controller.start(client.player, chunks ? radius * 16 : radius);
         }
         default -> {
            Integer radius = parseInt(sub);
            if (radius == null) {
               ChunkyFlyMod.warn("未知子指令：" + sub + "（用 " + PREFIX + " help 查看用法）");
            } else {
               controller.start(client.player, radius);
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

   /** 坐标解析：允许负数与 0（方块坐标） */
   private static Integer parseCoord(String text) {
      try {
         int value = Integer.parseInt(text.trim());
         return Math.abs(value) <= 30000000 ? value : null;
      } catch (NumberFormatException e) {
         return null;
      }
   }

   /**
    * {@code /chunkyfly corner <x1> <z1> <x2> <z2>}：加载矩形区域。
    * <p>
    * 四个参数是<b>两个角点</b>的方块坐标（(x1,z1) 与 (x2,z2)）：取它们所在的区块组成一个轴对齐矩形。
    * 两个角的先后、大小都无所谓；坐标只取所在区块，所以填区块里的任意一格都行。
    */
   private static void handleCorner(String[] parts) {
      if (parts.length < 6) {
         ChunkyFlyMod.warn(
            "用法：" + PREFIX + " corner <x1> <z1> <x2> <z2>（两个角点的方块坐标），例：" + PREFIX + " corner 2000 -3000 3000 -1000"
         );
         return;
      }

      Integer x1 = parseCoord(parts[2]);
      Integer z1 = parseCoord(parts[3]);
      Integer x2 = parseCoord(parts[4]);
      Integer z2 = parseCoord(parts[5]);
      if (x1 == null || z1 == null || x2 == null || z2 == null) {
         ChunkyFlyMod.warn(
            "x1 / z1 / x2 / z2 必须是整数方块坐标（-30000000 ~ 30000000），例如：" + PREFIX + " corner 2000 -3000 3000 -1000"
         );
         return;
      }

      FlyController.INSTANCE.startCorner(Minecraft.getInstance().player, x1, z1, x2, z2);
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

   /**
    * {@code /chunkyfly return [on|off]}：飞完之后要不要返回出发点。
    * <p>
    * {@code on}（默认）= 飞完自动返航到出发点再降落；{@code off} = 不返航，就在飞完的那一点就地盘旋下降落地。
    * 设置存进 {@code config/chunkyfly.json}，下次下指令生效。
    */
   private static void handleReturn(String[] parts) {
      if (parts.length >= 3) {
         String value = parts[2].toLowerCase();
         if (value.equals("on") || value.equals("true") || value.equals("yes") || value.equals("1")) {
            ChunkyFlyConfig.setReturnToStart(true);
         } else if (value.equals("off") || value.equals("false") || value.equals("no") || value.equals("0")) {
            ChunkyFlyConfig.setReturnToStart(false);
         } else {
            ChunkyFlyMod.warn("用法：" + PREFIX + " return on|off（当前：" + (ChunkyFlyConfig.returnToStart() ? "on" : "off") + "）");
            return;
         }
      }

      printReturn();
   }

   /** 打印"飞完是否返回出发点"设置 */
   public static void printReturn() {
      if (ChunkyFlyConfig.returnToStart()) {
         ChunkyFlyMod.info("飞完之后会返回出发点再降落（关闭：" + PREFIX + " return off）");
      } else {
         ChunkyFlyMod.info("飞完之后不返航，就地盘旋下降落地（开启：" + PREFIX + " return on）");
      }
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

   /** 打印帮助（指令 /chunkyfly help 与聊天拦截共用）：指令列表 + 每条对应功能 */
   public static void printHelp() {
      java.util.List<String> lines = new java.util.ArrayList<>();
      lines.add("===== ChunkyFly " + ChunkyFlyMod.version() + " 指令列表 =====");
      lines.add("§6【任务】");
      lines.add(PREFIX + " <半径>         从当前位置起飞，加载该半径（方块）内的区块。例：" + PREFIX + " 1000");
      lines.add(PREFIX + " radius <半径> [chunks]   同上；末尾加 chunks 表示半径按区块算");
      lines.add(PREFIX + " chunks <半径>   半径按区块算（例：" + PREFIX + " chunks 64 ≈ 1024 格）");
      lines.add(PREFIX + " corner <x1> <z1> <x2> <z2>   加载矩形区域：四个参数是两个角点的方块坐标");
      lines.add(PREFIX + " pause / stop    暂停飞行（保留进度，可 continue）");
      lines.add(PREFIX + " continue        继续上次暂停的任务");
      lines.add(PREFIX + " cancel          取消任务（丢弃进度，同时清掉崩溃存档）");
      lines.add(PREFIX + " status          查看进度 / 阶段 / 烟花用量");
      lines.add("§6【崩溃后续跑】");
      lines.add(PREFIX + " recover         从上次没飞完的航点接着飞（崩溃重进后用这个）");
      lines.add(PREFIX + " forget          丢弃上次的任务存档");
      lines.add("§6【设置】");
      lines.add(PREFIX + " hud <x> <y>     挪动左上角进度文字（像素；" + PREFIX + " hud reset 复位）");
      lines.add(PREFIX + " cruise <Y>      设置巡航高度（默认 1000；低一点更省性能）");
      lines.add(PREFIX + " rd <n|off>      飞行期间临时压到的渲染距离（默认 8，off 关闭该保护）");
      lines.add(PREFIX + " return <on|off> 飞完之后是否返回出发点（默认 on；off = 就地下降）");
      lines.add(PREFIX + " help            显示这份指令列表");
      lines.add("§6【说明】需要穿鞘翅 + 背包里有非爆炸烟花；会自动从快捷栏/背包/潜影盒取烟花。");
      lines.add("§6起飞前请阅读：本模组自动转视角较生硬、可能频闪（光敏性癫痫者谨慎使用）；"
         + "飞大范围时可能导致游戏崩溃，崩了重进用 recover 可继续。");
      ChunkyFlyMod.infoLines(lines);
   }

   /** 给客户端聊天栏发一条本地消息（不会发到服务器） */
   public static void chat(String text) {
      Minecraft client = Minecraft.getInstance();
      if (client.gui != null) {
         client.gui.hud.setOverlayMessage(Component.literal(text), false);
      }
   }
}

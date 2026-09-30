package com.chunkyfly;

import com.chunkyfly.mixin.MixinHudChat;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkyFly —— 靠鞘翅自动飞行来加载区块的客户端模组（类 Chunky，但不用服务端插件）。
 * <p>
 * 入口只做初始化，实际逻辑：
 * <ul>
 *   <li>{@code MixinClientPlayerEntity} → 每个 tick 驱动 {@link FlyController}；</li>
 *   <li>{@code MixinInGameHud} → 左上角画进度百分比；</li>
 *   <li>{@code MixinClientPacketListener} → 拦下 {@code chunkyfly ...} 聊天/指令。</li>
 * </ul>
 * 不依赖 Fabric API，也不依赖 MixinExtras，方便在较低的 Fabric Loader 版本上跑。
 */
@Environment(EnvType.CLIENT)
public class ChunkyFlyMod implements ClientModInitializer {
   public static final String MOD_ID = "chunkyfly";
   public static final String PREFIX = "[ChunkyFly] ";
   private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
   /**
    * 光敏性癫痫提示：本模组会自动、且比较生硬地转动视角（每 tick 直接设 yaw/pitch），
    * 高空飞行时画面还有大量区块刷新，容易出现频闪。启动时提示一次，进世界后分几次显示。
    */
   public static final String PHOTOSENSITIVITY_WARNING =
      "光敏性癫痫提示：本模组会自动快速转动视角（转向较生硬），画面可能出现频闪，相关病史者请谨慎使用；"
         + "随时可以用 /chunkyfly cancel 立即停止。";


   @Override
   public void onInitializeClient() {
      ChunkyFlyConfig.load();
      // 装了 Fabric API 就注册真正的客户端指令（有 tab 补全、不会发到服务器）；
      // 没装就退回聊天拦截（Commands + MixinClientPacketListener）。
      if (FabricLoader.getInstance().isModLoaded("fabric-command-api-v2")) {
         try {
            FabricCommands.register();
            LOGGER.info("已注册客户端指令 /chunkyfly（带 tab 补全）");
         } catch (Throwable t) {
            LOGGER.warn("注册客户端指令失败，退回聊天拦截：{}", t.toString());
         }
      } else {
         LOGGER.info("未检测到 Fabric API，使用聊天拦截方式（直接输入 chunkyfly ...）");
      }

      LOGGER.info("ChunkyFly {} 已加载（HUD 偏移 hudX={} hudY={}）：输入 /chunkyfly help 查看用法",
         version(), ChunkyFlyConfig.hudX(), ChunkyFlyConfig.hudY());
   }

   /**
    * 该模组在飞大范围时可能把游戏搞崩（渲染压力 / 原版动态缓冲那类问题）。
    * 崩了重进就行，而且可以用 {@code /chunkyfly recover} 接着上次飞完。
    */
   public static final String CRASH_NOTICE =
      "提示：本模组在飞大范围时可能导致游戏崩溃（渲染压力），崩了重进游戏即可；"
         + "重进后用 /chunkyfly recover 可以接着上次没飞完的任务继续。";

   /** 任务运行期间在 HUD 第三行一直显示的两条提示 */
   public static final String PERSISTENT_NOTICE =
      "⚠ 光敏性癫痫：自动转向生硬、可能频闪 | 本模组可能崩溃，重进可继续";

   /**
    * 光敏性癫痫提示：在<b>每次下指令、任务真正跑起来之前</b>提示一次（进游戏时不刷屏）。
    * 聊天栏在 26.2 里是私有的，只能走动作栏 + 日志。
    */
   public static void warnPhotosensitivity() {
      LOGGER.warn(PHOTOSENSITIVITY_WARNING);
      Commands.chat(PREFIX + "§e" + PHOTOSENSITIVITY_WARNING);
   }

   /** 崩溃提示：同样在任务开始前提示一次 */
   public static void warnCrashNotice() {
      LOGGER.warn(CRASH_NOTICE);
      Commands.chat(PREFIX + "§e" + CRASH_NOTICE);
   }

   /** 模组版本：直接读 fabric.mod.json 里的 version，和构建号一致，方便确认装的是哪一版 */
   public static String version() {
      return FabricLoader.getInstance()
         .getModContainer(MOD_ID)
         .map(container -> container.getMetadata().getVersion().getFriendlyString())
         .orElse("未知版本");
   }

   /** 聊天栏是否可用（null = 还没试过；false = 这条会话里已确认不可用，直接走动作栏） */
   private static Boolean chatUsable;
   /** 聊天栏不可用时，退而在 HUD 上常驻显示的行（比如 /chunkyfly help 的指令列表） */
   private static String[] noticeLines;
   private static int noticeTicksLeft;

   /**
    * 往聊天栏发一条"只给自己看"的消息（不会发到服务器）。
    * <p>
    * 走的是本模组的可选 mixin {@link MixinHudChat}；它没生效时（原版改了私有字段名等）
    * 这里会捕获异常并返回 false，调用方退回动作栏，游戏不受影响。
    */
   public static boolean sendChat(String text) {
      if (chatUsable == Boolean.FALSE) {
         return false;
      }

      try {
         Minecraft client = Minecraft.getInstance();
         if (client == null || client.gui == null || client.gui.hud == null) {
            return false;
         }

         ((MixinHudChat)(Object)client.gui.hud).chunkyfly$addClientMessage(Component.literal(text));
         chatUsable = Boolean.TRUE;
         return true;
      } catch (Throwable t) {
         chatUsable = Boolean.FALSE;
         LOGGER.warn("聊天栏不可用（可选 mixin 未生效），退回动作栏显示：{}", t.toString());
         return false;
      }
   }

   public static void info(String message) {
      LOGGER.info(message);
      if (!sendChat(PREFIX + message)) {
         Commands.chat(PREFIX + message);
      }
   }

   public static void warn(String message) {
      LOGGER.warn(message);
      if (!sendChat(PREFIX + "§e" + message)) {
         Commands.chat(PREFIX + "§e" + message);
      }
   }

   /**
    * 发一整段多行内容（比如 {@code /chunkyfly help} 的指令列表）。
    * 聊天栏可用时逐行发进去（能上下翻、不会互相顶掉）；不可用时退而在 HUD 上常驻显示一段时间。
    */
   public static void infoLines(List<String> lines) {
      boolean allSent = true;

      for (String line : lines) {
         LOGGER.info(line);
         if (allSent && !sendChat(PREFIX + line)) {
            allSent = false;
         }
      }

      if (!allSent) {
         showNotice(lines.toArray(new String[0]), 20);
         Commands.chat(PREFIX + "聊天栏不可用，指令列表已显示在左上角 20 秒");
      }
   }

   /** 在 HUD 上常驻显示几行文字（秒） */
   public static void showNotice(String[] lines, int seconds) {
      noticeLines = lines;
      noticeTicksLeft = Math.max(1, seconds) * 20;
   }

   /** 每客户端 tick 调一次：倒计时清掉 HUD 提示 */
   public static void tickNotice() {
      if (noticeTicksLeft > 0 && --noticeTicksLeft <= 0) {
         noticeLines = null;
      }
   }

   /** HUD 上要额外显示的行（没有就返回 null） */
   public static String[] noticeLines() {
      return noticeTicksLeft > 0 ? noticeLines : null;
   }
}

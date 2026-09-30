package com.chunkyfly;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
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

   /** 模组版本：直接读 fabric.mod.json 里的 version，和构建号一致，方便确认装的是哪一版 */
   public static String version() {
      return FabricLoader.getInstance()
         .getModContainer(MOD_ID)
         .map(container -> container.getMetadata().getVersion().getFriendlyString())
         .orElse("未知版本");
   }

   public static void info(String message) {
      LOGGER.info(message);
      Commands.chat(PREFIX + message);
   }

   public static void warn(String message) {
      LOGGER.warn(message);
      Commands.chat(PREFIX + "§e" + message);
   }
}

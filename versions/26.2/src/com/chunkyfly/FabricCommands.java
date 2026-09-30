package com.chunkyfly;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/**
 * 注册真正的客户端指令 {@code /chunkyfly}（Fabric API 的客户端指令 API）。
 * <p>
 * 这才是「有 tab 补全、且不会发到服务器」的正确做法：
 * 指令进的是客户端自己的 Brigadier 分发器，由客户端直接执行，服务端根本收不到，
 * 所以不会出现"未知指令"的红字；补全列表里也会出现 chunkyfly 及其子指令。
 * <p>
 * 这个类只在 Fabric API 的 {@code fabric-command-api-v2} 存在时才会被加载（见 ChunkyFlyMod），
 * 没装 Fabric API 时退回聊天拦截，模组依然能用。
 */
public final class FabricCommands {
   private FabricCommands() {
   }

   public static void register() {
      ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
         ClientCommands.literal("chunkyfly")
            // /chunkyfly <半径>
            .then(ClientCommands.argument("radius", IntegerArgumentType.integer(16))
               .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "radius"), false)))
            // /chunkyfly radius <半径> [chunks]
            .then(ClientCommands.literal("radius")
               .then(ClientCommands.argument("radius", IntegerArgumentType.integer(16))
                  .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "radius"), false))
                  .then(ClientCommands.literal("chunks")
                     .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "radius"), true)))))
            // /chunkyfly chunks <半径>
            .then(ClientCommands.literal("chunks")
               .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1))
                  .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "radius"), true))))
            // /chunkyfly corner <x1> <z1> <x2> <z2>
            .then(ClientCommands.literal("corner")
               .then(ClientCommands.argument("x1", IntegerArgumentType.integer(-30000000, 30000000))
                  .then(ClientCommands.argument("z1", IntegerArgumentType.integer(-30000000, 30000000))
                     .then(ClientCommands.argument("x2", IntegerArgumentType.integer(-30000000, 30000000))
                        .then(ClientCommands.argument("z2", IntegerArgumentType.integer(-30000000, 30000000))
                           .executes(ctx -> startCorner(
                              IntegerArgumentType.getInteger(ctx, "x1"),
                              IntegerArgumentType.getInteger(ctx, "z1"),
                              IntegerArgumentType.getInteger(ctx, "x2"),
                              IntegerArgumentType.getInteger(ctx, "z2")
                           )))))))
            .then(ClientCommands.literal("pause").executes(ctx -> {
               FlyController.INSTANCE.pause();
               return 1;
            }))
            .then(ClientCommands.literal("stop").executes(ctx -> {
               FlyController.INSTANCE.pause();
               return 1;
            }))
            .then(ClientCommands.literal("continue").executes(ctx -> {
               FlyController.INSTANCE.resume();
               return 1;
            }))
            .then(ClientCommands.literal("resume").executes(ctx -> {
               FlyController.INSTANCE.resume();
               return 1;
            }))
            .then(ClientCommands.literal("cancel").executes(ctx -> {
               FlyController.INSTANCE.cancel();
               return 1;
            }))
            .then(ClientCommands.literal("hud")
               .executes(ctx -> {
                  Commands.printHud();
                  return 1;
               })
               .then(ClientCommands.literal("reset").executes(ctx -> {
                  ChunkyFlyConfig.resetHud();
                  Commands.printHud();
                  return 1;
               }))
               .then(ClientCommands.argument("x", IntegerArgumentType.integer(ChunkyFlyConfig.MIN_OFFSET, ChunkyFlyConfig.MAX_OFFSET))
                  .then(ClientCommands.argument("y", IntegerArgumentType.integer(ChunkyFlyConfig.MIN_OFFSET, ChunkyFlyConfig.MAX_OFFSET))
                     .executes(ctx -> {
                        ChunkyFlyConfig.setHud(
                           IntegerArgumentType.getInteger(ctx, "x"),
                           IntegerArgumentType.getInteger(ctx, "y")
                        );
                        Commands.printHud();
                        return 1;
                     }))))
            .then(ClientCommands.literal("cruise")
               .executes(ctx -> {
                  Commands.printCruise();
                  return 1;
               })
               .then(ClientCommands.argument("y", IntegerArgumentType.integer(ChunkyFlyConfig.MIN_CRUISE_Y, ChunkyFlyConfig.MAX_CRUISE_Y))
                  .executes(ctx -> {
                     ChunkyFlyConfig.setCruiseY(IntegerArgumentType.getInteger(ctx, "y"));
                     Commands.printCruise();
                     return 1;
                  })))
            .then(ClientCommands.literal("rd")
               .executes(ctx -> {
                  Commands.printRenderDistance();
                  return 1;
               })
               .then(ClientCommands.literal("off").executes(ctx -> {
                  ChunkyFlyConfig.setFlightRenderDistance(0);
                  Commands.printRenderDistance();
                  return 1;
               }))
               .then(ClientCommands.argument("n", IntegerArgumentType.integer(ChunkyFlyConfig.MIN_RENDER_DISTANCE, ChunkyFlyConfig.MAX_RENDER_DISTANCE))
                  .executes(ctx -> {
                     ChunkyFlyConfig.setFlightRenderDistance(IntegerArgumentType.getInteger(ctx, "n"));
                     Commands.printRenderDistance();
                     return 1;
                  })))
            .then(ClientCommands.literal("return")
               .executes(ctx -> {
                  Commands.printReturn();
                  return 1;
               })
               .then(ClientCommands.literal("on").executes(ctx -> setReturn(true)))
               .then(ClientCommands.literal("off").executes(ctx -> setReturn(false))))
            .then(ClientCommands.literal("back")
               .executes(ctx -> {
                  Commands.printReturn();
                  return 1;
               })
               .then(ClientCommands.literal("on").executes(ctx -> setReturn(true)))
               .then(ClientCommands.literal("off").executes(ctx -> setReturn(false))))
            .then(ClientCommands.literal("recover").executes(ctx -> {
               FlyController.INSTANCE.recover();
               return 1;
            }))
            .then(ClientCommands.literal("forget").executes(ctx -> {
               FlyController.INSTANCE.forgetSaved();
               return 1;
            }))
            .then(ClientCommands.literal("status").executes(ctx -> {
               ChunkyFlyMod.info(FlyController.INSTANCE.statusText());
               return 1;
            }))
            .then(ClientCommands.literal("help").executes(ctx -> {
               Commands.printHelp();
               return 1;
            }))
      ));
   }

   private static int start(int radius, boolean chunks) {
      FlyController.INSTANCE.start(net.minecraft.client.Minecraft.getInstance().player, chunks ? radius * 16 : radius);
      return 1;
   }

   /** {@code /chunkyfly return on|off}：飞完是否返回出发点 */
   private static int setReturn(boolean value) {
      ChunkyFlyConfig.setReturnToStart(value);
      Commands.printReturn();
      return 1;
   }

   /** {@code /chunkyfly corner <x1> <z1> <x2> <z2>}：矩形区域（两个角点的方块坐标） */
   private static int startCorner(int x1, int z1, int x2, int z2) {
      FlyController.INSTANCE.startCorner(net.minecraft.client.Minecraft.getInstance().player, x1, z1, x2, z2);
      return 1;
   }
}

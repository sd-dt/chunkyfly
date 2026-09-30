package com.chunkyfly.mixin;

import com.chunkyfly.Commands;
import net.minecraft.class_437;
import net.minecraft.class_634;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 兜底：没装 Fabric API 时也能用 {@code chunkyfly ...}（直接输入，或 /chunkyfly ...）。
 * <p>
 * 装了 Fabric API 时走 {@code FabricCommands} 注册的真正客户端指令（有补全、不会被服务端拒），
 * 这里的拦截仍然保留：它还能处理不带斜杠的 {@code chunkyfly 1000} 写法。
 * <p>
 * 三个注入点覆盖 1.21.x 里"发聊天 / 发指令 / 发无人值守指令"三条路径，
 * 都用 {@code require = 0}（某个版本没有对应方法时跳过而不是崩游戏）。
 */
@Mixin({class_634.class})
public class MixinClientPacketListener {
   @Inject(
      method = {"method_45729"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void chunkyfly$onSendChat(String message, CallbackInfo ci) {
      if (Commands.handle(message)) {
         ci.cancel();
      }
   }

   @Inject(
      method = {"method_45730"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void chunkyfly$onSendCommand(String command, CallbackInfo ci) {
      if (Commands.handle(command)) {
         ci.cancel();
      }
   }

   @Inject(
      method = {"method_71927"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void chunkyfly$onSendUnattendedCommand(String command, class_437 screen, CallbackInfo ci) {
      if (Commands.handle(command)) {
         ci.cancel();
      }
   }
}

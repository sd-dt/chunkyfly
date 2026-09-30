package com.chunkyfly.mixin;

import com.chunkyfly.ChunkyFlyMod;
import com.chunkyfly.FlyController;
import com.chunkyfly.UniformBufferGuard;
import net.minecraft.class_310;
import net.minecraft.class_746;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 每个客户端 tick 驱动一次飞行控制器（挂在玩家 tick 上，和服务端 tick 同频） */
@Mixin({class_746.class})
public class MixinClientPlayerEntity {
   @Inject(
      method = {"method_5773"},
      at = {@At("HEAD")}
   )
   private void chunkyfly$tick(CallbackInfo ci) {
      // 渲染线程只排队、不碰聊天：把"原版动态缓冲溢出保护"要提示的话在客户端 tick 线程里发出去
      String guardWarning = UniformBufferGuard.pollChatWarning();
      if (guardWarning != null) {
         ChunkyFlyMod.warn(guardWarning);
      }

      FlyController.INSTANCE.tick(class_310.method_1551());
   }
}

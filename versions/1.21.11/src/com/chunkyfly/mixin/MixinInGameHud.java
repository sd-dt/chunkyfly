package com.chunkyfly.mixin;

import com.chunkyfly.ChunkyFlyConfig;
import com.chunkyfly.FlyController;
import net.minecraft.class_310;
import net.minecraft.class_329;
import net.minecraft.class_332;
import net.minecraft.class_9779;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 左上角画进度百分比（跟着原版 HUD 一起渲染，字体/缩放随游戏设置） */
@Mixin({class_329.class})
public class MixinInGameHud {
   /**
    * 画在 HUD 渲染的<b>开头</b>：
    * 1) 1.21.9+ 的 GUI 是分层渲染的，{@code method_1753} 中途有一次 flush（{@code method_71048()}），
    *    画在 TAIL 有可能落到一个不会再被提交的层里 → 看不见；
    * 2) 颜色必须带 alpha 字节：{@code 0xFFFF55} 其实是 {@code 0x00FFFF55}，alpha=0 → 完全透明！
    *    原版传的都是 {@code 0xFFFFFFFF} 这种 ARGB，所以这里补上 alpha（黄 {@code 0xFFFFFF55}、白 {@code 0xFFFFFFFF}）。
    */
   @Inject(
      method = {"method_1753"},
      at = {@At("HEAD")}
   )
   private void chunkyfly$renderHud(class_332 context, class_9779 tickCounter, CallbackInfo ci) {
      String[] lines = FlyController.INSTANCE.hudLines();
      if (lines == null) {
         return;
      }

      class_310 client = class_310.method_1551();
      if (client.field_1772 == null) {
         return;
      }

      // 位置来自 config/chunkyfly.json（/chunkyfly hud <x> <y> 修改），默认 4,4 与原版左上角一致
      int x = ChunkyFlyConfig.hudX();
      int y = ChunkyFlyConfig.hudY();
      context.method_51433(client.field_1772, lines[0], x, y, 0xFFFFFF55, true);
      context.method_51433(client.field_1772, lines[1], x, y + 11, 0xFFFFFFFF, true);
   }
}

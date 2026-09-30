package com.chunkyfly.mixin;

import com.chunkyfly.ChunkyFlyConfig;
import com.chunkyfly.ChunkyFlyMod;
import com.chunkyfly.FlyController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 左上角画进度百分比（跟着原版 HUD 一起渲染，字体/缩放随游戏设置） */
@Mixin({Hud.class})
public class MixinInGameHud {
   /**
    * 画在 HUD 渲染的<b>开头</b>：
    * 1) 1.21.9+ 的 GUI 是分层渲染的，{@code render} 中途有一次 flush（{@code nextStratum()}），
    *    画在 TAIL 有可能落到一个不会再被提交的层里 → 看不见；
    * 2) 颜色必须带 alpha 字节：{@code 0xFFFF55} 其实是 {@code 0x00FFFF55}，alpha=0 → 完全透明！
    *    原版传的都是 {@code 0xFFFFFFFF} 这种 ARGB，所以这里补上 alpha（黄 {@code 0xFFFFFF55}、白 {@code 0xFFFFFFFF}）。
    */
   @Inject(
      method = {"extractRenderState"},
      at = {@At("HEAD")}
   )
   private void chunkyfly$renderHud(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
      String[] lines = FlyController.INSTANCE.hudLines();
      if (lines == null) {
         // 没有任务在跑：如果有一段临时提示（聊天栏不可用时的 /chunkyfly help 列表等），就在左上角显示
         lines = ChunkyFlyMod.noticeLines();
         if (lines == null) {
            return;
         }
      }

      Minecraft client = Minecraft.getInstance();
      if (client.font == null) {
         return;
      }

      // 位置来自 config/chunkyfly.json（/chunkyfly hud <x> <y> 修改），默认 4,4 与原版左上角一致
      int x = ChunkyFlyConfig.hudX();
      int y = ChunkyFlyConfig.hudY();
      for (int i = 0; i < lines.length && i < 24; i++) {
         if (lines[i] == null) {
            continue;
         }

         int color = i == 0 ? 0xFFFFFF55 : (i == 2 ? 0xFFFFAA00 : 0xFFFFFFFF);
         context.text(client.font, lines[i], x, y + i * 11, color, true);
      }
   }
}

package com.chunkyfly.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 把 {@code Hud} 里私有的聊天组件暴露出来。
 * <p>
 * 26.2 的 {@code Gui} 不再有 {@code getChat()}，{@code Hud.chat} 也是私有字段，
 * 于是"给玩家自己看的多行文字"（比如 {@code /chunkyfly help} 的指令列表）只能塞进动作栏 ——
 * 而动作栏一次只显示一行，十几行指令列表实际只看得到最后一行。
 * <p>
 * 这个 mixin 只做一件事：把 {@code chat} 影子出来，并提供
 * {@code chunkyfly$addClientMessage(Component)} 供模组调用
 * （对应原版的 {@code addClientSystemMessage}，本地消息、不会发到服务器）。
 * <p>
 * 它单独放在 {@code chunkyfly.chat.mixins.json}（{@code "required": false}）：
 * 万一以后原版改了这个字段的名字/类型，也只会记一条日志、游戏照常启动，
 * 模组会自动退回"动作栏 + HUD 常驻显示"的方式。
 */
@Mixin(Hud.class)
public abstract class MixinHudChat {
   @Shadow
   @Final
   private ChatComponent chat;

   /** 给聊天栏加一条只属于自己的消息（不会发到服务器） */
   public void chunkyfly$addClientMessage(Component text) {
      this.chat.addClientSystemMessage(text);
   }
}

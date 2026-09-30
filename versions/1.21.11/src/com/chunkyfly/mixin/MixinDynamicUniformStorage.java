package com.chunkyfly.mixin;

import com.chunkyfly.UniformBufferGuard;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.class_11280;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦住原版 1.21.9+ 「动态变换缓冲」扩容时的整数溢出崩溃。
 * <p>
 * 原版 {@code DynamicUniformStorage.method_71101(int newCapacity)} 里新缓冲大小是
 * {@code this.blockSize * newCapacity}（int 相乘）。blockSize 是每条变换对齐后的大小
 * （{@code Std140SizeCalculator} 算出 160 字节 → 按 UBO 对齐 256），于是容量翻倍到 8,388,608 时
 * {@code 256 × 8388608 = 2^31} 溢出成负数 → {@code GpuBuffer} 抛
 * {@code IllegalArgumentException: Buffer size must be greater than zero} 把游戏崩掉。
 * <p>
 * 这里**只在这个"即将溢出"的状态下**动手：取消这次扩容、把写指针回绕到已分配缓冲的开头继续写
 * （画面可能出现个别错位，但游戏不会死）。正常游戏永远走不到这个分支（需要一帧内写几百万条变换），
 * 因此对正常渲染零影响。顺带在容量异常时按秒采样调用栈，把写入来源记进日志，便于定位肇事模组。
 */
@Mixin({class_11280.class})
public class MixinDynamicUniformStorage {
   /** 每条变换对齐后的大小（字节） */
   @Shadow
   private int field_60087;
   /** 当前写入下标 */
   @Shadow
   private int field_60089;
   /** 当前容量（条数） */
   @Shadow
   private int field_60090;

   @Inject(
      method = {"method_71101"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void chunkyfly$guardSizeOverflow(int newCapacity, CallbackInfo ci) {
      long bytes = (long)this.field_60087 * (long)newCapacity;
      if (bytes <= Integer.MAX_VALUE) {
         UniformBufferGuard.noteCapacity(newCapacity);
         return;
      }

      this.field_60089 = 0;
      ci.cancel();
      UniformBufferGuard.noteOverflowAvoided(this.field_60087, newCapacity, this.field_60090);
   }

   @Inject(
      method = {"method_71102"},
      at = {@At("HEAD")},
      require = 0
   )
   private void chunkyfly$sampleWriter(class_11280.class_11281 value, CallbackInfoReturnable<GpuBufferSlice> cir) {
      UniformBufferGuard.noteWrite(this.field_60090);
   }
}

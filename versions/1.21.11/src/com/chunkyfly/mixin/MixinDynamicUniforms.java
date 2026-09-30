package com.chunkyfly.mixin;

import com.chunkyfly.UniformBufferGuard;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.class_11282;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「批量变换写入」探测：原版 {@code DynamicUniforms} 有两个批量接口
 * （{@code method_71107} 写变换、{@code method_76294} 写区块段），一次调用就能写进成千上万条。
 * <p>
 * 用户环境里唯一的批量写入方是**投影（Litematica 0.26.12）的新原理图渲染器**
 * （{@code WorldRendererSchematic.prepareBlockLayers} 把"每个可见原理图区块 × 每个方块图层"攒成一条变换，
 * 最后一次性 {@code writeAll}）；原理图越大、已构建网格的区块越多，一帧写入的条数就越多，
 * 最终把原版 {@code DynamicUniformStorage} 顶到容量 2^23 × 256 字节 = 2^31（int 溢出）而崩溃。
 * <p>
 * 这里只做记录（限流），不改任何行为：把"一次批量写了多少条、来自谁"写进 {@code logs/latest.log}，
 * 便于确认肇事模组。
 */
@Mixin({class_11282.class})
public class MixinDynamicUniforms {
   @Inject(
      method = {"method_71107"},
      at = {@At("HEAD")},
      require = 0
   )
   private void chunkyfly$sampleTransformBatch(class_11282.class_11283[] values, CallbackInfoReturnable<GpuBufferSlice[]> cir) {
      UniformBufferGuard.noteBatch("变换", values == null ? 0 : values.length);
   }

   @Inject(
      method = {"method_76294"},
      at = {@At("HEAD")},
      require = 0
   )
   private void chunkyfly$sampleChunkSectionBatch(class_11282.class_12294[] values, CallbackInfoReturnable<GpuBufferSlice[]> cir) {
      UniformBufferGuard.noteBatch("区块段", values == null ? 0 : values.length);
   }
}

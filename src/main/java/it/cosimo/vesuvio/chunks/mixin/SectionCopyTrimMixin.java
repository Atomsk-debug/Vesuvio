package it.cosimo.vesuvio.chunks.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.chunks.SectionCopyTrim;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** SectionCopy.&lt;init&gt;: copy only the section's own block entities ({@link SectionCopyTrim}). */
@Mixin(SectionCopy.class)
public abstract class SectionCopyTrimMixin {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
			target = "Lcom/google/common/collect/ImmutableMap;copyOf(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;"))
	private ImmutableMap<BlockPos, BlockEntity> vesuvio$copyOwnBlockEntities(Map<BlockPos, BlockEntity> all,
			Operation<ImmutableMap<BlockPos, BlockEntity>> original, @Local(argsOnly = true) LevelChunk chunk,
			@Local(argsOnly = true) int sectionIndex) {
		ImmutableMap<BlockPos, BlockEntity> own = SectionCopyTrim.copyOwn(all, chunk, sectionIndex);
		return own != null ? own : original.call(all);
	}
}

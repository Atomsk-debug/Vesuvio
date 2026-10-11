package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.culling.DetailCulling;
import it.cosimo.vesuvio.culling.FogCulling;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

/**
 * Fog culling of entities and item frames behind their wall, where vanilla picks what to extract for the camera:
 * vanilla's {@code isEntityVisible(entity, frustum, camX, camY, camZ, entityPartialTicks, chunkFadeDuration)} answer
 * in extractVisibleEntities is narrowed. The arguments it needs are the loop's own locals, read here: a value hook,
 * nothing is allocated per entity. The frame's fog state is set up by {@link CullLevelExtractorMixin}.
 *
 * <p>Priority 999: the same answer is also narrowed by entity occlusion culling (entities.mixin.LevelExtractorMixin,
 * priority 1000). Of two value hooks on one call, the one applied later sees vanilla's value first, so this one is
 * applied first and its test runs second: occlusion, then fog, the order the game ran them in when both were
 * wrapping hooks (where the entities config, listed first, came first and ran innermost).
 */
@Mixin(value = LevelExtractor.class, priority = 999)
abstract class CullEntityVisibilityMixin {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@Shadow
	private ClientLevel level;

	@ModifyExpressionValue(method = "extractVisibleEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDFJ)Z"))
	private boolean vesuvio$cullEntity(boolean visible, @Local Entity entity, @Local float partialTicks,
			@Local(ordinal = 0) double camX, @Local(ordinal = 1) double camY, @Local(ordinal = 2) double camZ) {
		if (!visible) {
			return false;
		}
		if (FogCulling.entityHidden(entity, partialTicks, this.levelRenderer.entityRenderDispatcher())) {
			return false;
		}
		return !(entity instanceof ItemFrame frame && DetailCulling.itemFrameHidden(frame, camX, camY, camZ, this.level));
	}
}

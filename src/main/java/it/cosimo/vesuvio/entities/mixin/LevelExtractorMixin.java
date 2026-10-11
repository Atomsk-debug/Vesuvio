package it.cosimo.vesuvio.entities.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.entities.Culling;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Entity and block entity occlusion culling, at the point where vanilla picks what to extract for the camera:
 * {@code LevelExtractor.extractVisibleEntities} (after vanilla's own {@code isEntityVisible}) and the per-section
 * loop of {@code extractVisibleBlockEntities} (the globally rendered list: beacons, gateways, structure blocks, is
 * not touched). Etna Shaders' shadow pass draws terrain only and never comes through here.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@Shadow
	private ClientLevel level;

	@Inject(method = "extract", at = @At("HEAD"))
	private void vesuvio$beginCulling(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
		Culling.beginFrame(minecraft, camera, level, levelRenderer);
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void vesuvio$endCulling(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
		Culling.endFrame();
	}

	/** Vanilla's isEntityVisible answer, narrowed; entity and partial tick are the loop's locals it was called with. */
	@ModifyExpressionValue(method = "extractVisibleEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDFJ)Z"))
	private boolean vesuvio$occlusionCullEntity(boolean visible, @Local Entity entity, @Local float partialTicks) {
		return visible && !Culling.entityHidden(entity, partialTicks, levelRenderer.entityRenderDispatcher());
	}

	@WrapOperation(method = "extractVisibleBlockEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
			ordinal = 0))
	private BlockEntityRenderState vesuvio$cullBlockEntity(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity,
			float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress, boolean isGloballyRendered,
			Operation<BlockEntityRenderState> original) {
		if (!isGloballyRendered && Culling.blockEntityHidden(blockEntity, dispatcher)) {
			return null;
		}
		return original.call(dispatcher, blockEntity, partialTicks, breakProgress, isGloballyRendered);
	}
}

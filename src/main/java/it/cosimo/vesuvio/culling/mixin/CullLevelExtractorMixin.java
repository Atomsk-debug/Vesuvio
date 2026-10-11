package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.culling.DetailCulling;
import it.cosimo.vesuvio.culling.FogCulling;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fog culling of entities and block entities, item frames behind their wall and beacon beams out of view, where vanilla
 * picks what to extract for the camera: after its own isEntityVisible in extractVisibleEntities, and around
 * tryExtractRenderState in extractVisibleBlockEntities (the per-section loop for fog, the globally rendered list for
 * beacons).
 */
@Mixin(LevelExtractor.class)
abstract class CullLevelExtractorMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	private ClientLevel level;

	@Inject(method = "extract", at = @At("HEAD"))
	private void vesuvio$beginCulling(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
		FogCulling.beginExtract(this.minecraft, this.level, this.levelRenderState, worldPartialTicks);
		DetailCulling.beginExtract();
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void vesuvio$endCulling(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
		FogCulling.endExtract();
		DetailCulling.endExtract();
	}

	@WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDFJ)Z"))
	private boolean vesuvio$cullEntity(LevelExtractor self, Entity entity, Frustum frustum, double camX, double camY,
			double camZ, float partialTicks, long chunkFadeDuration, Operation<Boolean> original) {
		if (!original.call(self, entity, frustum, camX, camY, camZ, partialTicks, chunkFadeDuration)) {
			return false;
		}
		if (FogCulling.entityHidden(entity, partialTicks, this.levelRenderer.entityRenderDispatcher())) {
			return false;
		}
		return !(entity instanceof ItemFrame frame && DetailCulling.itemFrameHidden(frame, camX, camY, camZ, this.level));
	}

	@WrapOperation(method = "extractVisibleBlockEntities", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"))
	private BlockEntityRenderState vesuvio$fogCullBlockEntity(BlockEntityRenderDispatcher dispatcher,
			BlockEntity blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress,
			boolean isGloballyRendered, Operation<BlockEntityRenderState> original) {
		if (!isGloballyRendered && breakProgress == null && FogCulling.blockEntityHidden(blockEntity, dispatcher)) {
			return null;
		}
		return original.call(dispatcher, blockEntity, partialTicks, breakProgress, isGloballyRendered);
	}

	@WrapOperation(method = "extractVisibleBlockEntities", at = @At(value = "INVOKE", ordinal = 1,
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"))
	private BlockEntityRenderState vesuvio$cullBeaconBeam(BlockEntityRenderDispatcher dispatcher,
			BlockEntity blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress,
			boolean isGloballyRendered, Operation<BlockEntityRenderState> original,
			@Local(argsOnly = true) Camera camera) {
		if (DetailCulling.beaconHidden(blockEntity, dispatcher, this.levelRenderState.cameraRenderState,
				this.levelRenderState.playerRenderState, camera.forwardVector())) {
			return null;
		}
		return original.call(dispatcher, blockEntity, partialTicks, breakProgress, isGloballyRendered);
	}
}

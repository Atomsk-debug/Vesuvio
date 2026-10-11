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
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fog culling of block entities and beacon beams out of view, where vanilla picks what to extract for the camera:
 * around tryExtractRenderState in extractVisibleBlockEntities (the per-section loop for fog, the globally rendered
 * list for beacons). Starts and ends the frame's fog and detail culling. Entities: {@link CullEntityVisibilityMixin}.
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

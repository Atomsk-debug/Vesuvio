package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.cosimo.vesuvio.chunks.MeshOrder;
import it.cosimo.vesuvio.chunks.SectionState;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.Util;

/**
 * SectionRenderDispatcher.RenderSection: adds {@link SectionState} (compile time of the shown mesh, camera position
 * of the last translucent re-sort) and the {@link MeshOrder} check in setSectionMesh. setSectionMesh's callers
 * release whatever it returns (the replaced mesh); a refused mesh is returned instead, so it is released and the
 * shown one stays. reset() (the section moved to a new position, or the world/geometry was reset) marks the time,
 * so builds made for the old position can't be shown at the new one.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionStateMixin implements SectionState {
	@Unique
	private volatile long vesuvio$shownStart = Long.MIN_VALUE;
	@Unique
	private double vesuvio$sortX = Double.NaN;
	@Unique
	private double vesuvio$sortY = Double.NaN;
	@Unique
	private double vesuvio$sortZ = Double.NaN;

	@Inject(method = "setSectionMesh", at = @At("HEAD"), cancellable = true)
	private void vesuvio$keepNewest(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		if (!MeshOrder.accept(this, mesh)) {
			cir.setReturnValue(mesh);
		}
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void vesuvio$resetOrder(CallbackInfo ci) {
		vesuvio$shownStart = Util.getNanos();
		vesuvio$sortX = Double.NaN;
	}

	@Override
	public long vesuvio$shownStart() {
		return vesuvio$shownStart;
	}

	@Override
	public void vesuvio$setShownStart(long start) {
		vesuvio$shownStart = start;
	}

	@Override
	public double vesuvio$sortX() {
		return vesuvio$sortX;
	}

	@Override
	public double vesuvio$sortY() {
		return vesuvio$sortY;
	}

	@Override
	public double vesuvio$sortZ() {
		return vesuvio$sortZ;
	}

	@Override
	public void vesuvio$setSortPos(double x, double y, double z) {
		vesuvio$sortX = x;
		vesuvio$sortY = y;
		vesuvio$sortZ = z;
	}
}

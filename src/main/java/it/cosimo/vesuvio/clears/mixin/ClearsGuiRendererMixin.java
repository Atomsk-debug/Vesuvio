package it.cosimo.vesuvio.clears.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.gui.render.GuiRenderer;

import it.cosimo.vesuvio.clears.ClearFusion;

/** Setting clearFusion: the main depth clear before the menu blur is a dead candidate too (see ClearsGameRendererMixin). */
@Mixin(GuiRenderer.class)
public abstract class ClearsGuiRendererMixin {
	@WrapOperation(method = "draw()V", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
	private void vesuvio$guiDepthClear(CommandEncoder encoder, GpuTexture texture, double depth, Operation<Void> original) {
		ClearFusion.markDeadCandidate();
		try {
			original.call(encoder, texture, depth);
		} finally {
			ClearFusion.clearDeadCandidateMark();
		}
	}
}

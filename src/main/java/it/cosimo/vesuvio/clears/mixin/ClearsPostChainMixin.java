package it.cosimo.vesuvio.clears.mixin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;

import it.cosimo.vesuvio.clears.ClearFusion;

/**
 * Setting clearFusion, post-chain depth diet: PostChain.addToFrame gives every internal target a depth texture
 * (DEFAULT_DEPTH), which the frame's pool allocates and clears every frame. Post passes draw colour only
 * (PostPass opens a colour-only pass), and the only thing that reads a target's depth is an input with
 * use_depth_buffer (PostPass.TargetInput.depthBuffer). A target that no input of the chain reads as depth gets
 * no depth texture. A chain with an input of a kind this does not know (a mod's) keeps vanilla's depth for all
 * its targets. Etna's own chains already have none (its PostChainMixin); the two hooks chain without clashing.
 */
@Mixin(PostChain.class)
public abstract class ClearsPostChainMixin {
	@Shadow
	@Final
	private List<PostPass> passes;

	/** Targets some input reads as depth, by name and by id; null until first needed (passes never change). */
	@Unique
	private Set<Identifier> vesuvio$depthRead;

	@Unique
	private Set<String> vesuvio$depthReadNames;

	/** An input of an unknown kind: keep every depth texture of this chain. */
	@Unique
	private boolean vesuvio$keepAllDepth;

	@ModifyArg(method = "addToFrame", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;createInternal(Ljava/lang/String;Lcom/mojang/blaze3d/resource/ResourceDescriptor;)Lcom/mojang/blaze3d/resource/ResourceHandle;"),
			index = 1)
	private ResourceDescriptor<?> vesuvio$noUnusedDepth(String name, ResourceDescriptor<?> descriptor) {
		if (!ClearFusion.CLEAR_FUSION.on() || !(descriptor instanceof RenderTargetDescriptor target) || target.depth() == null
				|| target.color() == null) {
			return descriptor;
		}
		this.vesuvio$scanInputs();
		if (this.vesuvio$keepAllDepth || this.vesuvio$depthReadNames.contains(name)) {
			return descriptor;
		}
		return new RenderTargetDescriptor(target.width(), target.height(), target.color(), null);
	}

	@ModifyArg(method = "addToFrame", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/PostChain;getOrCreatePersistentTarget(Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;)Lcom/mojang/blaze3d/pipeline/RenderTarget;"),
			index = 1)
	private RenderTargetDescriptor vesuvio$noUnusedPersistentDepth(Identifier id, RenderTargetDescriptor target) {
		if (!ClearFusion.CLEAR_FUSION.on() || target.depth() == null || target.color() == null) {
			return target;
		}
		this.vesuvio$scanInputs();
		if (this.vesuvio$keepAllDepth || this.vesuvio$depthRead.contains(id)) {
			return target;
		}
		return new RenderTargetDescriptor(target.width(), target.height(), target.color(), null);
	}

	@Unique
	private void vesuvio$scanInputs() {
		if (this.vesuvio$depthRead != null) {
			return;
		}
		Set<Identifier> ids = new HashSet<>();
		Set<String> names = new HashSet<>();
		boolean keepAll = false;
		for (PostPass pass : this.passes) {
			for (PostPass.Input input : ((ClearsPostPassAccessor) pass).vesuvio$inputs()) {
				if (input instanceof PostPass.TargetInput targetInput) {
					if (targetInput.depthBuffer()) {
						ids.add(targetInput.targetId());
						names.add(targetInput.targetId().toString());
					}
				} else if (!(input instanceof PostPass.TextureInput)) {
					keepAll = true;
				}
			}
		}
		this.vesuvio$keepAllDepth = keepAll;
		this.vesuvio$depthReadNames = names;
		this.vesuvio$depthRead = ids;
	}
}

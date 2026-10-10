package it.cosimo.vesuvio.clears.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.PostPass;

/** Setting clearFusion: a post pass's inputs, to see which targets are read as depth. */
@Mixin(PostPass.class)
public interface ClearsPostPassAccessor {
	@Accessor("inputs")
	List<PostPass.Input> vesuvio$inputs();
}

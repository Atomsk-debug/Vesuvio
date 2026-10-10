package it.cosimo.vesuvio.frame;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.texture.SpriteContents;

/** Added to CompiledSectionMesh: the animated sprites its geometry uses, or null when unknown. */
public interface CompiledMeshExt {
	SpriteContents @Nullable [] vesuvio$animatedSprites();
}

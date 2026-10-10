package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/** The RenderSection steps vanilla's compile task uses to show a finished (here: empty) mesh. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public interface RenderSectionInvoker {
	@Invoker("cancelTasks")
	void vesuvio$cancelTasks();

	@Invoker("setSectionMesh")
	SectionMesh vesuvio$setSectionMesh(SectionMesh mesh);

	@Invoker("releaseSectionMesh")
	void vesuvio$releaseSectionMesh(SectionMesh mesh);
}

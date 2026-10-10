package it.cosimo.vesuvio.draws;

import java.util.List;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.ui.VesuvioStats;
import org.jspecify.annotations.Nullable;

/**
 * Setting skipRedundantBinds: the Vulkan render pass stops sending state the GPU already has.
 *
 * <p>Vanilla's Vulkan backend (VulkanRenderPass) binds the pipeline on every setPipeline, even when it is the one
 * already bound, and before every draw that follows a setPipeline or setUniform it allocates a new descriptor set,
 * writes every binding into it and binds it, even when every binding names the same buffer range or texture as the
 * set bound for the previous draw. Entities, particles and the GUI do setPipeline plus all their uniforms for every
 * single draw, so most of those binds and sets repeat the last ones. On MoltenVK each one is Metal encoding work on
 * the CPU; on desktop drivers it is driver work.
 *
 * <p>Within one render pass ({@code VulkanRenderPassMixin}):
 * <ul>
 * <li>setPipeline with the pipeline already bound in this pass skips vkCmdBindPipeline. Binding the same pipeline
 * again changes nothing in Vulkan: its static state is the same, its dynamic state (viewport, scissor) is left as
 * set, and bound descriptor sets and push constants stay bound.</li>
 * <li>A descriptor set is not made again when the pipeline is the one the bound set was made for, no pipeline was
 * bound in between, and every binding has the same value: the same buffer object, offset and length, or the same
 * texture view and sampler objects. The set already bound then holds exactly the descriptors vanilla would write
 * (descriptor sets are never written again after their first update, and buffers, views and samplers are only
 * destroyed after the frame is done), so binding a new copy of it changes nothing.</li>
 * </ul>
 * A missing uniform still reaches vanilla's code and fails the same way. Off (or with the master switch off),
 * vanilla's code runs as is.
 */
public final class RedundantBinds {
	public static final VesuvioConfig.Toggle SKIP = VesuvioConfig.toggle("rendering", "skipRedundantBinds", true, false);

	// Render thread: counted during a frame, published when it is submitted (VulkanCommandEncoder.submit).
	static int pipelineBindsSkipped;
	static int descriptorSetsSkipped;
	static int draws;
	/** Pipeline binds left out last frame. */
	public static int pipelineBindsSkippedLastFrame;
	/** Descriptor sets not allocated, written and bound last frame. */
	public static int descriptorSetsSkippedLastFrame;
	/** Draws recorded last frame, counting each indirect sub-draw (on MoltenVK, one Metal draw each). */
	public static int drawsLastFrame;

	private RedundantBinds() {
	}

	/** Called from Draws.init: makes the setting and registers the counters. */
	static void init() {
		VesuvioStats.register("binds skipped", () -> pipelineBindsSkippedLastFrame);
		VesuvioStats.register("desc sets skipped", () -> descriptorSetsSkippedLastFrame);
		VesuvioStats.register("draws", () -> drawsLastFrame);
	}

	public static void pipelineBindSkipped() {
		pipelineBindsSkipped++;
	}

	public static void descriptorSetSkipped() {
		descriptorSetsSkipped++;
	}

	public static void drew(int count) {
		draws += count;
	}

	/** Start of VulkanCommandEncoder.submit: the frame's commands are all recorded. */
	public static void endFrame() {
		pipelineBindsSkippedLastFrame = pipelineBindsSkipped;
		descriptorSetsSkippedLastFrame = descriptorSetsSkipped;
		drawsLastFrame = draws;
		pipelineBindsSkipped = 0;
		descriptorSetsSkipped = 0;
		draws = 0;
	}

	/**
	 * The values a descriptor set written now would hold, in the order of the pipeline's uniforms, reusing
	 * {@code into} when it is long enough.
	 */
	public static Object[] snapshot(List<CompiledRenderPipeline.CreateInfo.Uniform> uniforms, List<Object> values,
			Object @Nullable [] into) {
		int n = uniforms.size();
		Object[] out = into != null && into.length >= n ? into : new Object[n];
		for (int i = 0; i < n; i++) {
			out[i] = values.get(uniforms.get(i).binding());
		}
		return out;
	}

	/** Whether every uniform of the pipeline has the value recorded by {@link #snapshot}. */
	public static boolean sameValues(List<CompiledRenderPipeline.CreateInfo.Uniform> uniforms, List<Object> values,
			Object @Nullable [] snapshot) {
		if (snapshot == null) {
			return false;
		}
		int n = uniforms.size();
		if (snapshot.length < n) {
			return false;
		}
		for (int i = 0; i < n; i++) {
			if (!same(values.get(uniforms.get(i).binding()), snapshot[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Two uniform values that give the same descriptor: the same buffer range (buffer object, offset, length), or the
	 * same texture view and sampler. The front end makes a new GpuBufferSlice or TextureViewAndSampler for each
	 * setUniform, so the objects themselves are rarely the same. A null value never matches (vanilla must see it).
	 */
	private static boolean same(@Nullable Object a, @Nullable Object b) {
		if (a == null || b == null) {
			return false;
		}
		if (a == b) {
			return true;
		}
		if (a instanceof GpuBufferSlice sa && b instanceof GpuBufferSlice sb) {
			return sa.buffer() == sb.buffer() && sa.offset() == sb.offset() && sa.length() == sb.length();
		}
		if (a instanceof TextureViewAndSampler ta && b instanceof TextureViewAndSampler tb) {
			return ta.view() == tb.view() && ta.sampler() == tb.sampler();
		}
		return false;
	}
}

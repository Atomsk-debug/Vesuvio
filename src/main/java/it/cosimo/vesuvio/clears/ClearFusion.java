package it.cosimo.vesuvio.clears;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSubpassBeginInfo;
import org.lwjgl.vulkan.VkSubpassEndInfo;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.clears.mixin.ClearsVulkanEncoderAccessor;
import it.cosimo.vesuvio.ui.VesuvioStats;

/**
 * Clear fusion and dead-clear removal (research-vulkan-engine.md, T1).
 *
 * <p>A texture clear issued through the command encoder (clearColorTexture, clearDepthTexture,
 * clearColorAndDepthTextures) is a separate Vulkan command: vkCmdClearColorImage or vkCmdClearDepthStencilImage
 * plus a full barrier. On MoltenVK each one is its own Metal render encoder that writes the whole image out, and
 * the render pass that comes next then reads it all back (vanilla's passes only clear in their load op when the
 * caller gives them a clear value). Post chains clear every target they take from the frame's pool, the hand and
 * the GUI clear the main depth, and so on.
 *
 * <p>Here such a clear is not sent at once: it is noted as pending (texture, value). What happens next decides
 * what becomes of it, always with the same pixels as vanilla:
 * <ul>
 * <li><b>Fused.</b> The next render pass attaches the texture, would load it (no clear value of its own) and
 * covers all of it (render area = whole texture, one mip level, one layer). The pass gets the pending value as
 * its clear value, so the backend picks LOAD_OP_CLEAR and the pixels start as the same value, with one full
 * write and one full read less. The value goes through the same conversion as the separate clear
 * (VulkanUtils.putArgb for colour, a float for depth). Depth formats with stencil are never fused (the separate
 * depth clear leaves stencil alone; a load op would too, but it is not worth relying on), they are sent.</li>
 * <li><b>Dropped.</b> The texture is cleared again, or a pass clears all of it in its own load op, or the texture
 * is destroyed, and nothing looked at it in between: the pending clear could never have been seen.</li>
 * <li><b>Sent</b> (flushed), as vanilla would have, just before anything that could see the texture: a pass that
 * attaches it in a way that cannot be fused (part of it, another mip, with stencil, twice), a write or copy into
 * or out of it, the copy to the window, the end of the frame. A pass that does not attach it may still sample
 * it, so every pending clear is sent before a pass begins, except the dead candidates below.</li>
 * </ul>
 *
 * <p><b>Dead candidates.</b> The two GUI depth clears (GameRenderer.render before the GUI, GuiRenderer.draw before
 * the menu blur) clear the main depth for a GUI that has no depth attachment: nothing reads it until the next
 * frame's main pass clears it again. These two survive passes that do not attach the main depth, and the end of
 * the frame too, so they normally end dropped (by the next frame's clear). Nothing is assumed about the passes in
 * between, including mods' passes: a pass that attaches the main depth fuses it (or gets it sent first), any
 * transfer that touches it sends it, and a pass that binds it as a texture to sample (FrontendRenderPass
 * setUniform, the only way a pass reads a texture that is not one of its attachments) gets it right there: the
 * open Vulkan render pass is ended (store), the clear and its barriers are recorded, and the same pass is begun
 * again on the same framebuffer with every attachment loaded. Draws already recorded did not use the texture
 * (it was not bound yet), Vulkan keeps bound pipeline, descriptor sets, buffers and dynamic state across render
 * pass instances in one command buffer, so later draws see the cleared texture exactly as in vanilla. That split
 * costs one store and load of the pass's attachments, so it is kept for clears that are dead in practice
 * (counted as "clears split", and logged once).
 *
 * <p><b>Post-chain depth diet</b> (ClearsPostChainMixin): every internal target of a post chain gets a depth
 * texture that vanilla allocates and clears every frame, though post passes are colour-only and sample a
 * target's depth only through an input with use_depth_buffer. Targets that no input of the chain reads as depth
 * get none, while this setting is on.
 *
 * <p>Only the Vulkan backend and only clears made on the render thread are deferred. A clear made on another
 * thread runs as vanilla (after dropping a pending clear of the same texture, which it overwrites); any other
 * command on any thread first sends the pending clears it touches. The table is locked, so another thread never
 * sees it half changed. With the setting (or the master switch) off nothing new is deferred and whatever is
 * still pending is sent at the next command that touches the encoder.
 */
public final class ClearFusion {
	public static final VesuvioConfig.Toggle CLEAR_FUSION = VesuvioConfig.toggle("rendering", "clearFusion", true, false);

	// Counters for the settings screen and benchmark, last frame (render thread).
	/** Clears turned into a render pass load op. */
	public static int fusedLastFrame;
	/** Clears that were never sent: overwritten or destroyed before anything could see them. */
	public static int droppedLastFrame;
	/** Dead-candidate clears a pass sampled after all, recorded in the middle of that pass (see class doc). */
	public static int splitLastFrame;

	private static final Object LOCK = new Object();
	/** Clears not sent yet, at most one per texture. A handful at a time, so a list beats a map. */
	private static final ArrayList<Pending> PENDING = new ArrayList<>();
	/** PENDING.size(), readable without the lock (the per-bind check reads it first). */
	private static volatile int pendingCount;
	private static int fused;
	private static int dropped;
	private static int split;
	private static boolean splitLogged;

	/** Set by the GUI clear call sites around their clearDepthTexture call (render thread). */
	private static boolean candidateMark;

	// The last Vulkan render pass begun (render thread): what a split needs to begin it again.
	private static RenderPassDescriptor beganDescriptor;
	private static long beganFramebuffer;
	private static int beganX;
	private static int beganY;
	private static int beganWidth;
	private static int beganHeight;

	private ClearFusion() {
	}

	private static final class Pending {
		final GpuTexture texture;
		final boolean depth;
		/** Copied: callers may change their vector after the call. */
		final Vector4f color;
		final double depthValue;
		final boolean deadCandidate;

		Pending(GpuTexture texture, boolean depth, Vector4f color, double depthValue, boolean deadCandidate) {
			this.texture = texture;
			this.depth = depth;
			this.color = color;
			this.depthValue = depthValue;
			this.deadCandidate = deadCandidate;
		}
	}

	/** Called from Vesuvio.onInitializeClient: makes the setting and registers the counters. */
	public static void init() {
		VesuvioStats.register("clears fused", () -> fusedLastFrame);
		VesuvioStats.register("clears dropped", () -> droppedLastFrame);
		VesuvioStats.register("clears split", () -> splitLastFrame);
	}

	// ---- clears (FrontendCommandEncoder, after its checks, instead of the backend call) ----

	/** The GUI call sites mark their depth clear as a dead candidate (render thread). */
	public static void markDeadCandidate() {
		candidateMark = true;
	}

	public static void clearDeadCandidateMark() {
		candidateMark = false;
	}

	/** Whether a new clear may be held back: the setting, the Vulkan backend, the render thread. */
	private static boolean mayDefer(CommandEncoderBackend backend) {
		return CLEAR_FUSION.on() && backend instanceof VulkanCommandEncoder && RenderSystem.isOnRenderThread();
	}

	/** A colour clear: true when it was held back (the caller then skips the backend call). */
	public static boolean deferColor(CommandEncoderBackend backend, GpuTexture texture, Vector4fc color) {
		boolean defer = mayDefer(backend);
		if (!defer && pendingCount == 0) {
			return false;
		}
		synchronized (LOCK) {
			supersede(texture);
			if (defer) {
				add(new Pending(texture, false, new Vector4f(color), 0.0, false));
			}
			return defer;
		}
	}

	/** A depth clear: true when it was held back. */
	public static boolean deferDepth(CommandEncoderBackend backend, GpuTexture texture, double depth) {
		boolean candidate = candidateMark;
		candidateMark = false;
		boolean defer = mayDefer(backend);
		if (!defer && pendingCount == 0) {
			return false;
		}
		synchronized (LOCK) {
			supersede(texture);
			if (defer) {
				add(new Pending(texture, true, null, depth, candidate));
			}
			return defer;
		}
	}

	/** Both at once (one call in vanilla): both held back, or neither. */
	public static boolean deferColorAndDepth(CommandEncoderBackend backend, GpuTexture colorTexture, Vector4fc color,
			GpuTexture depthTexture, double depth) {
		boolean defer = mayDefer(backend);
		if (!defer && pendingCount == 0) {
			return false;
		}
		synchronized (LOCK) {
			supersede(colorTexture);
			supersede(depthTexture);
			if (defer) {
				add(new Pending(colorTexture, false, new Vector4f(color), 0.0, false));
				add(new Pending(depthTexture, true, null, depth, false));
			}
			return defer;
		}
	}

	/**
	 * A new clear of the texture overwrites every pixel of every mip level, so a clear still pending for it is
	 * dead: anything that could have seen it would have sent it already. Under LOCK.
	 */
	private static void supersede(GpuTexture texture) {
		if (remove(texture) != null) {
			dropped++;
		}
	}

	// ---- render passes (FrontendCommandEncoder.createRenderPass, after its checks) ----

	/**
	 * Settles the pending clears before a render pass begins: fuses the ones it can take as load-op clears,
	 * drops the ones it overwrites, sends every other one except surviving dead candidates. Returns the descriptor
	 * to begin the pass with (the same one when nothing was fused).
	 */
	public static RenderPassDescriptor beforePass(CommandEncoderBackend backend, RenderPassDescriptor descriptor) {
		if (pendingCount == 0) {
			return descriptor;
		}
		synchronized (LOCK) {
			boolean on = CLEAR_FUSION.on();
			// Made only when something must be sent: most passes in the GUI phase find only a dead candidate.
			List<Pending> send = null;
			RenderPass.RenderArea area = descriptor.renderArea();
			List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
			List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> newColors = null;
			for (int i = 0; i < colors.size(); i++) {
				RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
				if (attachment == null) {
					continue;
				}
				GpuTexture texture = attachment.textureView().texture();
				Pending pending = remove(texture);
				if (pending == null) {
					continue;
				}
				if (on && !pending.depth && coversAll(attachment.textureView(), area) && attachedOnce(descriptor, texture)) {
					if (attachment.clearValue().isPresent()) {
						dropped++;
					} else {
						if (newColors == null) {
							newColors = new ArrayList<>(colors);
						}
						newColors.set(i, new RenderPassDescriptor.Attachment<>(attachment.textureView(),
								Optional.<Vector4fc>of(new Vector4f(pending.color))));
						fused++;
					}
				} else {
					send = sendLater(send, pending);
				}
			}
			RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
			RenderPassDescriptor.Attachment<OptionalDouble> newDepth = depthAttachment;
			if (depthAttachment != null) {
				GpuTexture texture = depthAttachment.textureView().texture();
				Pending pending = remove(texture);
				if (pending != null) {
					if (on && pending.depth && coversAll(depthAttachment.textureView(), area)
							&& attachedOnce(descriptor, texture)) {
						if (depthAttachment.clearValue().isPresent()) {
							// The pass's own depth clear writes every depth value; stencil is left alone either way.
							dropped++;
						} else if (!texture.getFormat().hasStencilAspect()) {
							newDepth = new RenderPassDescriptor.Attachment<>(depthAttachment.textureView(),
									OptionalDouble.of(pending.depthValue));
							fused++;
						} else {
							send = sendLater(send, pending);
						}
					} else {
						send = sendLater(send, pending);
					}
				}
			}
			// The rest is not attached; the pass may sample it. Only dead candidates wait (split if sampled).
			for (int i = PENDING.size() - 1; i >= 0; i--) {
				Pending pending = PENDING.get(i);
				if (!on || !pending.deadCandidate) {
					PENDING.remove(i);
					send = sendLater(send, pending);
				}
			}
			pendingCount = PENDING.size();
			if (send != null) {
				emit(backend, send);
			}
			if (newColors == null && newDepth == depthAttachment) {
				return descriptor;
			}
			return new RenderPassDescriptor(descriptor.label(), newColors != null ? newColors : colors, newDepth, area);
		}
	}

	/**
	 * Whether a load-op clear of this attachment writes exactly what the separate clear writes: the whole
	 * texture (one mip level, one layer, the view starts at mip 0) and a render area covering all of it.
	 */
	private static boolean coversAll(GpuTextureView view, RenderPass.RenderArea area) {
		GpuTexture texture = view.texture();
		return texture.getMipLevels() == 1 && texture.getDepthOrLayers() == 1 && view.baseMipLevel() == 0
				&& area.x() == 0 && area.y() == 0
				&& area.width() == texture.getWidth(0) && area.height() == texture.getHeight(0);
	}

	/** A texture attached in two slots (not allowed by Vulkan anyway) is never fused. */
	private static boolean attachedOnce(RenderPassDescriptor descriptor, GpuTexture texture) {
		int n = 0;
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : descriptor.colorAttachments()) {
			if (attachment != null && attachment.textureView().texture() == texture) {
				n++;
			}
		}
		if (descriptor.depthAttachment() != null && descriptor.depthAttachment().textureView().texture() == texture) {
			n++;
		}
		return n == 1;
	}

	// ---- sampling inside a pass (FrontendRenderPass.setUniform) ----

	/** A pass binds a texture to sample: a dead candidate still pending for it is recorded right here. */
	public static void beforeSample(GpuTextureView view) {
		if (pendingCount == 0) {
			return;
		}
		Pending pending;
		synchronized (LOCK) {
			pending = remove(view.texture());
			pendingCount = PENDING.size();
		}
		if (pending == null) {
			return;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		CommandEncoderBackend backend = encoder instanceof FrontendCommandEncoder frontend ? frontend.backend() : null;
		if (backend instanceof VulkanCommandEncoder vulkan && encoder instanceof FrontendCommandEncoder frontend
				&& frontend.isInRenderPass() && ((ClearsVulkanEncoderAccessor) vulkan).vesuvio$currentRenderPass() != null
				&& beganDescriptor != null) {
			splitPass(vulkan, pending);
		} else if (backend != null) {
			// Not inside a pass after all (a stale pass object): an ordinary clear is allowed and in order.
			List<Pending> one = new ArrayList<>(1);
			one.add(pending);
			emit(backend, one);
		}
	}

	/**
	 * Ends the open Vulkan render pass, records the clear with its barriers, and begins the same pass again
	 * (same framebuffer, same render area, every attachment loaded). See the class doc for why this is exact.
	 */
	private static void splitPass(VulkanCommandEncoder encoder, Pending pending) {
		ClearsVulkanEncoderAccessor access = (ClearsVulkanEncoderAccessor) encoder;
		VkCommandBuffer commandBuffer = access.vesuvio$commandBuffer();
		long loadPass = access.vesuvio$device().renderpassCache().getRenderPass(loadOnly(beganDescriptor));
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK12.vkCmdEndRenderPass2(commandBuffer, VkSubpassEndInfo.calloc(stack).sType$Default());
			VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
			if (pending.depth) {
				encoder.clearDepthTextureUnsynced(stack, pending.texture, pending.depthValue);
			} else {
				access.vesuvio$clearColorTextureUnsynced(stack, pending.texture, pending.color);
			}
			VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
			VkRect2D area = VkRect2D.calloc(stack);
			area.offset().set(beganX, beganY);
			area.extent().set(beganWidth, beganHeight);
			VkRenderPassBeginInfo beginInfo = VkRenderPassBeginInfo.calloc(stack).sType$Default();
			beginInfo.renderPass(loadPass);
			beginInfo.framebuffer(beganFramebuffer);
			beginInfo.renderArea(area);
			VkSubpassBeginInfo subpassBeginInfo = VkSubpassBeginInfo.calloc(stack).sType$Default();
			subpassBeginInfo.contents(0);
			VK12.vkCmdBeginRenderPass2(commandBuffer, beginInfo, subpassBeginInfo);
		}
		split++;
		if (!splitLogged) {
			splitLogged = true;
			Vesuvio.LOGGER.info("Vesuvio clearFusion: a pass sampled {} while its clear was held back; cleared inside the pass"
					+ " (exact, a little slower; counted as \"clears split\")", pending.texture.getLabel());
		}
	}

	/** The same attachments with no clear values: every one loaded (the framebuffer stays compatible). */
	private static RenderPassDescriptor loadOnly(RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = new ArrayList<>(descriptor.colorAttachments().size());
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : descriptor.colorAttachments()) {
			colors.add(attachment == null ? null : new RenderPassDescriptor.Attachment<>(attachment.textureView(), Optional.empty()));
		}
		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment() == null ? null
				: new RenderPassDescriptor.Attachment<>(descriptor.depthAttachment().textureView(), OptionalDouble.empty());
		return new RenderPassDescriptor(descriptor.label(), colors, depth, descriptor.renderArea());
	}

	/** VulkanCommandEncoder.createRenderPass, as it begins the pass: remembered for a split. Render thread. */
	public static void onVulkanPassBegin(RenderPassDescriptor descriptor, VkRenderPassBeginInfo beginInfo) {
		beganDescriptor = descriptor;
		beganFramebuffer = beginInfo.framebuffer();
		VkRect2D area = beginInfo.renderArea();
		beganX = area.offset().x();
		beganY = area.offset().y();
		beganWidth = area.extent().width();
		beganHeight = area.extent().height();
	}

	/** Drops the remembered pass (its descriptor holds the pass's texture views). Render thread, outside any pass. */
	private static void forgetPass() {
		beganDescriptor = null;
		beganFramebuffer = 0L;
	}

	/**
	 * The world is changing or being left (Vesuvio.levelChanged, render thread, outside any pass): lets go of the last
	 * pass's attachments. Pending clears stay: they are sent, fused or dropped as usual (only the GUI's dead depth
	 * clears of the main target can still be pending here, and the main target outlives the world).
	 */
	public static void release() {
		forgetPass();
	}

	// ---- everything else that can see a texture ----

	/** A write, copy or read of these textures (either may be null) is about to be recorded: send their clears first. */
	public static void beforeTransfer(CommandEncoderBackend backend, GpuTexture first, GpuTexture second) {
		if (pendingCount == 0) {
			return;
		}
		synchronized (LOCK) {
			Pending a = first != null ? remove(first) : null;
			Pending b = second != null ? remove(second) : null;
			boolean on = CLEAR_FUSION.on();
			if (a == null && b == null && on) {
				// Nothing of these textures pending (the usual case while only a dead candidate waits).
				return;
			}
			List<Pending> send = new ArrayList<>(2);
			if (a != null) {
				send.add(a);
			}
			if (b != null) {
				send.add(b);
			}
			if (!on) {
				// Switched off: send everything now rather than keep any of it.
				send.addAll(PENDING);
				PENDING.clear();
			}
			pendingCount = PENDING.size();
			emit(backend, send);
		}
	}

	/**
	 * The frame is about to be submitted: every pending clear is sent, except dead candidates, which stay for the
	 * next frame (its main pass normally clears the same depth again and drops them; anything that looks at the
	 * texture first sends or splits them, so carrying them over is the same as sending them now).
	 */
	public static void beforeSubmit(CommandEncoderBackend backend) {
		if (pendingCount != 0) {
			synchronized (LOCK) {
				boolean on = CLEAR_FUSION.on() && RenderSystem.isOnRenderThread();
				List<Pending> send = null;
				for (int i = PENDING.size() - 1; i >= 0; i--) {
					Pending pending = PENDING.get(i);
					if (!on || !pending.deadCandidate) {
						PENDING.remove(i);
						send = sendLater(send, pending);
					}
				}
				pendingCount = PENDING.size();
				if (send != null) {
					emit(backend, send);
				}
			}
		}
		// No pass is open at a submit, so nothing can split the last one any more: let go of its attachments.
		forgetPass();
		fusedLastFrame = fused;
		droppedLastFrame = dropped;
		splitLastFrame = split;
		fused = 0;
		dropped = 0;
		split = 0;
	}

	/** The texture's image is about to be destroyed (closed, last view closed): nothing can see its clear any more. */
	public static void onTextureDestroyed(GpuTexture texture) {
		if (pendingCount == 0) {
			return;
		}
		synchronized (LOCK) {
			if (remove(texture) != null) {
				dropped++;
			}
			pendingCount = PENDING.size();
		}
	}

	// ---- table ----

	/** Adds a clear to the list of those to send, making the list on first use. */
	private static List<Pending> sendLater(List<Pending> send, Pending pending) {
		List<Pending> list = send != null ? send : new ArrayList<>(4);
		list.add(pending);
		return list;
	}

	private static void add(Pending pending) {
		PENDING.add(pending);
		pendingCount = PENDING.size();
	}

	private static Pending remove(GpuTexture texture) {
		for (int i = 0; i < PENDING.size(); i++) {
			if (PENDING.get(i).texture == texture) {
				Pending pending = PENDING.remove(i);
				pendingCount = PENDING.size();
				return pending;
			}
		}
		return null;
	}

	/**
	 * Records the clears as vanilla would have (a colour and a depth clear with the same mip count go as one
	 * clearColorAndDepthTextures, one barrier: the same as two calls, the images are different). Outside any pass.
	 */
	private static void emit(CommandEncoderBackend backend, List<Pending> send) {
		int n = send.size();
		for (int i = 0; i < n; i++) {
			Pending p = send.get(i);
			if (p == null) {
				continue;
			}
			if (!p.depth) {
				for (int j = 0; j < n; j++) {
					Pending q = send.get(j);
					if (q != null && q.depth && q.texture.getMipLevels() == p.texture.getMipLevels()) {
						send.set(j, null);
						send.set(i, null);
						backend.clearColorAndDepthTextures(p.texture, p.color, q.texture, q.depthValue);
						break;
					}
				}
				if (send.get(i) != null) {
					send.set(i, null);
					backend.clearColorTexture(p.texture, p.color);
				}
			}
		}
		for (int i = 0; i < n; i++) {
			Pending p = send.get(i);
			if (p != null) {
				backend.clearDepthTexture(p.texture, p.depthValue);
			}
		}
	}
}

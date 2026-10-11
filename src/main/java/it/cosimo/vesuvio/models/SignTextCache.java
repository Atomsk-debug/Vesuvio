package it.cosimo.vesuvio.models;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;

import it.cosimo.vesuvio.VesuvioConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

/**
 * Cached sign text (idea from ImmediatelyFast; Vesuvio's own code). Every frame vanilla measures each sign line
 * ({@code Font.width}) and lays it out again ({@code Font.prepareText}: a glyph lookup and a new glyph object per
 * letter, eight times over for an outlined glowing line) before writing its vertices. The layout does not depend on
 * where the sign is: it is a list of glyphs at text-space positions, and the pose is applied only when the vertices
 * are written ({@code TextRenderable.render(pose, ...)}). So the layout of each line is kept and replayed with the
 * current pose: same glyphs, same vertices, same order.
 *
 * <p>What makes a kept layout exact:
 * <ul>
 * <li>The line itself. Vanilla keeps each sign's lines in its {@code SignText} (immutable; a changed sign is a new
 * one) as finished character sequences. Here each such line is wrapped once ({@link Line}, which reads exactly like
 * the line it wraps), and the wrapper carries the cache, so a cached layout can only ever be used for the very
 * sequence it was made from.</li>
 * <li>Everything else the layout depends on is checked on every use: the font object, position, colour, shadow,
 * background and outline colour, and a font generation that goes up whenever the game rebuilds its glyphs (resource
 * reload, font options such as Force Unicode, closing a font set) or the world changes.</li>
 * <li>Lines that would not lay out the same way twice are never cached: obfuscated (§k) text picks random glyphs every
 * frame, and sprite or player-head fonts can change their glyphs without a reload (a skin finishing its download).
 * Those lines run vanilla's code every frame.</li>
 * </ul>
 * At most {@link #MAX_LINES} lines keep a layout; the least recently drawn give theirs up first.
 */
public final class SignTextCache {
	public static final VesuvioConfig.Toggle SIGN_TEXT_CACHE = VesuvioConfig.toggle("entities", "signTextCache", true, false);

	/** Most sign lines that keep a layout at once (about 1 KB each, 8 KB for an outlined glowing line). */
	static final int MAX_LINES = 4096;

	/** Layouts served from the cache since the last frame ended, and last frame (counter). */
	private static int hits;
	public static int hitsLastFrame;

	/** Goes up whenever glyphs may have changed; any thread. */
	private static volatile int fontGeneration;

	// ---- Render thread only ----
	/** The generation the cache was last emptied for. */
	private static int clearedGeneration;
	/** Lines holding a layout, most recently used first. */
	private static Line head;
	private static Line tail;
	private static int size;

	private SignTextCache() {
	}

	/** End of a frame (command encoder submit): publishes the counter. */
	public static void endFrame() {
		hitsLastFrame = hits;
		hits = 0;
	}

	/** Glyphs may have changed (font set rebuilt or closed, world changed): every kept layout is dropped. */
	public static void invalidate() {
		fontGeneration++;
		if (RenderSystem.isOnRenderThread()) {
			// Let go of the kept layouts now (they point at the old glyphs), not at the next sign drawn.
			generation();
		}
	}

	/** A sign's lines as wrapped for one {@code getRenderMessages} array (kept on the SignText). */
	public static final class Lines {
		final FormattedCharSequence[] source;
		final FormattedCharSequence[] wrapped;

		Lines(FormattedCharSequence[] source) {
			this.source = source;
			this.wrapped = new FormattedCharSequence[source.length];
			for (int i = 0; i < source.length; i++) {
				FormattedCharSequence line = source[i];
				// An empty line costs vanilla nothing (and EMPTY is one object shared by every sign).
				this.wrapped[i] = line == FormattedCharSequence.EMPTY || line == null ? line : new Line(line);
			}
		}

		boolean wraps(FormattedCharSequence[] messages) {
			if (messages != this.source || messages.length != this.wrapped.length) {
				return false;
			}
			for (int i = 0; i < messages.length; i++) {
				FormattedCharSequence w = this.wrapped[i];
				FormattedCharSequence original = w instanceof Line line ? line.line : w;
				if (original != messages[i]) {
					return false;
				}
			}
			return true;
		}
	}

	/** Implemented on SignText by a mixin: where a sign's wrapped lines are kept. */
	public interface Holder {
		Lines vesuvio$signLines();

		void vesuvio$setSignLines(Lines lines);
	}

	/**
	 * One sign line: reads exactly like the vanilla line it wraps (every visitor sees the same characters and styles),
	 * and carries that line's cached width and layouts.
	 */
	public static final class Line implements FormattedCharSequence {
		final FormattedCharSequence line;
		/** 0 not checked yet, 1 can be cached, 2 never (obfuscated, or a font that is not a plain resource font). */
		private byte cacheable;

		private Font widthFont;
		private int widthGeneration;
		private int width;

		private Font textFont;
		private int textGeneration;
		private int textX;
		private int textY;
		private int textColor;
		private boolean textShadow;
		private boolean textIncludeEmpty;
		private int textBackground;
		private Font.PreparedText text;

		private Font outlineFont;
		private int outlineGeneration;
		private int outlineX;
		private int outlineY;
		private int outlineColor;
		private Font.PreparedText outline;

		private Line prev;
		private Line next;
		private boolean linked;

		Line(FormattedCharSequence line) {
			this.line = line;
		}

		@Override
		public boolean accept(FormattedCharSink output) {
			return this.line.accept(output);
		}

		boolean cacheable() {
			if (this.cacheable == 0) {
				boolean[] ok = { true };
				this.line.accept((position, style, codepoint) -> {
					if (!stable(style)) {
						ok[0] = false;
						return false;
					}
					return true;
				});
				this.cacheable = ok[0] ? (byte) 1 : (byte) 2;
			}
			return this.cacheable == 1;
		}

		private static boolean stable(Style style) {
			return !style.isObfuscated() && style.getFont() instanceof FontDescription.Resource;
		}

		void drop() {
			this.text = null;
			this.textFont = null;
			this.outline = null;
			this.outlineFont = null;
		}
	}

	/** AbstractSignRenderer.submitSignText: the sign's lines, wrapped so the text renderer can find their cache. */
	public static FormattedCharSequence[] wrap(Object signText, FormattedCharSequence[] messages) {
		if (messages == null || !SIGN_TEXT_CACHE.on() || !RenderSystem.isOnRenderThread()) {
			return messages;
		}
		Holder holder = (Holder) signText;
		Lines lines = holder.vesuvio$signLines();
		if (lines == null || !lines.wraps(messages)) {
			lines = new Lines(messages);
			holder.vesuvio$setSignLines(lines);
		}
		return lines.wrapped;
	}

	/** AbstractSignRenderer.submitSignText, {@code font.width(line)}. */
	public static int width(Font font, FormattedCharSequence text, Operation<Integer> original) {
		if (!(text instanceof Line line) || !usable(line)) {
			return original.call(font, text);
		}
		int generation = generation();
		if (line.widthFont == font && line.widthGeneration == generation) {
			return line.width;
		}
		int width = original.call(font, text);
		line.widthFont = font;
		line.widthGeneration = generation;
		line.width = width;
		return width;
	}

	/** TextFeatureRenderer.renderText, {@code font.prepareText(...)}. */
	public static Font.PreparedText prepareText(Font font, FormattedCharSequence text, float x, float y, int color,
			boolean drawShadow, boolean includeEmpty, int backgroundColor, Operation<Font.PreparedText> original) {
		if (!(text instanceof Line line) || !usable(line)) {
			return original.call(font, text, x, y, color, drawShadow, includeEmpty, backgroundColor);
		}
		int generation = generation();
		int xBits = Float.floatToRawIntBits(x);
		int yBits = Float.floatToRawIntBits(y);
		if (line.text != null && line.textFont == font && line.textGeneration == generation && line.textX == xBits
				&& line.textY == yBits && line.textColor == color && line.textShadow == drawShadow
				&& line.textIncludeEmpty == includeEmpty && line.textBackground == backgroundColor) {
			hits++;
			touch(line);
			return line.text;
		}
		Font.PreparedText prepared = original.call(font, text, x, y, color, drawShadow, includeEmpty, backgroundColor);
		line.textFont = font;
		line.textGeneration = generation;
		line.textX = xBits;
		line.textY = yBits;
		line.textColor = color;
		line.textShadow = drawShadow;
		line.textIncludeEmpty = includeEmpty;
		line.textBackground = backgroundColor;
		line.text = prepared;
		touch(line);
		return prepared;
	}

	/** TextFeatureRenderer.renderText, {@code font.prepare8xTextOutline(...)} (glowing text with an outline). */
	public static Font.PreparedText prepareOutline(Font font, FormattedCharSequence text, float x, float y,
			int outlineColor, Operation<Font.PreparedText> original) {
		if (!(text instanceof Line line) || !usable(line)) {
			return original.call(font, text, x, y, outlineColor);
		}
		int generation = generation();
		int xBits = Float.floatToRawIntBits(x);
		int yBits = Float.floatToRawIntBits(y);
		if (line.outline != null && line.outlineFont == font && line.outlineGeneration == generation
				&& line.outlineX == xBits && line.outlineY == yBits && line.outlineColor == outlineColor) {
			hits++;
			touch(line);
			return line.outline;
		}
		Font.PreparedText prepared = original.call(font, text, x, y, outlineColor);
		line.outlineFont = font;
		line.outlineGeneration = generation;
		line.outlineX = xBits;
		line.outlineY = yBits;
		line.outlineColor = outlineColor;
		line.outline = prepared;
		touch(line);
		return prepared;
	}

	private static boolean usable(Line line) {
		return SIGN_TEXT_CACHE.on() && RenderSystem.isOnRenderThread() && line.cacheable();
	}

	/** The current font generation; empties the cache first if it went up since the last time. */
	private static int generation() {
		int generation = fontGeneration;
		if (generation != clearedGeneration) {
			for (Line line = head; line != null;) {
				Line next = line.next;
				line.drop();
				line.prev = null;
				line.next = null;
				line.linked = false;
				line = next;
			}
			head = null;
			tail = null;
			size = 0;
			clearedGeneration = generation;
		}
		return generation;
	}

	/** Moves a line to the front of the LRU list (adding it if new) and lets the oldest go past the bound. */
	private static void touch(Line line) {
		if (line.linked) {
			if (line == head) {
				return;
			}
			unlink(line);
		}
		line.next = head;
		line.prev = null;
		if (head != null) {
			head.prev = line;
		}
		head = line;
		if (tail == null) {
			tail = line;
		}
		line.linked = true;
		size++;
		while (size > MAX_LINES && tail != null) {
			Line oldest = tail;
			unlink(oldest);
			oldest.drop();
		}
	}

	private static void unlink(Line line) {
		if (line.prev != null) {
			line.prev.next = line.next;
		} else {
			head = line.next;
		}
		if (line.next != null) {
			line.next.prev = line.prev;
		} else {
			tail = line.prev;
		}
		line.prev = null;
		line.next = null;
		line.linked = false;
		size--;
	}
}

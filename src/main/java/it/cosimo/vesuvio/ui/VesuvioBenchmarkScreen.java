package it.cosimo.vesuvio.ui;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The benchmark's summary when a run finishes: one row per step. The full table is in latest.log. */
public final class VesuvioBenchmarkScreen extends Screen {
	/** A step: fps, mean and 95th percentile frame time (ms), and its mean minus the "on" steps' (NaN: it is one). */
	public record Row(String name, long fps, double frameMs, double p95Ms, double vsOnMs) {
	}

	private static final int TOP = 54;
	private static final int LINE = 11;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFC8C8C8;

	private final List<Row> rows;
	private final double onFps;
	private final double offFps;
	private int scroll;

	public VesuvioBenchmarkScreen(List<Row> rows, double onFps, double offFps) {
		super(Component.translatable("vesuvio.benchmark.title"));
		this.rows = List.copyOf(rows);
		this.onFps = onFps;
		this.offFps = offFps;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int visible = Math.max(1, (this.height - TOP - 44) / LINE);
		this.scroll = Math.max(0, Math.min(Math.max(0, this.rows.size() - visible), this.scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 10, TEXT);
		String gain = this.offFps > 0 ? String.format(Locale.ROOT, "%+.0f", (this.onFps / this.offFps - 1.0) * 100.0) : "?";
		graphics.centeredText(this.font, Component.translatable("vesuvio.benchmark.summary", Math.round(this.offFps),
				Math.round(this.onFps), gain), this.width / 2, 24, TEXT);

		int tableWidth = Math.min(this.width - 16, 400);
		int left = (this.width - tableWidth) / 2;
		int nameRight = left + tableWidth - 4 * 52;
		int[] cols = {nameRight + 52, nameRight + 104, nameRight + 156, nameRight + 208};
		int y = TOP - LINE - 2;
		graphics.text(this.font, Component.translatable("vesuvio.benchmark.col.step"), left, y, DIM);
		String[] heads = {"vesuvio.benchmark.col.fps", "vesuvio.benchmark.col.frame", "vesuvio.benchmark.col.p95",
				"vesuvio.benchmark.col.vsOn"};
		for (int c = 0; c < 4; c++) {
			Component h = Component.translatable(heads[c]);
			graphics.text(this.font, h, cols[c] - this.font.width(h), y, DIM);
		}
		y = TOP;
		int bottom = this.height - 44;
		for (int i = this.scroll; i < this.rows.size() && y + LINE <= bottom; i++) {
			Row r = this.rows.get(i);
			graphics.text(this.font, this.font.plainSubstrByWidth(i + "  " + r.name, nameRight - left), left, y, TEXT);
			right(graphics, Long.toString(r.fps), cols[0], y, TEXT);
			right(graphics, String.format(Locale.ROOT, "%.2f", r.frameMs), cols[1], y, TEXT);
			right(graphics, String.format(Locale.ROOT, "%.2f", r.p95Ms), cols[2], y, TEXT);
			if (Double.isNaN(r.vsOnMs)) {
				right(graphics, "ref", cols[3], y, DIM);
			} else {
				// Positive: the step is slower than "on", so what it switched off was saving that much per frame.
				right(graphics, String.format(Locale.ROOT, "%+.2f", r.vsOnMs), cols[3], y, TEXT);
			}
			y += LINE;
		}
		graphics.centeredText(this.font, Component.translatable("vesuvio.benchmark.footer"), this.width / 2, this.height - 40,
				DIM);
	}

	private void right(GuiGraphicsExtractor graphics, String s, int x, int y, int color) {
		graphics.text(this.font, s, x - this.font.width(s), y, color);
	}
}

package it.cosimo.vesuvio.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.memory.MemoryAdvisor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Vesuvio's settings, laid out like Sodium's: pages on the left, the page's options in a scrolling list,
 * and a box on the right that explains the option under the mouse (or shows the live counters). It lists
 * whatever settings exist ({@link VesuvioConfig#all()}), grouped by their page, so a new optimization's
 * settings appear without any change here. Every change applies at once and is saved to
 * config/vesuvio.properties; options marked * take effect after a restart.
 */
public final class VesuvioSettingsScreen extends Screen {
	/** Pages in this order first; any other page a setting names follows, in the order first seen. */
	private static final List<String> PAGE_ORDER = List.of("general", "rendering", "entities", "memory", "frame", "world");
	private static final int SIDEBAR = 120;
	private static final int TOP = 32;
	private static final int ROW = 24;
	private static final int FOOTER = 36;
	private static final int PANEL_MIN = 120;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFC8C8C8;
	private static final int GOLD = 0xFFFFAA00;
	private static final int RED = 0xFFFF5555;
	/**
	 * Values of the restart-only settings as the game started with them (first time this screen saw them:
	 * nothing else changes those), to say when a restart is due.
	 */
	private static final Map<String, String> AT_START = new HashMap<>();

	private final Screen lastScreen;
	private final String page;
	private @Nullable OptionList list;
	private int panelLeft;
	private boolean panel;

	public VesuvioSettingsScreen(Screen lastScreen) {
		this(lastScreen, "general");
	}

	private VesuvioSettingsScreen(Screen lastScreen, String page) {
		super(Component.translatable("vesuvio.screen.title"));
		this.lastScreen = lastScreen;
		this.page = page;
	}

	@Override
	protected void init() {
		// A menu stops a benchmark anyway; do it before reading any value, so the player's own ones show.
		VesuvioBenchmark.stop(VesuvioBenchmark.Stop.MENU);
		List<VesuvioConfig.Setting> all = VesuvioConfig.all();
		for (VesuvioConfig.Setting s : all) {
			if (s.needsRestart) {
				AT_START.putIfAbsent(s.key, value(s));
			}
		}

		int y = TOP;
		for (String p : pages(all)) {
			Button tab = Button.builder(pageName(p), b -> this.open(p)).bounds(8, y, SIDEBAR - 16, 20).build();
			tab.active = !p.equals(this.page);
			this.addRenderableWidget(tab);
			y += 22;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("vesuvio.screen.reset"), b -> this.resetPage())
				.bounds(8, this.height - 28, SIDEBAR - 16, 20)
				.tooltip(Component.translatable("vesuvio.screen.reset.tooltip"))
				.build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
				.bounds(this.width - 108, this.height - 28, 100, 20).build());

		int listLeft = SIDEBAR + 4;
		int room = this.width - listLeft - 8;
		int listWidth;
		if (room >= 200 + PANEL_MIN + 8) {
			listWidth = Math.min(320, Math.max(200, room * 55 / 100));
			this.panel = true;
		} else {
			listWidth = room;
			this.panel = false;
		}
		this.panelLeft = listLeft + listWidth + 8;
		OptionList options = new OptionList(this.minecraft, listWidth, this.height - TOP - FOOTER, TOP);
		options.updateSizeAndPosition(listWidth, this.height - TOP - FOOTER, listLeft, TOP);
		if (this.page.equals("memory")) {
			options.add(this.memoryRow());
		}
		for (VesuvioConfig.Setting s : all) {
			if (s.page.equals(this.page)) {
				options.add(this.row(s));
			}
		}
		if (this.page.equals("general")) {
			options.add(this.benchmarkRow());
		}
		this.list = this.addRenderableWidget(options);
	}

	/** Pages to show: the known ones always, then any other page some setting is on. */
	private static List<String> pages(List<VesuvioConfig.Setting> all) {
		Set<String> pages = new LinkedHashSet<>(PAGE_ORDER);
		for (VesuvioConfig.Setting s : all) {
			pages.add(s.page);
		}
		List<String> out = new ArrayList<>();
		for (String p : pages) {
			// "world" and unknown pages only once something is on them.
			if (PAGE_ORDER.indexOf(p) >= 0 && PAGE_ORDER.indexOf(p) < 5 || all.stream().anyMatch(s -> s.page.equals(p))) {
				out.add(p);
			}
		}
		return out;
	}

	private Row row(VesuvioConfig.Setting s) {
		Component name = name(s);
		Component label = s.needsRestart
				? Component.empty().append(name).append(Component.literal(" *").withStyle(ChatFormatting.GOLD))
				: name;
		AbstractWidget widget;
		if (s instanceof VesuvioConfig.Toggle t) {
			Button button = Button.builder(toggleLabel(label, t.get()), b -> {
				t.set(!t.get());
				b.setMessage(toggleLabel(label, t.get()));
				VesuvioConfig.save();
				VesuvioChanges.changed(t);
			}).size(200, 20).build();
			widget = button;
		} else {
			widget = new Slider(label, (VesuvioConfig.Number) s);
		}
		Row row = new Row(widget, s, description(s));
		if (!this.panel) {
			widget.setTooltip(Tooltip.create(row.text()));
		}
		return row;
	}

	private Row benchmarkRow() {
		boolean inWorld = this.minecraft.level != null && this.minecraft.player != null;
		Button button = Button.builder(Component.translatable("vesuvio.benchmark.button"), b -> {
			VesuvioConfig.save();
			this.minecraft.gui.setScreen(null);
			VesuvioBenchmark.start();
		}).size(200, 20).build();
		button.active = inWorld;
		Row row = new Row(button, null, new Description(Component.translatable("vesuvio.benchmark.button"),
				Component.translatable(inWorld ? "vesuvio.benchmark.tooltip" : "vesuvio.benchmark.noWorld"), null));
		if (!this.panel) {
			button.setTooltip(Tooltip.create(row.text()));
		}
		return row;
	}

	/** The memory check: its verdict on the button, the advice and the numbers in the box; a click says it in chat. */
	private Row memoryRow() {
		Button button = Button.builder(MemoryAdvisor.status(), b -> {
			this.minecraft.gui.hud.getChat().addClientSystemMessage(
					Component.translatable("vesuvio.memory.chat", MemoryAdvisor.advice()));
			b.setMessage(MemoryAdvisor.status());
		}).size(200, 20).build();
		Row row = new Row(button, null, new Description(Component.translatable("vesuvio.memory.title").withStyle(ChatFormatting.BOLD),
				Component.empty().append(MemoryAdvisor.advice()).append("\n\n").append(Component.translatable("vesuvio.memory.howTo")),
				Component.literal(MemoryAdvisor.facts())));
		if (!this.panel) {
			button.setTooltip(Tooltip.create(row.text()));
		}
		return row;
	}

	private static Component toggleLabel(Component name, boolean on) {
		return Options.genericValueLabel(name, on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private void resetPage() {
		for (VesuvioConfig.Setting s : VesuvioConfig.all()) {
			if (s.page.equals(this.page)) {
				String before = value(s);
				s.reset();
				if (!before.equals(value(s))) {
					VesuvioChanges.changed(s);
				}
			}
		}
		VesuvioConfig.save();
		this.open(this.page);
	}

	private void open(String target) {
		this.minecraft.gui.setScreen(new VesuvioSettingsScreen(this.lastScreen, target));
	}

	@Override
	public void removed() {
		// Sliders save when let go; this catches keyboard changes.
		VesuvioConfig.save();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, SIDEBAR, this.height, 0x70000000);
		graphics.centeredText(this.font, this.title, SIDEBAR / 2, 12, TEXT);
		super.extractRenderState(graphics, mouseX, mouseY, a);

		int x = SIDEBAR + 8;
		graphics.text(this.font, pageName(this.page), x, 12, TEXT);
		int noteX = x + this.font.width(pageName(this.page)) + 12;
		if (!VesuvioConfig.ENABLED.get()) {
			graphics.text(this.font, Component.translatable("vesuvio.screen.masterOff"), noteX, 12, GOLD);
		} else if (restartDue()) {
			graphics.text(this.font, Component.translatable("vesuvio.screen.restartDue"), noteX, 12, RED);
		}
		OptionList options = this.list;
		if (options != null && options.children().isEmpty()) {
			graphics.text(this.font, Component.translatable("vesuvio.screen.emptyPage"), x, TOP + 8, DIM);
		}
		if (options != null && options.children().stream().anyMatch(r -> r.setting != null && r.setting.needsRestart)) {
			graphics.text(this.font, Component.translatable("vesuvio.screen.restartLegend"), x, this.height - 22, GOLD);
		}
		if (this.panel && options != null) {
			this.extractPanel(graphics, options);
		}
	}

	/** The description box: the option under the mouse (or with keyboard focus), else the live counters. */
	private void extractPanel(GuiGraphicsExtractor graphics, OptionList options) {
		int left = this.panelLeft;
		int right = this.width - 8;
		int width = right - left - 12;
		int bottom = this.height - FOOTER;
		graphics.fill(left, TOP, right, bottom, 0x90000000);
		int y = TOP + 6;
		Row row = options.hoveredRow();
		if (row == null) {
			row = options.getFocused();
		}
		if (row != null) {
			Description d = row.description;
			y = graphics.textWithWordWrap(this.font, d.title, left + 6, y, width, TEXT) + 4;
			y = graphics.textWithWordWrap(this.font, d.body, left + 6, y, width, DIM) + 4;
			if (d.note != null) {
				graphics.textWithWordWrap(this.font, d.note, left + 6, y, width, GOLD);
			}
			return;
		}
		y = graphics.textWithWordWrap(this.font, Component.translatable("vesuvio.screen.hint"), left + 6, y, width, DIM) + 8;
		List<VesuvioStats.Stat> stats = VesuvioStats.all();
		if (stats.isEmpty()) {
			return;
		}
		graphics.text(this.font, Component.translatable("vesuvio.screen.stats"), left + 6, y, TEXT);
		y += 12;
		boolean on = VesuvioConfig.ENABLED.get();
		for (VesuvioStats.Stat s : stats) {
			if (y > bottom - 10) {
				break;
			}
			String line = s.label() + ": " + (on ? Integer.toString(s.read()) : "-");
			graphics.text(this.font, this.font.plainSubstrByWidth(line, width), left + 6, y, DIM);
			y += 10;
		}
	}

	private static boolean restartDue() {
		for (VesuvioConfig.Setting s : VesuvioConfig.all()) {
			String start = AT_START.get(s.key);
			if (s.needsRestart && start != null && !start.equals(value(s))) {
				return true;
			}
		}
		return false;
	}

	private static String value(VesuvioConfig.Setting s) {
		if (s instanceof VesuvioConfig.Toggle t) {
			return Boolean.toString(t.get());
		}
		return Integer.toString(((VesuvioConfig.Number) s).get());
	}

	private static Component pageName(String page) {
		return translatedOr("vesuvio.page." + page, Character.toUpperCase(page.charAt(0)) + page.substring(1));
	}

	private static Component name(VesuvioConfig.Setting s) {
		return translatedOr(s.nameKey(), s.key);
	}

	/** A translation, or the fallback text when the language file has no such key (a setting added without one). */
	private static Component translatedOr(String key, String fallback) {
		return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(fallback);
	}

	private static Description description(VesuvioConfig.Setting s) {
		Component body = Language.getInstance().has(s.tooltipKey())
				? Component.translatable(s.tooltipKey())
				: Component.translatable("vesuvio.screen.noTooltip");
		MutableComponent note = Component.empty();
		if (s instanceof VesuvioConfig.Toggle t) {
			note.append(Component.translatable("vesuvio.screen.default",
					t.defaultValue ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF));
		} else {
			VesuvioConfig.Number n = (VesuvioConfig.Number) s;
			note.append(Component.translatable("vesuvio.screen.defaultRange", n.defaultValue, n.min, n.max));
		}
		if (s.needsRestart) {
			note.append(" ").append(Component.translatable("vesuvio.screen.needsRestart"));
		}
		if (s == it.cosimo.vesuvio.frame.GpuPassTimers.SETTING && it.cosimo.vesuvio.frame.GpuPassTimers.unsupported()) {
			note.append(" ").append(Component.translatable("vesuvio.option.gpuPassTimers.unsupported"));
		}
		return new Description(name(s).copy().withStyle(ChatFormatting.BOLD), body, note);
	}

	private record Description(Component title, Component body, @Nullable Component note) {
	}

	/** The scrolling column of options. */
	static final class OptionList extends ContainerObjectSelectionList<Row> {
		OptionList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, ROW);
			this.centerListVertically = false;
		}

		void add(Row row) {
			this.addEntry(row);
		}


		@Override
		public int getRowWidth() {
			return this.getWidth() - 16;
		}

		@Override
		protected int scrollBarX() {
			return this.getRight() - 6;
		}

		@Nullable Row hoveredRow() {
			return this.getHovered();
		}
	}

	/** One option: its button or slider, filling the row. */
	static final class Row extends ContainerObjectSelectionList.Entry<Row> {
		final AbstractWidget widget;
		final VesuvioConfig.@Nullable Setting setting;
		final Description description;

		Row(AbstractWidget widget, VesuvioConfig.@Nullable Setting setting, Description description) {
			this.widget = widget;
			this.setting = setting;
			this.description = description;
		}

		/** The whole description as one text, for a tooltip when there is no room for the box. */
		Component text() {
			MutableComponent t = Component.empty().append(this.description.body);
			if (this.description.note != null) {
				t.append("\n\n").append(this.description.note);
			}
			return t;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			this.widget.setPosition(this.getContentX(), this.getContentY());
			this.widget.setWidth(this.getContentWidth());
			this.widget.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(this.widget);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(this.widget);
		}
	}

	/** A whole-number setting. Applies live while dragged, saves when let go. */
	private static final class Slider extends AbstractSliderButton {
		private final Component name;
		private final VesuvioConfig.Number setting;

		Slider(Component name, VesuvioConfig.Number setting) {
			super(0, 0, 200, 20, Component.empty(), position(setting, setting.get()));
			this.name = name;
			this.setting = setting;
			this.updateMessage();
		}

		private static double position(VesuvioConfig.Number s, int v) {
			return s.max == s.min ? 0.0 : (v - s.min) / (double) (s.max - s.min);
		}

		private int current() {
			return (int) Math.round(this.setting.min + this.value * (this.setting.max - this.setting.min));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Options.genericValueLabel(this.name, this.current()));
		}

		@Override
		protected void applyValue() {
			int v = this.current();
			if (v != this.setting.get()) {
				this.setting.set(v);
				VesuvioChanges.changed(this.setting);
			}
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			// Snap the handle to the whole number it stands for.
			this.value = position(this.setting, this.setting.get());
			this.updateMessage();
			VesuvioConfig.save();
		}
	}
}

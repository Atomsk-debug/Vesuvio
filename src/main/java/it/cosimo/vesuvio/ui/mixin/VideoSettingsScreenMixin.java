package it.cosimo.vesuvio.ui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.ui.VesuvioSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/** Puts an "Vesuvio..." button at the top of Video Settings' option list (before the Display header). */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	protected VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions()V", at = @At("HEAD"))
	private void vesuvio$addButton(CallbackInfo ci) {
		if (this.list == null) {
			return;
		}
		Screen videoSettings = this;
		this.list.addBig(Button.builder(Component.translatable("vesuvio.screen.open"),
				b -> this.minecraft.gui.setScreen(new VesuvioSettingsScreen(videoSettings))).build());
	}
}

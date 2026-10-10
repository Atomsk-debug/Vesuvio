package it.cosimo.vesuvio.ui.mixin;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.ui.VesuvioKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/**
 * Adds Vesuvio's key to the list of key mappings vanilla loads from and saves to options.txt and shows
 * in Controls > Key Binds. Options.load runs at the end of Options' constructor, after the list is built.
 */
@Mixin(Options.class)
public abstract class OptionsKeyMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "load()V", at = @At("HEAD"))
	private void vesuvio$addKey(CallbackInfo ci) {
		for (KeyMapping k : this.keyMappings) {
			if (k == VesuvioKey.TOGGLE) {
				return;
			}
		}
		KeyMapping[] more = Arrays.copyOf(this.keyMappings, this.keyMappings.length + 1);
		more[more.length - 1] = VesuvioKey.TOGGLE;
		this.keyMappings = more;
	}
}

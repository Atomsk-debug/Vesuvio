package it.cosimo.vesuvio.ui.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.ui.VesuvioDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;

/**
 * Shows Vesuvio's F3 lines with the overlay in vanilla's built-in debug profiles (which only list
 * vanilla's entries). A custom profile keeps whatever the player chose for it.
 */
@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListMixin {
	@Shadow
	@Final
	private Map<Identifier, DebugScreenEntryStatus> allStatuses;

	@Inject(method = "resetToProfile(Lnet/minecraft/client/gui/components/debug/DebugScreenProfile;)V", at = @At("TAIL"))
	private void vesuvio$showWithF3(DebugScreenProfile profile, CallbackInfo ci) {
		this.allStatuses.putIfAbsent(VesuvioDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
	}
}

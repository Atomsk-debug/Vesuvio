package it.cosimo.vesuvio.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The master switch's key, F8 by default. It is a normal vanilla key mapping in its own "Vesuvio"
 * category, so it can be changed in Options > Controls > Key Binds and is saved in options.txt
 * ("key_key.vesuvio.toggle"); OptionsKeyMixin adds it to the list vanilla loads, saves and shows.
 */
public final class VesuvioKey {
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("vesuvio", "main"));
	public static final KeyMapping TOGGLE = new KeyMapping("key.vesuvio.toggle", InputConstants.KEY_F8, CATEGORY);

	private VesuvioKey() {
	}
}

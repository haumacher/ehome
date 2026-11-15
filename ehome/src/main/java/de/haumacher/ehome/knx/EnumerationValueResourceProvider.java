/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import com.top_logic.knowledge.gui.WrapperResourceProvider;
import com.top_logic.layout.Flavor;
import com.top_logic.layout.basic.ThemeImage;

/**
 * Presents an entry of an enumeration of an application program
 * (<code>ehome.operational:EnumerationValue</code>) with the icon its program gives it, from the icons
 * of the program ({@link ProgramResources#icon(Object)}, haumacher/ehome #156), as the picture's data;
 * an entry without one with the icon of its type.
 *
 * <p>
 * Registered for the type in <code>ehomeConf.config.xml</code>, so that the options of a choice of
 * a parameter and the chip of the chosen entry show the icon of each entry (TL #30316,
 * <code>projects/device-item.view.xml</code>).
 * </p>
 */
public class EnumerationValueResourceProvider extends WrapperResourceProvider {

	/**
	 * Creates a {@link EnumerationValueResourceProvider}.
	 */
	public EnumerationValueResourceProvider() {
		super(null);
	}

	@Override
	public ThemeImage getImage(Object object, Flavor flavor) {
		ThemeImage icon = ThemeImage.data(ProgramResources.icon(object));
		return icon != null ? icon : super.getImage(object, flavor);
	}

}

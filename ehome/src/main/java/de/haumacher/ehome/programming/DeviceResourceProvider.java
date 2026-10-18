/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import com.top_logic.knowledge.gui.WrapperResourceProvider;
import com.top_logic.model.TLObject;

/**
 * Presents a device (<code>ehome.physical:Device</code>) by its name and individual address, see
 * {@link GroupAddressConnections#deviceLabel(TLObject)}, e.g. "Tastsensor 3 (1.3.50)".
 *
 * <p>
 * Registered for the device type in <code>ehomeConf.config.xml</code>, so that a choice of devices
 * (the options of a select field) and every other plain display of a device names its individual
 * address rather than the device number alone.
 * </p>
 */
public class DeviceResourceProvider extends WrapperResourceProvider {

	/**
	 * Creates a {@link DeviceResourceProvider}.
	 */
	public DeviceResourceProvider() {
		super(null);
	}

	@Override
	public String getLabel(Object object) {
		if (object instanceof TLObject device && device.tValid()) {
			return GroupAddressConnections.deviceLabel(device);
		}
		return super.getLabel(object);
	}

}

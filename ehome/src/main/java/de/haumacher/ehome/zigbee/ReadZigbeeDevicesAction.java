/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLObject;

/**
 * {@link ViewAction} reading the Zigbee devices of the input project's controller into the project
 * (<code>GET /api/zigbee/export</code>, all pages), see {@link ZigbeeController#read(TLObject)}.
 *
 * <p>
 * The result is the sentence saying what was imported, followed by the notes of the import.
 * </p>
 */
public class ReadZigbeeDevicesAction implements ViewAction {

	/**
	 * Configuration of {@link ReadZigbeeDevicesAction}.
	 */
	@TagName("read-zigbee-devices")
	public interface Config extends PolymorphicConfiguration<ReadZigbeeDevicesAction> {

		@Override
		@ClassDefault(ReadZigbeeDevicesAction.class)
		Class<? extends ReadZigbeeDevicesAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ReadZigbeeDevicesAction} from configuration.
	 */
	@CalledByReflection
	public ReadZigbeeDevicesAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		return ZigbeeController.read((TLObject) input);
	}

}

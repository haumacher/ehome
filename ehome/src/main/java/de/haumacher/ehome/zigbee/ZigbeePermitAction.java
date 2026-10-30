/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.config.annotation.defaults.IntDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;

/**
 * {@link ViewAction} opening the Zigbee network of a controller for pairing
 * (<code>POST /api/zigbee/permit</code>), or closing it, see {@link ZigbeeController#permit(TLObject, int)}.
 *
 * <p>
 * The input is a controller (<code>ehome.runtime:Controller</code>) or a project, whose controller is
 * meant; the result is the sentence saying what the controller did.
 * </p>
 */
public class ZigbeePermitAction implements ViewAction {

	/**
	 * Configuration of {@link ZigbeePermitAction}.
	 */
	@TagName("zigbee-permit")
	public interface Config extends PolymorphicConfiguration<ZigbeePermitAction>, WithResultActions {

		@Override
		@ClassDefault(ZigbeePermitAction.class)
		Class<? extends ZigbeePermitAction> getImplementationClass();

		/**
		 * The time in seconds the network is opened for (1..254); 0 closes it.
		 */
		@Name("seconds")
		@IntDefault(ZigbeeController.PERMIT_SECONDS)
		int getSeconds();
	}

	private final int _seconds;

	/**
	 * Creates a {@link ZigbeePermitAction} from configuration.
	 */
	@CalledByReflection
	public ZigbeePermitAction(InstantiationContext context, Config config) {
		_seconds = config.getSeconds();
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		return ZigbeeController.permit((TLObject) input, _seconds);
	}

}

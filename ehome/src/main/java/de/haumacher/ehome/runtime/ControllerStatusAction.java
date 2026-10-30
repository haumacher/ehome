/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

/**
 * {@link ViewAction} reading what a controller runs (<code>GET /api/status</code>).
 *
 * <p>
 * For a project (<code>ehome.project:Project</code>) the result is the comparison of what its
 * controller runs with the project's last deployment ({@link ControllerStatus#drift(TLObject)}), for
 * a controller (<code>ehome.runtime:Controller</code>) what it runs; a text either way.
 * </p>
 */
public class ControllerStatusAction implements ViewAction {

	/**
	 * Configuration of {@link ControllerStatusAction}.
	 */
	@TagName("controller-status")
	public interface Config extends PolymorphicConfiguration<ControllerStatusAction>, WithResultActions {

		@Override
		@ClassDefault(ControllerStatusAction.class)
		Class<? extends ControllerStatusAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ControllerStatusAction} from configuration.
	 */
	@CalledByReflection
	public ControllerStatusAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject obj = (TLObject) input;
		if (TLModelUtil.qualifiedName(obj.tType()).equals("ehome.runtime:Controller")) {
			return ControllerStatus.describe(Deployer.client(obj));
		}
		return ControllerStatus.drift(obj);
	}

}

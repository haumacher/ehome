/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.time.Instant;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLObject;

/**
 * {@link ViewAction} deploying the runtime image of the input project
 * (<code>ehome.project:Project</code>) to its controller, see {@link Deployer}.
 *
 * <p>
 * The result is the recorded deployment (<code>ehome.runtime:Deployment</code>), accepted or not:
 * a refusal is an outcome to show, not a failure of the command.
 * </p>
 */
public class DeployRuntimeImageAction implements ViewAction {

	/**
	 * Configuration of {@link DeployRuntimeImageAction}.
	 */
	@TagName("deploy-runtime-image")
	public interface Config extends PolymorphicConfiguration<DeployRuntimeImageAction> {

		@Override
		@ClassDefault(DeployRuntimeImageAction.class)
		Class<? extends DeployRuntimeImageAction> getImplementationClass();
	}

	/**
	 * Creates a {@link DeployRuntimeImageAction} from configuration.
	 */
	@CalledByReflection
	public DeployRuntimeImageAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		return Deployer.deploy((TLObject) input, Instant.now());
	}

}

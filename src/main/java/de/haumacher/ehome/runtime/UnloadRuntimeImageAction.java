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
 * {@link ViewAction} unloading the runtime image the controller of the input project
 * (<code>ehome.project:Project</code>) runs (<code>DELETE /api/image</code>), see
 * {@link Deployer#unload(TLObject, Instant)}.
 *
 * <p>
 * The result is the recorded unload (an <code>ehome.runtime:Deployment</code> marked as an unload),
 * done or not: a refusal is an outcome to show, not a failure of the command.
 * </p>
 */
public class UnloadRuntimeImageAction implements ViewAction {

	/**
	 * Configuration of {@link UnloadRuntimeImageAction}.
	 */
	@TagName("unload-runtime-image")
	public interface Config extends PolymorphicConfiguration<UnloadRuntimeImageAction> {

		@Override
		@ClassDefault(UnloadRuntimeImageAction.class)
		Class<? extends UnloadRuntimeImageAction> getImplementationClass();
	}

	/**
	 * Creates a {@link UnloadRuntimeImageAction} from configuration.
	 */
	@CalledByReflection
	public UnloadRuntimeImageAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		return Deployer.unload((TLObject) input, Instant.now());
	}

}

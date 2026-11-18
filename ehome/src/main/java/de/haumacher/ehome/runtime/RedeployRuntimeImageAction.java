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
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

/**
 * {@link ViewAction} deploying the image of the input deployment (<code>ehome.runtime:Deployment</code>)
 * again to the controller of its project, compiled from the project as of that deployment
 * (haumacher/ehome #192, {@link Deployer#redeploy(TLObject, TLObject, Instant)}).
 *
 * <p>
 * The result is the recorded deployment, accepted or not: a refusal is an outcome to show, not a
 * failure of the command.
 * </p>
 */
public class RedeployRuntimeImageAction implements ViewAction {

	/**
	 * Configuration of {@link RedeployRuntimeImageAction}.
	 */
	@TagName("redeploy-runtime-image")
	public interface Config extends PolymorphicConfiguration<RedeployRuntimeImageAction>, WithResultActions {

		@Override
		@ClassDefault(RedeployRuntimeImageAction.class)
		Class<? extends RedeployRuntimeImageAction> getImplementationClass();
	}

	/**
	 * Creates a {@link RedeployRuntimeImageAction} from configuration.
	 */
	@CalledByReflection
	public RedeployRuntimeImageAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject deployment = (TLObject) input;
		if (!(deployment.tContainer() instanceof TLObject project)) {
			throw new TopLogicException(I18NConstants.ERROR_REDEPLOY_NO_DEPLOYMENT);
		}
		return Deployer.redeploy(project, deployment, Instant.now());
	}

}

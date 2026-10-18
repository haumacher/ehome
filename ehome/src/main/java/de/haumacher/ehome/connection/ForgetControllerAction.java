/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * {@link ViewAction} forgetting the input controller (<code>ehome.runtime:Controller</code>,
 * haumacher/ehome #54): deletes it ({@link Controllers#forget(TLObject)}) and closes its connection,
 * which is refused from now on. A later enrollment of the same device is a new controller.
 *
 * <p>
 * The result is the sentence saying so.
 * </p>
 */
public class ForgetControllerAction implements ViewAction {

	/**
	 * Configuration of {@link ForgetControllerAction}.
	 */
	@TagName("forget-controller")
	public interface Config extends PolymorphicConfiguration<ForgetControllerAction> {

		@Override
		@ClassDefault(ForgetControllerAction.class)
		Class<? extends ForgetControllerAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ForgetControllerAction} from configuration.
	 */
	@CalledByReflection
	public ForgetControllerAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		if (!(input instanceof TLObject controller) || !controller.tValid()) {
			return null;
		}
		String name = String.valueOf(controller.tValueByName("name"));
		Controllers.forget(controller);
		return Resources.getInstance().getString(I18NConstants.FORGOTTEN__CONTROLLER.fill(name));
	}

}

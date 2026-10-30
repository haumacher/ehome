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
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * {@link ViewAction} creating the installation key of the input project (homecontrol issue #35,
 * {@link InstallationKeys#create(TLObject)}), which has none: a new Ed25519 key pair, offered to the
 * project's connected controllers at once and to the others at their next connection.
 *
 * <p>
 * The result is the sentence naming the fingerprint of the new key, which the owner compares with
 * the one the controller's Settings page shows before accepting it.
 * </p>
 */
public class CreateInstallationKeyAction implements ViewAction {

	/**
	 * Configuration of {@link CreateInstallationKeyAction}.
	 */
	@TagName("create-installation-key")
	public interface Config extends PolymorphicConfiguration<CreateInstallationKeyAction>, WithResultActions {

		@Override
		@ClassDefault(CreateInstallationKeyAction.class)
		Class<? extends CreateInstallationKeyAction> getImplementationClass();
	}

	/**
	 * Creates a {@link CreateInstallationKeyAction} from configuration.
	 */
	@CalledByReflection
	public CreateInstallationKeyAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		if (!(input instanceof TLObject project) || !project.tValid()) {
			return null;
		}
		Signatures.Keys keys = InstallationKeys.create(project);
		return Resources.getInstance().getString(I18NConstants.KEY_CREATED__FINGERPRINT.fill(keys.fingerprint()));
	}

}

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
 * {@link ViewAction} renewing the installation key of the input project (homecontrol issue #35,
 * {@link InstallationKeys#renew(TLObject)}): a new Ed25519 key pair is offered to the controllers, and
 * the old key stays the signing key until they accept the new one.
 *
 * <p>
 * The result is the sentence naming the fingerprint of the new key, which the owner compares with
 * the one the controller's Settings page shows before accepting it.
 * </p>
 */
public class RenewInstallationKeyAction implements ViewAction {

	/**
	 * Configuration of {@link RenewInstallationKeyAction}.
	 */
	@TagName("renew-installation-key")
	public interface Config extends PolymorphicConfiguration<RenewInstallationKeyAction>, WithResultActions {

		@Override
		@ClassDefault(RenewInstallationKeyAction.class)
		Class<? extends RenewInstallationKeyAction> getImplementationClass();
	}

	/**
	 * Creates a {@link RenewInstallationKeyAction} from configuration.
	 */
	@CalledByReflection
	public RenewInstallationKeyAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		if (!(input instanceof TLObject project) || !project.tValid()) {
			return null;
		}
		Signatures.Keys keys = InstallationKeys.renew(project);
		return Resources.getInstance().getString(I18NConstants.KEY_RENEWED__FINGERPRINT.fill(keys.fingerprint()));
	}

}

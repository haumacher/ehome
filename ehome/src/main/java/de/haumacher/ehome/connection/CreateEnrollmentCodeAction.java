/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.text.DateFormat;
import java.time.Instant;
import java.util.Date;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;

/**
 * {@link ViewAction} creating an enrollment code for the input project (haumacher/ehome #54,
 * {@link Enrollment#createCode(TLObject, com.top_logic.knowledge.wrap.person.Person, Instant)}),
 * replacing an open one.
 *
 * <p>
 * The result is the sentence naming the code, the time it expires and the minutes it stays valid,
 * for a dialog (the only place the code is shown besides the project's hidden attribute).
 * </p>
 */
public class CreateEnrollmentCodeAction implements ViewAction {

	/**
	 * Configuration of {@link CreateEnrollmentCodeAction}.
	 */
	@TagName("create-enrollment-code")
	public interface Config extends PolymorphicConfiguration<CreateEnrollmentCodeAction> {

		@Override
		@ClassDefault(CreateEnrollmentCodeAction.class)
		Class<? extends CreateEnrollmentCodeAction> getImplementationClass();
	}

	/**
	 * Creates a {@link CreateEnrollmentCodeAction} from configuration.
	 */
	@CalledByReflection
	public CreateEnrollmentCodeAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		if (!(input instanceof TLObject project) || !project.tValid()) {
			return null;
		}
		Instant now = Instant.now();
		String code = Enrollment.createCode(project, TLContext.currentUser(), now);
		Resources resources = Resources.getInstance();
		String until = DateFormat.getTimeInstance(DateFormat.SHORT, resources.getLocale())
			.format(Date.from(now.plus(Enrollment.VALIDITY)));
		return resources.getString(I18NConstants.ENROLLMENT_CODE__CODE_UNTIL_MINUTES.fill(code, until,
			Long.valueOf(Enrollment.VALIDITY.toMinutes())));
	}

}

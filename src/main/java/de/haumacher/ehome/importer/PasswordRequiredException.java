/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import com.top_logic.basic.util.ResKey;
import com.top_logic.util.error.TopLogicException;

/**
 * Refusal of a project file whose project is protected by a password, imported without one.
 *
 * <p>
 * Distinguished from other refusals so that the user interface can ask for the password and import
 * the file again.
 * </p>
 */
public class PasswordRequiredException extends TopLogicException {

	/**
	 * Creates a {@link PasswordRequiredException}.
	 *
	 * @param message
	 *        The reason, naming the protected project.
	 */
	public PasswordRequiredException(ResKey message) {
		super(message);
	}

}

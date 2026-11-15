/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.IOException;

import com.top_logic.basic.exception.I18NFailure;
import com.top_logic.basic.util.ResKey;

/**
 * A program file of a product or project file that cannot be read: its XML is broken (haumacher/ehome
 * #171).
 *
 * <p>
 * {@link #getErrorKey()} is the reason for a person, in the language of the reader; the parser's own
 * message is data inside it. {@link #getMessage()} is the parser's message alone, for the log.
 * </p>
 */
public class ProgramFileException extends IOException implements I18NFailure {

	/**
	 * Creates a {@link ProgramFileException}.
	 *
	 * @param cause
	 *        The error of the XML parser.
	 */
	public ProgramFileException(Exception cause) {
		super(detail(cause), cause);
	}

	@Override
	public ResKey getErrorKey() {
		return I18NConstants.REASON_PROGRAM_FILE_UNREADABLE__DETAIL.fill(getMessage());
	}

	/**
	 * The message of the given exception, its class name if it has none.
	 */
	static String detail(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
	}

}

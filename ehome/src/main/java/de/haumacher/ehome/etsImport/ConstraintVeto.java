/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.exception.I18NRuntimeException;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

/**
 * The problems of a commit the model constraints vetoed.
 *
 * <p>
 * {@link com.top_logic.util.model.ModelService#checkCommit} refuses with the message "values fail
 * their checks" alone and hangs the problems it found (at most ten) on it as its causes, the last
 * found first. A job that fails with it shows only the message, so the importer reads the causes
 * and names them itself (TL #30201, doc/tl-todo.md).
 * </p>
 */
public final class ConstraintVeto {

	private ConstraintVeto() {
		// Static utility.
	}

	/**
	 * The texts of the problems the given veto names, in the user's language and in the order they
	 * were found; <code>null</code> if the exception is not a veto of the model constraints.
	 */
	public static List<String> problems(TopLogicException ex) {
		if (!com.top_logic.util.model.I18NConstants.CONSTRAINTS_VIOLATED.equals(ex.getErrorKey())) {
			return null;
		}
		List<String> result = new ArrayList<>();
		Resources resources = Resources.getInstance();
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof I18NRuntimeException problem && problem.getErrorKey() != null) {
				result.add(0, resources.getString(problem.getErrorKey()));
			} else if (cause.getMessage() != null) {
				result.add(0, cause.getMessage());
			}
		}
		return result;
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;

/**
 * What the commit messages of E-Home's writing tools share (TL #30276): the texts naming the objects
 * a call changes.
 *
 * <p>
 * A tool's commit message method (<code>@ToolMethod(commitMessage = ...)</code>) is asked right before
 * the call runs, with the call's arguments, which the tool has not checked yet: an argument may be
 * <code>null</code>, of another type or deleted. A text of this class answers for every such value
 * and never fails.
 * </p>
 */
public final class CommitMessages {

	private CommitMessages() {
		// Static helpers only.
	}

	/**
	 * The label of an object as the application shows it, its identifier when it has no label, and
	 * "?" for none.
	 */
	public static String label(Object obj) {
		if (obj == null) {
			return "?";
		}
		if (obj instanceof TLObject object && !object.tValid()) {
			return "?";
		}
		try {
			String label = MetaLabelProvider.INSTANCE.getLabel(obj);
			if (label != null && !label.isBlank()) {
				return label;
			}
		} catch (RuntimeException ex) {
			// The identifier instead.
		}
		return obj instanceof TLObject object ? object.tId().asString() : String.valueOf(obj);
	}

	/**
	 * The {@link #label(Object) labels} of the given objects, separated by commas.
	 */
	public static String labels(Collection<?> objects) {
		if (objects == null || objects.isEmpty()) {
			return "-";
		}
		List<String> result = new ArrayList<>();
		for (Object obj : objects) {
			result.add(label(obj));
		}
		return String.join(", ", result);
	}

	/**
	 * The given text, trimmed, or "?" for none.
	 */
	public static String text(String text) {
		return text == null || text.isBlank() ? "?" : text.trim();
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.ai.core.agent.ToolContext;

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

	/**
	 * What the answer of a tool that changed something says about the commit, as it is where the call
	 * runs: in the chat (a run with a person present) the calls of a turn share its transaction,
	 * committed when the turn ends; over the MCP endpoint (a run without one,
	 * {@link ToolContext#forBackgroundRun(com.top_logic.knowledge.wrap.person.Person)} - E-Home starts
	 * no scheduled agents) the engine commits each call when it returns. An external agent took the
	 * chat's sentence for the endpoint and believed it had to end its turn before a check could see its
	 * change (production report of 2026-11-15).
	 */
	public static String saved(ToolContext context) {
		return context != null && !context.attended()
			? "The change is saved: it was committed when this call returned, and every later call sees it."
			: "The change is saved when this turn ends.";
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.tools.refusal.ApplicationRefusals;
import com.top_logic.tl.ai.core.tools.refusal.messages.ApplicationRefusal;
import com.top_logic.tl.ai.core.tools.refusal.messages.Reason;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.runtime.Json;

/**
 * What E-Home's tools for the assistant share: identifiers and labels in their answers, the answers
 * themselves, the refusals of the application, and the check of the right to change a project.
 *
 * <p>
 * The texts of the tools are for the model and English. A refusal of the application (a veto of
 * the operation the views run) is the application's own text in the language of the run's user, in
 * an {@link ApplicationRefusal}, as the shipped object tools relay it.
 * </p>
 */
final class ToolSupport {

	/** The qualified name of a project. */
	static final String PROJECT = "ehome.project:Project";

	/** What a tool description says about a refusal of the application. */
	static final String REFUSALS = ApplicationRefusals.DESCRIPTION;

	/** What an answer that changed something says about the commit. */
	static final String SAVED = "The change is saved when this turn ends.";

	private ToolSupport() {
		// Static helpers only.
	}

	/**
	 * The identifier of an object as the tools take it again: <code>Type:id</code>.
	 */
	static String id(TLObject obj) {
		return obj == null ? null : obj.tId().asString();
	}

	/**
	 * The label of an object as the application shows it.
	 */
	static String label(Object obj) {
		return obj == null ? null : MetaLabelProvider.INSTANCE.getLabel(obj);
	}

	/**
	 * An object in an answer: its identifier and its label.
	 */
	static Map<String, Object> ref(TLObject obj) {
		return ref(obj, label(obj));
	}

	/**
	 * An object in an answer: its identifier and the given name.
	 */
	static Map<String, Object> ref(TLObject obj, String name) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id(obj));
		result.put("name", name);
		return result;
	}

	/**
	 * A part of an object in an answer that is no object of its own (a parameter, a page): the ID
	 * the tools take for it and its name.
	 */
	static Map<String, Object> named(String id, String name) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (id != null) {
			result.put("id", id);
		}
		result.put("name", name);
		return result;
	}

	/**
	 * The project the given object belongs to (the object itself for a project), <code>null</code>
	 * for an object of no project.
	 */
	static TLObject project(TLObject obj) {
		return ExtIds.ancestor(obj, PROJECT);
	}

	/**
	 * An answer: a sentence for the model followed by the data as JSON.
	 */
	static ToolResult answer(String sentence, Object data) {
		return ToolResult.of(sentence + "\n" + Json.write(data));
	}

	/**
	 * A failed call with a sentence of the tool for the model.
	 */
	static ToolResult failure(String sentence) {
		return ToolResult.failure(sentence);
	}

	/**
	 * A refusal of the application: its reasons in the language of the run's user.
	 *
	 * @param object
	 *        The object the refused operation was about, <code>null</code> for none.
	 */
	static ToolResult refused(TLObject object, List<ResKey> reasons) {
		List<Reason> texts = new ArrayList<>();
		for (ResKey reason : reasons) {
			texts.add(ApplicationRefusals.reason(reason));
		}
		ApplicationRefusal refusal = ApplicationRefusals.refusal().setReasons(texts);
		if (object != null) {
			refusal.setObject(label(object) + " [id:" + id(object) + "]");
		}
		return ToolResult.failure(ApplicationRefusals.toJson(refusal));
	}

	/**
	 * A refusal of the application with one reason.
	 */
	static ToolResult refused(TLObject object, ResKey reason) {
		return refused(object, List.of(reason));
	}

	/**
	 * Runs an operation that changes the project of the given object, as the account of the run.
	 *
	 * <p>
	 * E-Home's Java code is not access checked: the views check <code>Write</code> before they call
	 * it, and so does this before the operation runs. A call without a user account, in a system
	 * context where no right is checked and no author recorded, is refused like the object tools of
	 * the AI modules refuse it.
	 * </p>
	 *
	 * <p>
	 * A {@link TopLogicException} of the operation is its refusal and is answered as such: the
	 * operations called refuse before they write (their vetoes) or restore what they wrote. Every
	 * other failure escapes, and the turn is rolled back.
	 * </p>
	 *
	 * @param context
	 *        The run's context with the rights of its account.
	 * @param obj
	 *        An object of the project to change.
	 * @param operation
	 *        The change.
	 */
	static ToolResult changing(ToolContext context, TLObject obj, Supplier<ToolResult> operation) {
		ToolResult denied = writeDenied(context, obj);
		if (denied != null) {
			return denied;
		}
		try {
			return operation.get();
		} catch (TopLogicException ex) {
			return refused(null, ex.getErrorKey());
		}
	}

	/**
	 * Why the run may not change the project of the given object, <code>null</code> if it may.
	 */
	static ToolResult writeDenied(ToolContext context, TLObject obj) {
		if (ThreadContext.isSecurityBypassed() || TLContext.currentUser() == null) {
			return failure("This call runs without a user account, in a system context where the application"
				+ " checks no rights and records no author. Data is only changed in the name of the user the"
				+ " run acts for. Nothing was changed.");
		}
		TLObject project = project(obj);
		if (project == null) {
			return failure("'" + id(obj) + "' belongs to no project. Nothing was changed.");
		}
		if (!context.modelSecurity().canWrite(project)) {
			return failure("Permission denied: your account may not change the project '" + label(project)
				+ "'. Nothing was changed. Tell the user; do not try another way to change it.");
		}
		return null;
	}

}

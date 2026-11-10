/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.Map;
import java.util.function.Supplier;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.tools.refusal.ApplicationRefusals;
import com.top_logic.tl.ai.core.tools.refusal.messages.ApplicationRefusal;
import com.top_logic.tl.ai.core.tools.refusal.messages.Reason;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Json;

/**
 * What the assistant's tools for E-Home's logic share (haumacher/ehome #128): identifiers and labels
 * in their answers, the answers themselves, the refusals of the application, and the check of the
 * right to change a project.
 *
 * <p>
 * The texts of the tools are for the model and English. A refusal of the application (a veto of the
 * operation the views run, a field the rule editor refuses) is the application's own text in the
 * language of the run's user, relayed in an {@link ApplicationRefusal} as the shipped object tools
 * relay it.
 * </p>
 */
final class LogicSupport {

	/** The qualified name of a project. */
	static final String PROJECT = "ehome.project:Project";

	/** What a tool description says about a refusal of the application. */
	static final String REFUSALS = ApplicationRefusals.DESCRIPTION;

	/** What an answer that changed something says about the commit. */
	static final String SAVED = "The change is saved when this turn ends.";

	private LogicSupport() {
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
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id(obj));
		result.put("name", label(obj));
		return result;
	}

	/**
	 * The text of a message of the application in the language of the run's user.
	 */
	static String text(ResKey key) {
		return key == null ? null : Resources.getInstance().getString(key);
	}

	/**
	 * The project the given object belongs to (the object itself for a project), <code>null</code>
	 * for an object of no project.
	 */
	static TLObject project(TLObject obj) {
		return obj == null ? null : ExtIds.ancestor(obj, PROJECT);
	}

	/**
	 * An answer: a sentence for the model followed by the data as JSON.
	 */
	static ToolResult answer(String sentence, Object data) {
		return ToolResult.of(data == null ? sentence : sentence + "\n" + Json.write(data));
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
	 * @param reasons
	 *        The reasons, each optionally with the attribute or place it is about.
	 */
	static ToolResult refused(TLObject object, List<Reason> reasons) {
		ApplicationRefusal refusal = ApplicationRefusals.refusal().setReasons(reasons);
		if (object != null) {
			refusal.setObject(label(object) + " [id:" + id(object) + "]");
		}
		return ToolResult.failure(ApplicationRefusals.toJson(refusal));
	}

	/**
	 * A refusal of the application with one reason.
	 */
	static ToolResult refused(TLObject object, ResKey reason) {
		List<Reason> reasons = new ArrayList<>();
		reasons.add(ApplicationRefusals.reason(reason));
		return refused(object, reasons);
	}

	/**
	 * Why the run may not change the project of the given object, <code>null</code> if it may.
	 *
	 * <p>
	 * E-Home's Java code is not access checked: the views check <code>Write</code> on the project
	 * before they call it, and so do the tools. A call without a user account, in a system context
	 * where no right is checked and no author recorded, is refused as the object tools of the AI
	 * modules refuse it.
	 * </p>
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

	/**
	 * A sentence naming an object that is not of the kind a parameter takes.
	 */
	static ToolResult wrongKind(String parameter, TLObject obj, String kind) {
		return failure("'" + parameter + "' must be " + kind + "; '" + id(obj) + "' (" + label(obj)
			+ ") is not. Nothing was done.");
	}

	/**
	 * Whether the object is of the given type of E-Home (<code>module:Type</code>) or a subtype.
	 */
	static boolean isA(TLObject obj, String type) {
		return obj != null && ExtIds.isA(obj, type);
	}

	/**
	 * What a change did, or would do in a dry run.
	 *
	 * @param sentence
	 *        What happened, for the model.
	 * @param data
	 *        The details: no objects created by the change (a dry run's are gone afterwards), only
	 *        their names and the ids of existing objects.
	 * @param errors
	 *        Why the application refuses the change; empty when it does not.
	 */
	record Outcome(String sentence, Map<String, Object> data, List<Reason> errors) {

		/** A change the application accepts. */
		static Outcome done(String sentence, Map<String, Object> data) {
			return new Outcome(sentence, data, List.of());
		}

		/** A change the application refuses for the given reasons. */
		static Outcome refused(List<Reason> errors) {
			return new Outcome(null, null, errors);
		}
	}

	/**
	 * Runs a change of the project as the views run it, after the check of the right to change the
	 * project: first in a {@link Sandbox}, so that a change the application refuses (a veto, a field
	 * the model's constraints refuse) leaves nothing behind; then, unless it is a dry run, for real in
	 * the turn's transaction.
	 *
	 * <p>
	 * A {@link TopLogicException} of the change is its refusal. In the real run, a refusal the dry
	 * run did not see (the data changed in between) escapes, and the turn is rolled back.
	 * </p>
	 */
	static ToolResult perform(ToolContext context, TLObject project, boolean dryRun, Supplier<Outcome> change) {
		ToolResult denied = writeDenied(context, project);
		if (denied != null) {
			return denied;
		}
		Outcome probe;
		try {
			probe = Sandbox.run(context, change);
		} catch (TopLogicException ex) {
			return refused(project, ex.getErrorKey());
		}
		if (!probe.errors().isEmpty()) {
			return refused(project, probe.errors());
		}
		if (dryRun) {
			return answer("Dry run, nothing was changed: " + probe.sentence(), probe.data());
		}
		Outcome real;
		try {
			real = change.get();
		} catch (TopLogicException ex) {
			throw new IllegalStateException("The change passed its dry run but was refused: " + text(ex.getErrorKey()), ex);
		}
		if (!real.errors().isEmpty()) {
			throw new IllegalStateException("The change passed its dry run but was refused: " + real.errors());
		}
		return answer(real.sentence() + " " + SAVED, real.data());
	}

	/**
	 * The reasons of the field checks of the given problems that refuse a change: the errors; with
	 * <code>all</code> also what the controller would refuse.
	 */
	static List<Reason> reasons(List<AutomationChange.Problem> problems, boolean all) {
		List<Reason> result = new ArrayList<>();
		for (AutomationChange.Problem problem : problems) {
			if (problem.error() || all) {
				String where = problem.item() + (problem.part() != null ? " / " + problem.part() : "") + " / "
					+ problem.attribute();
				result.add(ApplicationRefusals.reason(problem.message()).setAttribute(where));
			}
		}
		return result;
	}

	/**
	 * The problems as the model reads them.
	 */
	static List<Object> json(List<AutomationChange.Problem> problems) {
		List<Object> result = new ArrayList<>();
		for (AutomationChange.Problem problem : problems) {
			result.add(problem.json());
		}
		return result;
	}

	/**
	 * Why the object is no part of the given project, <code>null</code> if it is.
	 */
	static ToolResult foreign(TLObject project, TLObject obj, String parameter) {
		if (obj == null || project(obj) != project) {
			return failure("'" + parameter + "' (" + id(obj) + ") is no part of the project '" + label(project)
				+ "'. Nothing was changed.");
		}
		return null;
	}

	/**
	 * A name given in no particular language as a text of every language of the application, as the
	 * import of an image stores a label.
	 */
	static ResKey allLanguages(String text) {
		Map<String, String> texts = new TreeMap<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			texts.put(locale.getLanguage(), text);
		}
		return FunctionProposal.text(texts);
	}

}

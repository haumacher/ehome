/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * {@link ViewAction} adding the functions {@link FunctionProposal} proposes for the input project
 * (<code>ehome.project:Project</code>) and the project lacks.
 *
 * <p>
 * The result is the text of a notification ({@link #summary(FunctionProposal.Run)}): how many
 * functions in use and how many unused ones were added, how many existing ones were updated
 * (haumacher/ehome #98), and the warnings about the wiring of the proposed functions.
 * </p>
 */
public class ProposeFunctionsAction implements ViewAction {

	/**
	 * Configuration of {@link ProposeFunctionsAction}.
	 */
	@TagName("propose-functions")
	public interface Config extends PolymorphicConfiguration<ProposeFunctionsAction>, WithResultActions {

		@Override
		@ClassDefault(ProposeFunctionsAction.class)
		Class<? extends ProposeFunctionsAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ProposeFunctionsAction} from configuration.
	 */
	@CalledByReflection
	public ProposeFunctionsAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	/**
	 * The sentences of the notification about the given run, in the user's language: how many
	 * functions in use were added, apart from how many unused ones (haumacher/ehome #135), how many
	 * existing ones were updated, then the warnings about the wiring of the functions
	 * (haumacher/ehome #134), one line each.
	 */
	public static String summary(FunctionProposal.Run run) {
		Resources resources = Resources.getInstance();
		List<String> sentences = new ArrayList<>();
		int inUse = run.addedInUse().size();
		int unused = run.addedUnused().size();
		if (inUse > 0) {
			sentences.add(resources.getString(I18NConstants.PROPOSAL_ADDED__COUNT.fill(Integer.valueOf(inUse))));
		}
		if (unused > 0) {
			sentences.add(resources.getString(I18NConstants.PROPOSAL_ADDED_UNUSED__COUNT.fill(Integer.valueOf(unused))));
		}
		if (!run.updated().isEmpty()) {
			sentences.add(
				resources.getString(I18NConstants.PROPOSAL_UPDATED__COUNT.fill(Integer.valueOf(run.updated().size()))));
		}
		if (sentences.isEmpty()) {
			sentences.add(resources.getString(I18NConstants.PROPOSAL_NOTHING_NEW));
		}
		StringBuilder result = new StringBuilder(String.join(" ", sentences));
		run.warnings().forEach((function, warnings) -> {
			String name = function.tValueByName("name") instanceof ResKey key ? resources.getString(key)
				: String.valueOf(function.tValueByName("identifier"));
			for (ResKey warning : warnings) {
				result.append('\n').append(resources.getString(
					I18NConstants.PROPOSAL_WARNING__FUNCTION_WARNING.fill(name, resources.getString(warning))));
			}
		});
		return result.toString();
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject project = (TLObject) input;
		FunctionProposal.Run run;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			run = FunctionProposal.run(project);
			tx.commit();
		}
		return summary(run);
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ActionArguments;
import com.top_logic.layout.view.command.ArgumentsViewAction;
import com.top_logic.layout.view.command.Continuation;
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
 * (haumacher/ehome #98), and the warnings about the wiring of the proposed functions; with the
 * option to overwrite (haumacher/ehome #177) how many existing functions were overwritten, each
 * rename ("old → new") and the functions the proposal no longer gives.
 * </p>
 *
 * <p>
 * The project is the default argument of the chain; the option to overwrite is the argument named
 * by {@link Config#getOverwrite()} (a Boolean), off where the chain has none.
 * </p>
 */
public class ProposeFunctionsAction extends ArgumentsViewAction {

	/**
	 * Configuration of {@link ProposeFunctionsAction}.
	 */
	@TagName("propose-functions")
	public interface Config extends PolymorphicConfiguration<ProposeFunctionsAction>, WithResultActions {

		@Override
		@ClassDefault(ProposeFunctionsAction.class)
		Class<? extends ProposeFunctionsAction> getImplementationClass();

		/**
		 * The name of the argument of the chain whether the existing functions that come from a
		 * proposal are overwritten (haumacher/ehome #177).
		 */
		@Name("overwrite")
		@StringDefault("overwrite")
		String getOverwrite();
	}

	private final String _overwrite;

	/**
	 * Creates a {@link ProposeFunctionsAction} from configuration.
	 */
	@CalledByReflection
	public ProposeFunctionsAction(InstantiationContext context, Config config) {
		_overwrite = config.getOverwrite();
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
		if (!run.overwritten().isEmpty()) {
			sentences.add(resources
				.getString(I18NConstants.PROPOSAL_OVERWRITTEN__COUNT.fill(Integer.valueOf(run.overwritten().size()))));
		}
		if (sentences.isEmpty()) {
			sentences.add(resources.getString(I18NConstants.PROPOSAL_NOTHING_NEW));
		}
		StringBuilder result = new StringBuilder(String.join(" ", sentences));
		for (FunctionProposal.Rename rename : run.renamed()) {
			result.append('\n').append(resources.getString(I18NConstants.PROPOSAL_RENAMED__BEFORE_AFTER
				.fill(rename.before() == null ? "-" : resources.getString(rename.before()),
					resources.getString(rename.after()))));
		}
		if (!run.stale().isEmpty()) {
			result.append('\n').append(
				resources.getString(I18NConstants.PROPOSAL_STALE__COUNT.fill(Integer.valueOf(run.stale().size()))));
			for (TLObject function : run.stale()) {
				result.append('\n').append(
					resources.getString(I18NConstants.PROPOSAL_STALE__FUNCTION.fill(name(function, resources))));
			}
		}
		run.warnings().forEach((function, warnings) -> {
			String name = name(function, resources);
			for (ResKey warning : warnings) {
				result.append('\n').append(resources.getString(
					I18NConstants.PROPOSAL_WARNING__FUNCTION_WARNING.fill(name, resources.getString(warning))));
			}
		});
		return result.toString();
	}

	private static String name(TLObject function, Resources resources) {
		return function.tValueByName("name") instanceof ResKey key ? resources.getString(key)
			: String.valueOf(function.tValueByName("identifier"));
	}

	/**
	 * The question asked before a run with the option to overwrite (haumacher/ehome #177), in the
	 * user's language: how many existing functions it overwrites ({@link FunctionProposal.Overwrite#differing()})
	 * and what that means.
	 */
	public static String confirmation(TLObject project) {
		Resources resources = Resources.getInstance();
		int count = FunctionProposal.overwrite(project).differing().size();
		return resources.getString(I18NConstants.PROPOSAL_OVERWRITE_CONFIRM__COUNT.fill(Integer.valueOf(count)))
			+ "\n" + resources.getString(I18NConstants.PROPOSAL_OVERWRITE_CONFIRM_DETAIL);
	}

	@Override
	public void execute(ReactContext context, ActionArguments arguments, Continuation continuation) {
		boolean overwrite = Boolean.TRUE.equals(arguments.has(_overwrite) ? arguments.get(_overwrite) : null);
		FunctionProposal.Run run = apply((TLObject) arguments.value(), overwrite);
		continuation.resume(summary(run));
	}

	/**
	 * Adds the functions {@link FunctionProposal} proposes for the given project, in a transaction of
	 * its own whose commit message names the project and counts what was added and updated
	 * (haumacher/ehome #143).
	 */
	public static FunctionProposal.Run apply(TLObject project) {
		return apply(project, false);
	}

	/**
	 * Adds the functions {@link FunctionProposal} proposes for the given project, with the option to
	 * overwrite the existing functions that come from a proposal (haumacher/ehome #177,
	 * {@link FunctionProposal#run(TLObject, boolean)}), in a transaction of its own whose commit
	 * message names the project and counts what was added and updated (the overwritten functions
	 * among the updated ones).
	 */
	public static FunctionProposal.Run apply(TLObject project, boolean overwrite) {
		String label = MetaLabelProvider.INSTANCE.getLabel(project);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase()
			.beginTransaction(I18NConstants.COMMIT_FUNCTIONS_PROPOSED__PROJECT_ADDED_UNUSED_UPDATED.fill(label,
				Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(0)))) {
			FunctionProposal.Run run = FunctionProposal.run(project, overwrite);
			tx.setCommitMessage(I18NConstants.COMMIT_FUNCTIONS_PROPOSED__PROJECT_ADDED_UNUSED_UPDATED.fill(label,
				Integer.valueOf(run.addedInUse().size()), Integer.valueOf(run.addedUnused().size()),
				Integer.valueOf(run.updated().size() + run.overwritten().size())));
			tx.commit();
			return run;
		}
	}

}

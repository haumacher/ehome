/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
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

import de.haumacher.ehome.knx.GA;

/**
 * {@link ViewAction} giving the group addresses of the input project (<code>ehome.project:Project</code>)
 * the identifiers {@link GroupAddressIds#propose(TLObject, boolean)} proposes (haumacher/ehome #130), in
 * one transaction; the rules naming them follow ({@link IdRenames}). Without the option to overwrite,
 * the addresses without an identifier get one (since haumacher/ehome #178 every address is given one
 * when it is created, so these are rare); with it (haumacher/ehome #178, as "Propose functions" in
 * haumacher/ehome #177), every address gets what the proposal gives now.
 *
 * <p>
 * The result is the text of a notification ({@link #summary(List)}): how many addresses got a new
 * identifier, each change "old → new". The command asks first with
 * {@link #confirmation(TLObject, boolean)}, which counts and lists the changes.
 * </p>
 *
 * <p>
 * The project is the default argument of the chain; the option to overwrite is the argument named
 * by {@link Config#getOverwrite()} (a Boolean), off where the chain has none.
 * </p>
 */
public class ProposeIdentifiersAction extends ArgumentsViewAction {

	/** The changes a confirmation or a result lists at most. */
	private static final int LISTED = 40;

	/**
	 * Configuration of {@link ProposeIdentifiersAction}.
	 */
	@TagName("propose-identifiers")
	public interface Config extends PolymorphicConfiguration<ProposeIdentifiersAction>, WithResultActions {

		@Override
		@ClassDefault(ProposeIdentifiersAction.class)
		Class<? extends ProposeIdentifiersAction> getImplementationClass();

		/**
		 * The name of the argument of the chain whether the identifiers the addresses have are
		 * proposed anew (haumacher/ehome #178).
		 */
		@Name("overwrite")
		@StringDefault("overwrite")
		String getOverwrite();
	}

	/**
	 * A new identifier of a group address.
	 *
	 * @param address
	 *        The group address.
	 * @param before
	 *        The id of its datapoint before: its identifier, else its address in three levels.
	 * @param after
	 *        Its new identifier.
	 */
	public record Change(TLObject address, String before, String after) {
		// Value only.
	}

	private final String _overwrite;

	/**
	 * Creates a {@link ProposeIdentifiersAction} from configuration.
	 */
	@CalledByReflection
	public ProposeIdentifiersAction(InstantiationContext context, Config config) {
		_overwrite = config.getOverwrite();
	}

	@Override
	public void execute(ReactContext context, ActionArguments arguments, Continuation continuation) {
		boolean overwrite = Boolean.TRUE.equals(arguments.has(_overwrite) ? arguments.get(_overwrite) : null);
		continuation.resume(summary(run((TLObject) arguments.value(), overwrite)));
	}

	/**
	 * Gives the group addresses of the project without an identifier the proposed ones, in a
	 * transaction of its own.
	 *
	 * @return The number of addresses that got an identifier.
	 */
	public static int apply(TLObject project) {
		return run(project, false).size();
	}

	/**
	 * Gives the group addresses of the project the proposed identifiers, in a transaction of its
	 * own; with the option to overwrite, every address the one the proposal gives now.
	 *
	 * @return The changes, in the order of the addresses.
	 */
	public static List<Change> run(TLObject project, boolean overwrite) {
		List<Change> changes = changes(project, overwrite);
		if (changes.isEmpty()) {
			return changes;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(I18NConstants.COMMIT_IDENTIFIERS_PROPOSED__PROJECT_COUNT
			.fill(MetaLabelProvider.INSTANCE.getLabel(project), Integer.valueOf(changes.size())))) {
			for (Change change : changes) {
				change.address().tUpdateByName("identifier", change.after());
			}
			tx.commit();
		}
		return changes;
	}

	/**
	 * The changes the proposal makes to the group addresses of the project, without applying them.
	 */
	public static List<Change> changes(TLObject project, boolean overwrite) {
		List<Change> result = new ArrayList<>();
		for (Map.Entry<TLObject, String> entry : GroupAddressIds.propose(project, overwrite).entrySet()) {
			TLObject address = entry.getKey();
			result.add(new Change(address, GroupAddressIds.datapointId(address), entry.getValue()));
		}
		return result;
	}

	/**
	 * The text of the notification about the changes, in the user's language: how many addresses
	 * got a new identifier, then each change, "old → new" where the address had an identifier.
	 */
	public static String summary(List<Change> changes) {
		Resources resources = Resources.getInstance();
		if (changes.isEmpty()) {
			return resources.getString(I18NConstants.IDENTIFIERS_NOTHING_TO_PROPOSE);
		}
		StringBuilder text = new StringBuilder(
			resources.getString(I18NConstants.IDENTIFIERS_PROPOSED__COUNT.fill(Integer.valueOf(changes.size()))));
		list(text, changes, resources);
		return text.toString();
	}

	/**
	 * The question asked before the proposal is applied to the project: the addresses with the
	 * identifiers they would get (the first {@value #LISTED}); the statement that there is nothing to
	 * propose where there is none.
	 */
	public static String confirmation(TLObject project) {
		return confirmation(project, false);
	}

	/**
	 * The question asked before the proposal is applied to the project, with or without the option
	 * to overwrite: how many addresses get a new identifier and which (the first {@value #LISTED}:
	 * the address, its name, "old → new" where it had an identifier); the statement that there is
	 * nothing to change where there is nothing.
	 */
	public static String confirmation(TLObject project, boolean overwrite) {
		Resources resources = Resources.getInstance();
		List<Change> changes = changes(project, overwrite);
		if (changes.isEmpty()) {
			return resources.getString(
				overwrite ? I18NConstants.IDENTIFIERS_ALREADY_PROPOSED : I18NConstants.IDENTIFIERS_NOTHING_TO_PROPOSE);
		}
		StringBuilder text = new StringBuilder(resources.getString((overwrite
			? I18NConstants.IDENTIFIERS_OVERWRITE_CONFIRM__COUNT
			: I18NConstants.IDENTIFIERS_PROPOSAL_CONFIRM__COUNT).fill(Integer.valueOf(changes.size()))));
		text.append('\n');
		list(text, changes, resources);
		return text.toString();
	}

	private static void list(StringBuilder text, List<Change> changes, Resources resources) {
		int listed = 0;
		for (Change change : changes) {
			if (listed == LISTED) {
				text.append("\n• ").append(resources.getString(
					I18NConstants.IDENTIFIERS_PROPOSAL_MORE__COUNT.fill(Integer.valueOf(changes.size() - LISTED))));
				break;
			}
			listed++;
			TLObject address = change.address();
			String label = DatapointNames.label(address);
			// The address in the notation of the project.
			String where = address.tValueByName("address") instanceof GA ga ? ga.toString() : null;
			boolean renamed = !change.before().equals(GroupAddressIds.address(address));
			String head = (where == null ? "" : where) + (label == null ? "" : " " + label);
			text.append("\n• ").append(head.trim());
			if (renamed) {
				text.append(head.isBlank() ? "" : ": ").append(change.before());
			}
			text.append(" → ").append(change.after());
		}
	}

}

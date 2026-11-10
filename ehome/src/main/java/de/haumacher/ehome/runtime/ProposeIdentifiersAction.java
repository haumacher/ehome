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
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.GA;

/**
 * {@link ViewAction} giving the group addresses of the input project (<code>ehome.project:Project</code>)
 * that have no identifier the ones {@link GroupAddressIds#propose(TLObject)} proposes
 * (haumacher/ehome #130), in one transaction; the rules naming them follow
 * ({@link IdRenames}).
 *
 * <p>
 * The result is a sentence saying how many addresses got an identifier, for a notification. The
 * command asks first with {@link #confirmation(TLObject)}, which lists the proposal.
 * </p>
 */
public class ProposeIdentifiersAction implements ViewAction {

	/** The proposals the confirmation lists at most. */
	private static final int LISTED = 40;

	/**
	 * Configuration of {@link ProposeIdentifiersAction}.
	 */
	@TagName("propose-identifiers")
	public interface Config extends PolymorphicConfiguration<ProposeIdentifiersAction>, WithResultActions {

		@Override
		@ClassDefault(ProposeIdentifiersAction.class)
		Class<? extends ProposeIdentifiersAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ProposeIdentifiersAction} from configuration.
	 */
	@CalledByReflection
	public ProposeIdentifiersAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		int count = apply((TLObject) input);
		Resources resources = Resources.getInstance();
		return resources.getString(count == 0 ? I18NConstants.IDENTIFIERS_NOTHING_TO_PROPOSE
			: I18NConstants.IDENTIFIERS_PROPOSED__COUNT.fill(Integer.valueOf(count)));
	}

	/**
	 * Gives the group addresses of the project without an identifier the proposed ones, in a
	 * transaction of its own.
	 *
	 * @return The number of addresses that got an identifier.
	 */
	public static int apply(TLObject project) {
		Map<TLObject, String> proposal = GroupAddressIds.propose(project);
		if (proposal.isEmpty()) {
			return 0;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (Map.Entry<TLObject, String> entry : proposal.entrySet()) {
				entry.getKey().tUpdateByName("identifier", entry.getValue());
			}
			tx.commit();
		}
		return proposal.size();
	}

	/**
	 * The question asked before the proposal is applied to the project: the addresses with the
	 * identifiers they would get (the first {@value #LISTED}); the statement that there is nothing to
	 * propose where there is none.
	 */
	public static String confirmation(TLObject project) {
		Resources resources = Resources.getInstance();
		Map<TLObject, String> proposal = GroupAddressIds.propose(project);
		if (proposal.isEmpty()) {
			return resources.getString(I18NConstants.IDENTIFIERS_NOTHING_TO_PROPOSE);
		}
		List<String> lines = new ArrayList<>();
		for (Map.Entry<TLObject, String> entry : proposal.entrySet()) {
			if (lines.size() == LISTED) {
				lines.add(resources.getString(
					I18NConstants.IDENTIFIERS_PROPOSAL_MORE__COUNT.fill(Integer.valueOf(proposal.size() - LISTED))));
				break;
			}
			TLObject address = entry.getKey();
			String label = DatapointNames.label(address);
			// The address in the notation of the project.
			String where = address.tValueByName("address") instanceof GA ga ? ga.toString() : null;
			lines.add((where == null ? "" : where + " ") + (label == null ? "" : label + " ") + "→ " + entry.getValue());
		}
		StringBuilder text = new StringBuilder(
			resources.getString(I18NConstants.IDENTIFIERS_PROPOSAL_CONFIRM__COUNT.fill(Integer.valueOf(proposal.size()))));
		text.append('\n');
		for (String line : lines) {
			text.append("\n• ").append(line);
		}
		return text.toString();
	}

}

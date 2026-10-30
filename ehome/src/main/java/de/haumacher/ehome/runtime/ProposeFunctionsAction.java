/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.List;

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

/**
 * {@link ViewAction} adding the functions {@link FunctionProposal} proposes for the input project
 * (<code>ehome.project:Project</code>) and the project lacks.
 *
 * <p>
 * The result is a sentence saying how many functions were added, for a notification.
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

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject project = (TLObject) input;
		List<TLObject> added;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			added = FunctionProposal.apply(project);
			tx.commit();
		}
		return Resources.getInstance().getString(added.isEmpty() ? I18NConstants.PROPOSAL_NOTHING_NEW
			: I18NConstants.PROPOSAL_ADDED__COUNT.fill(Integer.valueOf(added.size())));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.model.TLObject;

/**
 * {@link ViewAction} deleting the input function (<code>ehome.runtime:Function</code>) and, for a
 * proposed one, remembering the proposal as dismissed (haumacher/ehome #49,
 * {@link FunctionProposal#dismiss(TLObject)}).
 *
 * <p>
 * The result is <code>null</code> (nothing selected any more). The command carries the model access
 * checks: the action changes the project in Java.
 * </p>
 */
public class DeleteFunctionAction implements ViewAction {

	/**
	 * Configuration of {@link DeleteFunctionAction}.
	 */
	@TagName("delete-function")
	public interface Config extends PolymorphicConfiguration<DeleteFunctionAction> {

		@Override
		@ClassDefault(DeleteFunctionAction.class)
		Class<? extends DeleteFunctionAction> getImplementationClass();
	}

	/**
	 * Creates a {@link DeleteFunctionAction} from configuration.
	 */
	@CalledByReflection
	public DeleteFunctionAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject function = (TLObject) input;
		if (function == null || !function.tValid()) {
			return null;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.dismiss(function);
			tx.commit();
		}
		return null;
	}

}

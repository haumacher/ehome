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
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

/**
 * {@link ViewAction} adding a new function (a light without datapoints) to the input project
 * (<code>ehome.project:Project</code>), for the user to edit; the result is the new function.
 */
public class CreateFunctionAction implements ViewAction {

	/**
	 * Configuration of {@link CreateFunctionAction}.
	 */
	@TagName("create-function")
	public interface Config extends PolymorphicConfiguration<CreateFunctionAction>, WithResultActions {

		@Override
		@ClassDefault(CreateFunctionAction.class)
		Class<? extends CreateFunctionAction> getImplementationClass();
	}

	/**
	 * Creates a {@link CreateFunctionAction} from configuration.
	 */
	@CalledByReflection
	public CreateFunctionAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject project = (TLObject) input;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject function = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Function"));
			function.tUpdateByName("name", FunctionProposal.text(I18NConstants.NEW_FUNCTION_NAME));
			function.tUpdateByName("kind", FunctionProposal.classifier("light"));
			List<TLObject> functions = FunctionProposal.functions(project);
			functions.add(function);
			project.tUpdateByName("functions", functions);
			tx.commit();
			return function;
		}
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;

/**
 * {@link ViewAction} reading the diagnostics of a virtual device (<code>ehome.runtime:BlockInstance</code>)
 * from the controller of its project ({@link BlockDiagnostics#read(TLObject)}, haumacher/ehome #78).
 *
 * <p>
 * The result is a dictionary with the instance (<code>block</code>) and the text
 * (<code>text</code>), so that a view shows the text only while the instance it was read for is
 * selected.
 * </p>
 */
public class BlockDiagnosticsAction implements ViewAction {

	/**
	 * Configuration of {@link BlockDiagnosticsAction}.
	 */
	@TagName("block-diagnostics")
	public interface Config extends PolymorphicConfiguration<BlockDiagnosticsAction>, WithResultActions {

		@Override
		@ClassDefault(BlockDiagnosticsAction.class)
		Class<? extends BlockDiagnosticsAction> getImplementationClass();
	}

	/**
	 * Creates a {@link BlockDiagnosticsAction} from configuration.
	 */
	@CalledByReflection
	public BlockDiagnosticsAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		TLObject instance = (TLObject) input;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("block", instance);
		result.put("text", BlockDiagnostics.read(instance));
		return result;
	}

}

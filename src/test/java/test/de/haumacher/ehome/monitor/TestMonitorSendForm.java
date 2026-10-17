/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.PropertyDescriptor;
import com.top_logic.layout.view.ViewLoader;
import com.top_logic.layout.view.element.FieldsElement;
import com.top_logic.layout.view.element.StackElement;
import com.top_logic.layout.view.element.ValueInputElement;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.view.OptionOrderSelectControlProvider;

/**
 * The send form of the group monitor (<code>projects/monitor.view.xml</code>, haumacher/ehome
 * #24) as configured: a choice whose options come from the target keeps their order (the days of
 * the week, the step sizes), and no input shares a row with the buttons, where the narrow column
 * of the form squeezed it.
 */
@SuppressWarnings("javadoc")
public class TestMonitorSendForm extends BasicTestCase {

	private static final String VIEW = "/WEB-INF/views/projects/monitor.view.xml";

	public void testChoicesKeepTheirOrder() throws Exception {
		List<ValueInputElement.Config> choices = new ArrayList<>();
		for (ConfigurationItem item : items(ViewLoader.loadConfig(VIEW))) {
			if (item instanceof ValueInputElement.Config input && input.getOptions() != null
				&& List.of("stepSize", "dayValue", "choiceValue").contains(input.getValue().getChannelName())) {
				choices.add(input);
			}
		}
		assertEquals(3, choices.size());
		for (ValueInputElement.Config choice : choices) {
			assertNotNull(choice.getValue().getChannelName(), choice.getInputControl());
			assertEquals(choice.getValue().getChannelName(), OptionOrderSelectControlProvider.class,
				choice.getInputControl().getImplementationClass());
		}
	}

	public void testNoInputInARow() throws Exception {
		int rows = 0;
		for (ConfigurationItem item : items(ViewLoader.loadConfig(VIEW))) {
			if (item instanceof StackElement.Config stack && "row".equals(stack.getDirection().getExternalName())) {
				rows++;
				for (ConfigurationItem child : stack.getChildren()) {
					assertFalse("An input in a row of the send form: " + child.location(),
						child instanceof FieldsElement.Config);
				}
			}
		}
		assertTrue(rows > 0);
	}

	/**
	 * The given configuration and all configurations nested in it.
	 */
	private static List<ConfigurationItem> items(ConfigurationItem root) {
		List<ConfigurationItem> result = new ArrayList<>();
		collect(root, result);
		return result;
	}

	private static void collect(Object value, List<ConfigurationItem> result) {
		if (value instanceof ConfigurationItem item) {
			result.add(item);
			for (PropertyDescriptor property : item.descriptor().getProperties()) {
				collect(item.value(property), result);
			}
		} else if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				collect(element, result);
			}
		} else if (value instanceof Map<?, ?> map) {
			for (Object element : map.values()) {
				collect(element, result);
			}
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestMonitorSendForm.class,
			ModelService.Module.INSTANCE, SearchBuilder.Module.INSTANCE));
	}

}

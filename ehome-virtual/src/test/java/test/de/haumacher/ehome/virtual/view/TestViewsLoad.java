/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.PropertyDescriptor;
import com.top_logic.layout.view.ViewElement;
import com.top_logic.layout.view.ViewLoader;
import com.top_logic.layout.view.element.TabBarElement;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.util.model.ModelService;

/**
 * Every view of the tooling loads and instantiates, the same-path overlays (the device tabs of the
 * app with the tab "Virtual device") merged onto the views of the app they extend.
 */
@SuppressWarnings("javadoc")
public class TestViewsLoad extends BasicTestCase {

	/**
	 * The views of the module, relative to the module directory (surefire runs there).
	 */
	private static final Path VIEWS = Path.of("src/main/webapp/WEB-INF/views");

	/**
	 * The view of the app the tooling extends by a same-path overlay.
	 */
	private static final String DEVICES = "projects/devices.view.xml";

	/**
	 * The id of the tab bar of a device in {@link #DEVICES}, which the overlay addresses.
	 */
	private static final String DEVICE_TABS = "device-tabs";

	public void testAllViews() throws IOException {
		List<String> problems = new ArrayList<>();
		int loaded = 0;
		try (Stream<Path> files = Files.walk(VIEWS)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".view.xml")).sorted()::iterator) {
				String view = VIEWS.relativize(file).toString().replace('\\', '/');
				try {
					ViewLoader.loadView(ViewLoader.fullPath(view));
					loaded++;
				} catch (Exception ex) {
					problems.add(view + ": " + causes(ex));
				}
			}
		}
		assertEquals("Views that do not load:\n" + String.join("\n", problems), 0, problems.size());
		assertTrue(loaded > 0);
	}

	public void testDeviceTabsOverlay() throws Exception {
		ViewElement.Config config = ViewLoader.loadConfig(ViewLoader.fullPath(DEVICES));
		TabBarElement.Config tabBar = tabBar(config);
		assertNotNull("No tab bar " + DEVICE_TABS + " in " + DEVICES, tabBar);
		List<String> ids = new ArrayList<>();
		for (TabBarElement.TabConfig tab : tabBar.getTabs()) {
			ids.add(tab.getId());
		}
		assertTrue("The app's tabs are kept: " + ids, ids.contains("parameters"));
		assertEquals("The tab of the tooling comes after the app's tabs: " + ids, "virtual", ids.get(ids.size() - 1));
	}

	/**
	 * The tab bar {@link #DEVICE_TABS} in the given configuration or one nested in it,
	 * <code>null</code> if there is none.
	 */
	private static TabBarElement.Config tabBar(Object value) {
		if (value instanceof TabBarElement.Config tabBar && DEVICE_TABS.equals(tabBar.getId())) {
			return tabBar;
		}
		if (value instanceof ConfigurationItem item) {
			for (PropertyDescriptor property : item.descriptor().getProperties()) {
				if (property.hasContainerAnnotation() || property.isDerived()) {
					// Points back up.
					continue;
				}
				TabBarElement.Config found = tabBar(item.value(property));
				if (found != null) {
					return found;
				}
			}
		} else if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				TabBarElement.Config found = tabBar(element);
				if (found != null) {
					return found;
				}
			}
		} else if (value instanceof Map<?, ?> map) {
			return tabBar(map.values());
		}
		return null;
	}

	private static String causes(Throwable ex) {
		StringBuilder result = new StringBuilder();
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (result.length() > 0) {
				result.append(" <- ");
			}
			result.append(cause.getMessage());
		}
		return result.toString();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestViewsLoad.class,
			ModelService.Module.INSTANCE, SearchBuilder.Module.INSTANCE));
	}

}

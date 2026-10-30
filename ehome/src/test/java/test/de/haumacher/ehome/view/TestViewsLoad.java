/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.layout.view.ViewLoader;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.util.model.ModelService;

/**
 * Every view of the app loads and instantiates: no configuration error of the view layer (an
 * unknown property, a script that does not compile, a duplicate id) waits for the browser.
 */
@SuppressWarnings("javadoc")
public class TestViewsLoad extends BasicTestCase {

	/**
	 * The views of the module, relative to the module directory (surefire runs there).
	 */
	private static final Path VIEWS = Path.of("src/main/webapp/WEB-INF/views");

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

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.view;

import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.de.haumacher.ehome.view.ViewCommandScan;

/**
 * The probe of the access checks of the app (<code>test.de.haumacher.ehome.view.TestCommandAccessProbe</code>,
 * haumacher/ehome #65) applied to the views of the tooling: every command whose actions change data
 * or act on the bus (the virtual device, the recorder) declares an <code>&lt;access&gt;</code> check,
 * and every declared check is valid. A same-path overlay is checked as the merged view, so the
 * commands of the app's view it extends are checked again.
 */
@SuppressWarnings("javadoc")
public class TestCommandAccessProbe extends BasicTestCase {

	public void testDataChangingCommandsAreChecked() throws Exception {
		List<String> unchecked = ViewCommandScan.uncheckedCommands(ViewCommandScan.VIEWS);
		assertEquals("Commands changing data without an access check:\n" + String.join("\n", unchecked), List.of(),
			unchecked);
	}

	public void testDataChangingDropsAreChecked() throws Exception {
		List<String> unchecked = ViewCommandScan.uncheckedDrops(ViewCommandScan.VIEWS);
		assertEquals("Drops changing data without an access check:\n" + String.join("\n", unchecked), List.of(),
			unchecked);
	}

	public void testDeclaredChecksAreValid() throws Exception {
		List<String> invalid = ViewCommandScan.invalidChecks(ViewCommandScan.VIEWS);
		assertEquals("Access checks that do not build:\n" + String.join("\n", invalid), List.of(), invalid);
	}

	public static Test suite() {
		return test.de.haumacher.ehome.view.TestCommandAccessProbe.suite(TestCommandAccessProbe.class);
	}

}

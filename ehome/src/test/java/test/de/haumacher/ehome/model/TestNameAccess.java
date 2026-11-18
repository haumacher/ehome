/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.model;

import java.io.File;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Test that the app adds no name-based model access with a literal attribute name
 * (haumacher/ehome #198): the sources agree with the inventory {@value NameAccessScan#INVENTORY}
 * (see {@link NameAccessScan}).
 */
@SuppressWarnings("javadoc")
public class TestNameAccess extends TestCase {

	public void testInventory() throws Exception {
		assertInventory(new File("."));
	}

	/**
	 * Checks the sources of the given module against its inventory, or writes the inventory with
	 * the system property {@value NameAccessScan#REGENERATE_PROPERTY}.
	 */
	public static void assertInventory(File moduleDir) throws Exception {
		assertTrue("No sources in " + moduleDir.getAbsolutePath(), new File(moduleDir, "src/main/java").isDirectory());
		if (Boolean.getBoolean(NameAccessScan.REGENERATE_PROPERTY)) {
			NameAccessScan.regenerate(moduleDir);
		}
		List<String> problems = NameAccessScan.check(moduleDir);
		assertEquals("Name-based model access differing from " + NameAccessScan.INVENTORY + ":\n"
			+ String.join("\n", problems), List.of(), problems);
	}

	public void testScan() {
		// Single quotes stand for double quotes, so that this source has no call of its own.
		String source = String.join("\n",
			"class A {",
			"	// x.tValueByName('comment')",
			"	/* x.tUpdateByName('block', 1) */",
			"	Object a = x.tValueByName('name');",
			"	Object b = x.tValueByName(NAME);",
			"	String c = 'tValueByName(\\'inString\\')';",
			"	void f() { y().tUpdateByName",
			"		(",
			"		'multi', 1); tAddByName ('add', 2); tRemoveByName('remove', 3); }",
			"}").replace('\'', '"');
		assertEquals(List.of(4, 7, 9, 9), NameAccessScan.calls(source));
	}

	public static Test suite() {
		return new TestSuite(TestNameAccess.class);
	}

}

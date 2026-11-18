/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.model;

import java.io.File;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import test.de.haumacher.ehome.model.NameAccessScan;

/**
 * The check of the app against name-based model access with a literal attribute name
 * (<code>test.de.haumacher.ehome.model.TestNameAccess</code>, haumacher/ehome #198) applied to the
 * tooling: its sources agree with its own inventory {@value NameAccessScan#INVENTORY}.
 */
@SuppressWarnings("javadoc")
public class TestNameAccess extends TestCase {

	public void testInventory() throws Exception {
		test.de.haumacher.ehome.model.TestNameAccess.assertInventory(new File("."));
	}

	public static Test suite() {
		return new TestSuite(TestNameAccess.class);
	}

}

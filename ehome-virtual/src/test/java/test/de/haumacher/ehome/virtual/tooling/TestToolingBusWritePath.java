/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import junit.framework.Test;
import junit.framework.TestSuite;
import test.de.haumacher.ehome.programming.TestBusWritePath;

/**
 * The one write path to the bus (haumacher/ehome #7), checked on the sources of the development
 * tooling: the check of the app, run in this module, reads the sources of this module.
 */
@SuppressWarnings("javadoc")
public class TestToolingBusWritePath extends TestBusWritePath {

	public static Test suite() {
		return new TestSuite(TestToolingBusWritePath.class);
	}

}

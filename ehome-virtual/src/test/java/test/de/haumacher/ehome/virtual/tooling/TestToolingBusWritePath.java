/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestSetup;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.de.haumacher.ehome.programming.TestBusWritePath;

import com.top_logic.basic.config.ApplicationConfig;

/**
 * The one write path to the bus (haumacher/ehome #7), checked on the sources of the development
 * tooling: the check of the app, run in this module, reads the sources of this module.
 */
@SuppressWarnings("javadoc")
public class TestToolingBusWritePath extends TestBusWritePath {

	public static Test suite() {
		// The operator settings (de.haumacher.ehome.settings.OperatorSettings) are read from the
		// configuration of the application.
		return BasicTestSetup.createBasicTestSetup(
			ServiceTestSetup.createSetup(TestToolingBusWritePath.class, ApplicationConfig.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import junit.framework.AssertionFailedError;

import test.com.top_logic.basic.BasicTestCase;

import com.top_logic.model.TLObject;

/**
 * A test that reads a project imported once for all tests, see {@link SharedData#sharedProject(String)}
 * (haumacher/ehome #213).
 *
 * <p>
 * Each test fails if a commit that touched a shared project was refused while it ran, also when the
 * test (or the code it runs) caught the failure of the commit: the number of refused commits is
 * compared before and after the test.
 * </p>
 */
public abstract class SharedProjectTestCase extends BasicTestCase {

	/**
	 * The project imported once from the given ETS project file, see
	 * {@link SharedData#sharedProject(String)}.
	 */
	protected static TLObject sharedProject(String file) throws Exception {
		return SharedData.sharedProject(file);
	}

	@Override
	public void runBare() throws Throwable {
		int before = SharedData.sharedProjectVetoes();
		Throwable failure = null;
		try {
			super.runBare();
		} catch (Throwable ex) {
			failure = ex;
		}
		if (SharedData.sharedProjectVetoes() != before) {
			AssertionFailedError changed = new AssertionFailedError(
				"The test changed a shared project: " + SharedData.lastSharedProjectChange());
			if (failure != null) {
				changed.addSuppressed(failure);
			}
			throw changed;
		}
		if (failure != null) {
			throw failure;
		}
	}

}

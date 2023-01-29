/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved
 */
package de.haumacher.ehome;

import com.top_logic.basic.AbstractVersion;

/**
 * Version class of the application.
 *
 * @author    <a href="mailto:support@top-logic.com">Top-Logic Support</a>
 */
public class EHVersion extends AbstractVersion {

    /** Singleton {@link EHVersion} instance. */
	public static final EHVersion INSTANCE = new EHVersion();

	private EHVersion() {
		// Singleton constructor.
	}

	/**
     * Main function to identify this package.
     * 
     * @param args Will be ignored in here.
     */
    public static void main(String args[]) {
        INSTANCE.printVersions(System.out);
    }
}

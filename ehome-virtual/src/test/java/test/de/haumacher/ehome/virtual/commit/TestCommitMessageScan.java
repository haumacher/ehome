/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.commit;

import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import test.de.haumacher.ehome.commit.CommitMessageScan;

/**
 * The scan of the app (<code>test.de.haumacher.ehome.commit.TestCommitMessageScan</code>,
 * haumacher/ehome #143) applied to the sources of the tooling: every transaction it opens carries a
 * commit message.
 */
@SuppressWarnings("javadoc")
public class TestCommitMessageScan extends TestCase {

	public void testEveryTransactionHasAMessage() throws Exception {
		List<String> unnamed = CommitMessageScan.unnamedTransactions(CommitMessageScan.SOURCES);
		assertEquals("Transactions without a commit message:\n" + String.join("\n", unnamed), List.of(), unnamed);
	}

	public static Test suite() {
		return new TestSuite(TestCommitMessageScan.class);
	}

}

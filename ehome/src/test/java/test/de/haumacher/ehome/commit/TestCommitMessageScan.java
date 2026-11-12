/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.commit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Every transaction the app opens carries a commit message (haumacher/ehome #143), see
 * {@link CommitMessageScan}; the scan itself finds the calls without one.
 */
@SuppressWarnings("javadoc")
public class TestCommitMessageScan extends TestCase {

	public void testEveryTransactionHasAMessage() throws Exception {
		List<String> unnamed = CommitMessageScan.unnamedTransactions(CommitMessageScan.SOURCES);
		assertEquals("Transactions without a commit message:\n" + String.join("\n", unnamed), List.of(), unnamed);
	}

	public void testScanFindsUnnamedTransactions() throws Exception {
		Path dir = Files.createTempDirectory("commit-scan");
		try {
			Files.writeString(dir.resolve("Sample.java"), String.join("\n",
				"try (Transaction tx = kb.beginTransaction()) {",
				"try (Transaction tx = PersistencyLayer.getKnowledgeBase()",
				"	.beginTransaction( )) {",
				"try (Transaction tx = kb.beginTransaction(I18NConstants.COMMIT_X)) {",
				"KBUtils.inTransaction(kb, () -> x());",
				"kb.begin();",
				"// kb.beginTransaction()",
				"/* kb.beginTransaction() */ String s = \"beginTransaction()\";"));
			assertEquals(List.of("Sample.java:1: beginTransaction()", "Sample.java:3: beginTransaction()",
				"Sample.java:5: KBUtils.inTransaction(", "Sample.java:6: kb.begin()"),
				CommitMessageScan.unnamedTransactions(dir));
		} finally {
			Files.delete(dir.resolve("Sample.java"));
			Files.delete(dir);
		}
	}

	public static Test suite() {
		return new TestSuite(TestCommitMessageScan.class);
	}

}

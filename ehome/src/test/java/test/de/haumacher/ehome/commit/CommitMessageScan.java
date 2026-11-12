/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.commit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The scan of the Java sources of a module for transactions opened without a commit message
 * (haumacher/ehome #143), for <code>TestCommitMessageScan</code> of the app and of the tooling.
 *
 * <p>
 * The change log of a user ("Meine letzten Änderungen") lists each of the user's commits with its
 * message; a transaction opened without one shows "Keine Nachricht.". Every transaction E-Home opens
 * names what was done and on what, as a <code>ResKey</code> of its package. Found are the engine's
 * entry points that commit without a message: {@code KnowledgeBase#beginTransaction()} without an
 * argument, the legacy {@code KnowledgeBase#begin()} and {@code KBUtils#inTransaction}. Comments are
 * not scanned.
 * </p>
 *
 * <p>
 * The view commands need no scan: the engine commits a <code>&lt;with-transaction&gt;</code>, a form
 * and the deletion of an object with a message of its own (the label of the command and the object, or
 * the configured <code>commit-message</code>, <code>ViewCommitMessage</code>).
 * </p>
 */
public class CommitMessageScan {

	/**
	 * The Java sources of a module, relative to the module directory (surefire runs there).
	 */
	public static final Path SOURCES = Path.of("src/main/java");

	/** Calls opening a transaction without a commit message. */
	private static final Pattern UNNAMED = Pattern.compile(
		"\\bbeginTransaction\\s*\\(\\s*\\)|\\bKBUtils\\s*\\.\\s*inTransaction\\s*\\("
			+ "|(?:\\bkb|getKnowledgeBase\\s*\\(\\s*\\))\\s*\\.\\s*begin\\s*\\(\\s*\\)");

	/** Comments and literals, which are blanked before the scan. */
	private static final Pattern COMMENTS_AND_LITERALS =
		Pattern.compile("/\\*.*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\\\n])*\"", Pattern.DOTALL);

	private CommitMessageScan() {
		// Static helpers.
	}

	/**
	 * The places in the Java sources below the given directory that open a transaction without a
	 * commit message, as <code>file:line: call</code>.
	 */
	public static List<String> unnamedTransactions(Path sources) throws IOException {
		List<String> result = new ArrayList<>();
		try (Stream<Path> files = Files.walk(sources)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
				String code = blank(Files.readString(file, StandardCharsets.UTF_8));
				Matcher matcher = UNNAMED.matcher(code);
				while (matcher.find()) {
					result.add(sources.relativize(file) + ":" + line(code, matcher.start()) + ": "
						+ matcher.group().replaceAll("\\s+", ""));
				}
			}
		}
		return result;
	}

	/**
	 * The given source with its comments and string literals replaced by blanks, keeping the line
	 * breaks so that the line numbers stay.
	 */
	static String blank(String source) {
		Matcher matcher = COMMENTS_AND_LITERALS.matcher(source);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group().replaceAll("[^\\n]", " ")));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private static int line(String code, int offset) {
		int line = 1;
		for (int n = 0; n < offset; n++) {
			if (code.charAt(n) == '\n') {
				line++;
			}
		}
		return line;
	}

}

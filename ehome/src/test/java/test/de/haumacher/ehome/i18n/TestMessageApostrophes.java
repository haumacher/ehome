/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.i18n;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.top_logic.basic.util.ResKey;

/**
 * Test that the apostrophes of the message bundles survive their formatting (haumacher/ehome #76).
 *
 * <p>
 * A {@link ResKey} filled with arguments is formatted as a message format
 * ({@code ResKey.MessageKey}, {@code TLMessageFormat}), which takes a single apostrophe as the
 * start of a quote and drops it ("the project's key" shows "the projects key"); such a text writes
 * the apostrophe doubled, as TopLogic's own bundles do. A key without arguments is not formatted,
 * so its text keeps a single apostrophe, and a doubled one would show twice. A text with a
 * placeholder <code>{n}</code> is taken as one that is filled with arguments.
 * </p>
 *
 * <p>
 * Scans every bundle of the app ({@code src/main/java/META-INF}, {@code WEB-INF/conf/resources})
 * and of the tooling module next to it ({@code ../ehome-virtual}), in both languages.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMessageApostrophes extends TestCase {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+");

	/** An apostrophe that is not part of a doubled one. */
	private static final Pattern SINGLE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

	private static final String[] BUNDLE_DIRS = {
		"src/main/java/META-INF",
		"src/main/webapp/WEB-INF/conf/resources",
		"../ehome-virtual/src/main/java/META-INF",
		"../ehome-virtual/src/main/webapp/WEB-INF/conf/resources",
	};

	public void testApostrophes() throws IOException {
		List<File> bundles = bundles();
		assertTrue("No message bundles found in " + new File(".").getAbsolutePath(), bundles.size() > 2);
		List<String> problems = new ArrayList<>();
		for (File bundle : bundles) {
			Properties texts = new Properties();
			try (Reader in = new InputStreamReader(Files.newInputStream(bundle.toPath()), StandardCharsets.UTF_8)) {
				texts.load(in);
			}
			for (String key : new TreeSet<>(texts.stringPropertyNames())) {
				String text = texts.getProperty(key);
				boolean formatted = PLACEHOLDER.matcher(text).find();
				if (formatted && SINGLE_APOSTROPHE.matcher(text).find()) {
					problems.add(bundle.getPath() + ": " + key
						+ ": a single apostrophe in a text with arguments is swallowed by the formatting, write it doubled.");
				} else if (!formatted && text.contains("''")) {
					problems.add(bundle.getPath() + ": " + key
						+ ": a doubled apostrophe in a text without arguments shows twice, write it single.");
				}
			}
		}
		if (!problems.isEmpty()) {
			fail(problems.size() + " message texts with wrong apostrophes:\n" + String.join("\n", problems));
		}
	}

	private static List<File> bundles() throws IOException {
		List<File> result = new ArrayList<>();
		for (String dir : BUNDLE_DIRS) {
			File base = new File(dir);
			if (!base.isDirectory()) {
				continue;
			}
			try (Stream<Path> files = Files.list(base.toPath())) {
				files.map(Path::toFile)
					.filter(file -> file.getName().endsWith(".properties"))
					.sorted()
					.forEach(result::add);
			}
		}
		return result;
	}

	public static Test suite() {
		return new TestSuite(TestMessageApostrophes.class);
	}

}

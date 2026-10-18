/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.runtime.TestImageConformance.IMAGES;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;

/**
 * The image fixtures of the specification (<code>src/test/fixtures/homecontrol/images/</code>)
 * checked by {@link ImageValidator}: the example and the minimal image are valid, the example with its
 * rules and schedules checked by the loaders of the reference interpreter.
 */
@SuppressWarnings("javadoc")
public class TestImageFixtures extends TestCase {

	public void testExample() throws IOException {
		ImageReport report = ImageValidator.validate(read("example.json"));
		assertTrue(report.toString(), report.isValid());
		assertEquals(List.of(), report.getNotes());
	}

	public void testMinimal() throws IOException {
		ImageReport report = ImageValidator.validate(read("minimal.json"));
		assertTrue(report.toString(), report.isValid());
		assertEquals(List.of(), report.getNotes());
	}

	/**
	 * The template of the test system names its addresses by placeholders, which are no group
	 * addresses; with addresses it is valid.
	 */
	public void testTemplate() throws IOException {
		String template = read("test-system.template.json");
		ImageReport placeholders = ImageValidator.validate(template);
		assertFalse(placeholders.isValid());
		assertTrue(placeholders.getRefusals().stream().allMatch(r -> r.reason().contains("is not a group address")));
		ImageReport filled = ImageValidator.validate(fill(template));
		assertTrue(filled.toString(), filled.isValid());
	}

	public void testSyntaxAndSize() {
		ImageReport syntax = ImageValidator.validate("{");
		assertEquals(ImageReport.Kind.SYNTAX, syntax.getKind());
		ImageReport large = ImageValidator.validate(" ".repeat(ImageValidator.MAX_IMAGE_BYTES + 1));
		assertEquals(ImageReport.Kind.TOO_LARGE, large.getKind());
		ImageReport deep = ImageValidator.validate("{\"image\": " + "[".repeat(49) + "]".repeat(49) + "}");
		assertEquals(ImageReport.Kind.SYNTAX, deep.getKind());
	}

	/**
	 * The template with a distinct address for each placeholder.
	 */
	static String fill(String template) {
		Matcher matcher = Pattern.compile("(?<!\\\\)\"GA:[^\"\\\\]+\"").matcher(template);
		Map<String, String> addresses = new HashMap<>();
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String address = addresses.computeIfAbsent(matcher.group(), p -> "\"1/0/" + (addresses.size() + 1) + "\"");
			matcher.appendReplacement(result, Matcher.quoteReplacement(address));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private static String read(String name) throws IOException {
		return Files.readString(new File(IMAGES, name).toPath(), StandardCharsets.UTF_8);
	}
	/**
	 * The tests of this class.
	 */
	public static Test suite() {
		return new TestSuite(TestImageFixtures.class);
	}

}

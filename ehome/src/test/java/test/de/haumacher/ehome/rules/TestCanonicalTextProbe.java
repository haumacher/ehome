/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.runtime.Json;

/**
 * Probe of the normalized canonical text (#63, spec round one): for every expression of the whole
 * corpus, the canonical text is a fixed point (printing what it parses to gives it again), parsing it
 * gives the normalized tree, and normalizing is idempotent.
 */
@SuppressWarnings("javadoc")
public class TestCanonicalTextProbe extends TestCase {

	public void testFixedPoint() throws Exception {
		List<String> failures = new ArrayList<>();
		int[] count = { 0 };
		File[] files = TestRuleCorpus.CORPUS.listFiles((dir, name) -> name.endsWith(".json"));
		Arrays.sort(files);
		for (File file : files) {
			Object json = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), 200);
			walk(file.getName(), null, json, failures, count);
		}
		assertEquals(String.join("\n", failures), List.of(), failures);
		assertTrue(String.valueOf(count[0]), count[0] >= 200);
	}

	/** The members of a rule (and of an expression) that hold an expression. */
	private static final Set<String> EXPRESSIONS =
		Set.of("value", "args", "left", "right", "condition", "duration", "operand", "after");

	private static void walk(String where, Object member, Object json, List<String> failures, int[] count) {
		if (json instanceof Map<?, ?> map) {
			// A timer is an argument of a function only and is checked within its call.
			if (member != null && EXPRESSIONS.contains(member) && map.get("kind") instanceof String
				&& !"timer".equals(map.get("kind"))) {
				check(where, map, failures, count);
			}
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				walk(where, entry.getKey(), entry.getValue(), failures, count);
			}
		} else if (json instanceof List<?> list) {
			for (Object element : list) {
				walk(where, member, element, failures, count);
			}
		}
	}

	private static void check(String where, Map<?, ?> node, List<String> failures, int[] count) {
		// The canonical text is that of the normalized tree.
		Object normalized = RuleText.normalize(node);
		String text = RuleText.print(normalized);
		count[0]++;
		if (!TestRuleText.same(normalized, RuleText.normalize(normalized))) {
			failures.add(where + ": normalize not idempotent: " + text);
		}
		try {
			Map<String, Object> parsed = RuleText.parse(text);
			String again = RuleText.print(parsed);
			if (!again.equals(text)) {
				failures.add(where + ": '" + text + "' prints as '" + again + "'");
			}
			if (!TestRuleText.same(normalized, RuleText.normalize(parsed))) {
				failures.add(where + ": '" + text + "' parses to " + Json.write(parsed) + ", normalized "
					+ Json.write(normalized));
			}
		} catch (RuleText.SyntaxError ex) {
			failures.add(where + ": canonical text '" + text + "' does not parse: " + ex.getMessage());
		}
	}

	public static Test suite() {
		return new TestSuite(TestCanonicalTextProbe.class);
	}

}

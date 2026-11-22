/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.ValueTypes;

/**
 * The initial value of an application datapoint as the editor takes it (haumacher/ehome #217): a
 * literal of the rule language of the datapoint's type, read into the JSON literal value of the
 * image (homecontrol <code>spec/runtime-image.md</code> 3.3) and printed back. The neutral value of
 * every type is a value of it; a value outside the type's range is refused, as the image's
 * validation refuses it.
 */
@SuppressWarnings("javadoc")
public class TestInitialValueLiterals extends TestCase {

	public void testNeutralValueOfEveryType() throws Exception {
		for (String type : ValueTypes.ALL) {
			String neutral = ApplicationDatapoints.neutralInitial(type);
			Object json = ApplicationDatapoints.initialValue(neutral, type);
			assertTrue(type, ImageValidator.initialFits(json, type));
			assertEquals(type, neutral, ApplicationDatapoints.initialLiteral(json, type));
		}
	}

	public void testLiterals() throws Exception {
		// Text, type, the JSON literal value, the canonical text.
		Object[][] cases = {
			{ "true", ValueTypes.BOOLEAN, "true", "true" },
			{ "4294967295", ValueTypes.UNSIGNED, "4294967295", "4294967295" },
			{ "-15", ValueTypes.SIGNED, "-15", "-15" },
			{ "21", ValueTypes.REAL, "21", "21.0" },
			{ "\"Willkommen\"", ValueTypes.STRING, "\"Willkommen\"", "\"Willkommen\"" },
			{ "6:30", ValueTypes.TIME_OF_DAY, "{\"hour\":6,\"minute\":30,\"second\":0}", "06:30" },
			{ "2026-11-22", ValueTypes.DATE, "{\"year\":2026,\"month\":11,\"day\":22}", "2026-11-22" },
			{ "2", ValueTypes.ENUMERATION, "2", "2" },
			{ "controlled(true, 3)", ValueTypes.CONTROLLED, "{\"control\":true,\"value\":3}", "controlled(true, 3)" },
			{ "#FF8000", ValueTypes.COLOR, null, "#ff8000" },
			{ "x\"0aff\"", ValueTypes.RAW, "\"0A FF\"", "x\"0AFF\"" },
		};
		for (Object[] c : cases) {
			String text = (String) c[0];
			String type = (String) c[1];
			Object json = ApplicationDatapoints.initialValue(text, type);
			if (c[2] != null) {
				assertEquals(text, c[2], Json.writeCompact(json));
			}
			assertEquals(text, c[3], ApplicationDatapoints.initialLiteral(json, type));
		}
	}

	public void testRefused() {
		Object[][] cases = {
			{ "1", ValueTypes.BOOLEAN },
			{ "-1", ValueTypes.UNSIGNED },
			{ "2147483648", ValueTypes.SIGNED },
			{ "21.5", ValueTypes.UNSIGNED },
			{ "\"a text of more than 28 bytes!\"", ValueTypes.STRING },
			{ "25:00", ValueTypes.TIME_OF_DAY },
			{ "controlled(true, 256)", ValueTypes.CONTROLLED },
			{ "true", ValueTypes.CONTROLLED },
			{ "@other", ValueTypes.BOOLEAN },
			{ "warm", ValueTypes.REAL },
		};
		for (Object[] c : cases) {
			try {
				ApplicationDatapoints.initialValue((String) c[0], (String) c[1]);
				fail("Refused: " + c[0] + " as " + c[1]);
			} catch (SyntaxError ex) {
				// Expected.
			}
		}
		// The image's validation refuses what the import does not take (an image written elsewhere).
		assertNull(ApplicationDatapoints.initialLiteral(Long.valueOf(-1), ValueTypes.UNSIGNED));
		assertNull(ApplicationDatapoints.initialLiteral(Map.of("hour", Long.valueOf(6)), ValueTypes.TIME_OF_DAY));
		assertNull(ApplicationDatapoints.initialLiteral(List.of(), ValueTypes.RAW));
	}

	public static Test suite() {
		return new TestSuite(TestInitialValueLiterals.class);
	}

}

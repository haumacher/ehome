/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;

/**
 * The conformance cases of the runtime image (homecontrol <code>spec/fixtures/images/cases/</code>,
 * copied to <code>src/test/fixtures/homecontrol/images/cases/</code>) run against E-Home's
 * {@link ImageValidator}, as the controller's own test runs them against its loader: a case
 * expects a valid image, or the kind of the refusal and exactly its refusals, each found by its path
 * and a part of its reason.
 *
 * <p>
 * The cases of the rules and the schedules ({@link #NOT_CHECKED}) need the rule loader and the
 * scheduler of the controller, which E-Home does not have; E-Home exports neither member. For them
 * the test asserts that the validator says it did not check them, so a new case of such a member
 * fails here instead of passing unnoticed.
 * </p>
 */
public class TestImageConformance extends TestCase {

	/** The directory of the image fixtures of the specification. */
	public static final File IMAGES = new File("src/test/fixtures/homecontrol/images");

	/**
	 * The cases E-Home does not check, with the member it leaves to the controller.
	 */
	static final Map<String, String> NOT_CHECKED = Map.of(
		"refuse-rules.json", "rules",
		"refuse-schedules.json", "schedules");

	private final File _file;

	/**
	 * Creates a {@link TestImageConformance} for the given case.
	 */
	public TestImageConformance(File file) {
		super(file.getName());
		_file = file;
	}

	@Override
	protected void runTest() throws Throwable {
		String text = Files.readString(_file.toPath(), StandardCharsets.UTF_8);
		Map<?, ?> doc = (Map<?, ?>) Json.parse(text, 100);
		Map<?, ?> expect = (Map<?, ?>) doc.get("expect");
		ImageReport report = ImageValidator.validate(text);
		String member = NOT_CHECKED.get(_file.getName());
		if (member != null) {
			assertTrue("E-Home does not check " + member + ", and says so: " + report.getNotes(),
				report.getNotes().stream().anyMatch(note -> note.contains("The " + member + " are not checked")));
			return;
		}
		assertEquals(report.toString(), expect.get("valid"), Boolean.valueOf(report.isValid()));
		if (report.isValid()) {
			return;
		}
		assertEquals(report.toString(), expect.get("kind"), report.getKind().externalName());
		List<?> refusals = (List<?>) expect.get("refusals");
		assertEquals("Number of refusals: " + report, refusals.size(), report.getRefusals().size());
		for (Object entry : refusals) {
			Map<?, ?> refusal = (Map<?, ?>) entry;
			String path = (String) refusal.get("path");
			String reason = (String) refusal.get("reason");
			boolean found = false;
			for (Refusal actual : report.getRefusals()) {
				found |= actual.path().equals(path) && actual.reason().contains(reason);
			}
			assertTrue("Expected " + path + ": ..." + reason + "... in " + report, found);
		}
	}

	/**
	 * One test per case file.
	 */
	public static Test suite() {
		TestSuite suite = new TestSuite(TestImageConformance.class.getName());
		File[] files = new File(IMAGES, "cases").listFiles((dir, name) -> name.endsWith(".json"));
		assertNotNull(files);
		Arrays.sort(files);
		assertTrue(files.length >= 13);
		Set<String> names = new java.util.HashSet<>();
		for (File file : files) {
			suite.addTest(new TestImageConformance(file));
			names.add(file.getName());
		}
		assertTrue("Every case left to the controller exists.", names.containsAll(NOT_CHECKED.keySet()));
		return suite;
	}

}

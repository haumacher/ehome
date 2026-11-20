/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Writes when each test of the suite started and ended (milliseconds since the start of the suite) to
 * <code>target/surefire-reports/test-timeline.txt</code> of the module, for
 * <code>bin/slowest-tests.py</code> (haumacher/ehome #208).
 *
 * <p>
 * The surefire report has the time of each test method only; the setups and teardowns of the
 * suites around them (services, imports of a class's <code>TestSetup</code>) are in no test. From the
 * timeline, the time of a class includes the time before its first test since the last test of the
 * class before it.
 * </p>
 *
 * <p>
 * Registered for the JUnit platform of surefire by
 * <code>META-INF/services/org.junit.platform.launcher.TestExecutionListener</code> in the test
 * resources of the app, which reach the tooling's suite with the app's test-jar.
 * </p>
 */
public class TimelineListener implements TestExecutionListener {

	private static final Path FILE = Path.of("target", "surefire-reports", "test-timeline.txt");

	private static final Pattern CLASS_IN_ID = Pattern.compile("\\(([\\w.$]+)\\)\\]$");

	private final List<String> _lines = new ArrayList<>();

	private long _start;

	@Override
	public void testPlanExecutionStarted(TestPlan testPlan) {
		_start = System.nanoTime();
	}

	@Override
	public void executionStarted(TestIdentifier testIdentifier) {
		record("start", testIdentifier);
	}

	@Override
	public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
		record("end", testIdentifier);
	}

	private void record(String event, TestIdentifier test) {
		if (!test.isTest()) {
			return;
		}
		TestSource source = test.getSource().orElse(null);
		String name;
		if (source instanceof MethodSource method) {
			name = method.getClassName() + "#" + method.getMethodName();
		} else if (source instanceof ClassSource type) {
			// A test of a suite with a setup (e.g. "testImport with H2_KB").
			name = type.getClassName() + "#" + test.getLegacyReportingName();
		} else {
			// The JUnit 3 test of a suite: its ID ends with "name(class)".
			Matcher type = CLASS_IN_ID.matcher(test.getUniqueId());
			name = type.find() ? type.group(1) + "#" + test.getLegacyReportingName() : test.getLegacyReportingName();
		}
		_lines.add(event + "\t" + (System.nanoTime() - _start) / 1_000_000 + "\t" + name);
	}

	@Override
	public void testPlanExecutionFinished(TestPlan testPlan) {
		_lines.add("finished\t" + (System.nanoTime() - _start) / 1_000_000 + "\t");
		try {
			Files.createDirectories(FILE.getParent());
			try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(FILE, StandardCharsets.UTF_8))) {
				_lines.forEach(out::println);
			}
		} catch (IOException ex) {
			System.err.println("Test timeline not written: " + ex.getMessage());
		}
	}

}

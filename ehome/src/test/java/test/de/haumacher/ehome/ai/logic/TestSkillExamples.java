/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai.logic;

import static test.de.haumacher.ehome.ai.logic.LogicFixture.turn;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;

import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.tool.api.ToolResult;

import de.haumacher.ehome.ai.logic.RuleTools;
import de.haumacher.ehome.ai.logic.SimulationTools;
import de.haumacher.ehome.runtime.RuleExport;

/**
 * The examples of the skill <code>EHome_rules</code> (<code>WEB-INF/autoconf/ehome.ai.logic.config.xml</code>)
 * do not rot: every <code>```json</code> block of the skills is checked by <code>checkAutomation</code>
 * in a project with the application datapoints the skill names ({@link LogicFixture#SKILL_DATAPOINTS}),
 * and the worked pattern "entrance at night" runs three scenarios in the simulation (haumacher/ehome
 * #128):
 *
 * <ol>
 * <li>Arriving at 22:00 in the dark: motion, the door opened and closed - the yard light goes off when
 * the door closes, the entrance light one minute later.</li>
 * <li>Leaving: the door opened, closed and locked with motion outside within the last minute - the lights
 * stay on for three minutes after the lock, then go off.</li>
 * <li>Closing the door from inside without locking: the lights go off one minute after the close.</li>
 * </ol>
 */
@SuppressWarnings("javadoc")
public class TestSkillExamples extends BasicTestCase {

	private static final File SKILLS = new File("src/main/webapp/WEB-INF/autoconf/ehome.ai.logic.config.xml");

	private static final Pattern JSON = Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL);

	private static final String START = "2026-11-10T22:00";

	private final LogicFixture _fixture = new LogicFixture("ehomeSkillExamples");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_fixture.setUp();
	}

	@Override
	protected void tearDown() throws Exception {
		_fixture.tearDown();
		super.tearDown();
	}

	public void testEveryExampleValidates() throws Exception {
		List<String> examples = examples();
		assertTrue("The skill has its worked patterns: " + examples.size(), examples.size() >= 10);
		TLObject project = _fixture.project();
		for (String example : examples) {
			ToolResult result = turn(_fixture.installer(),
				c -> new RuleTools().checkAutomation(c, project, TestLogicTools.definitions(example)));
			assertFalse("Example does not validate: " + result.text() + "\n" + example, result.failed());
		}
		assertTrue("A check saves nothing.", RuleExport.orderedRules(project).isEmpty());
	}

	public void testEntranceArriving() throws Exception {
		String timeline = run(initial(), set("outdoor.motion", "true"), wait("5s"),
			set("door.open", "true"), wait("5s"), set("door.open", "false"), wait("2min"));
		assertTrue(timeline, timeline.contains("22:00:00.000 command entrance-night: write yard.light = true"));
		assertTrue(timeline, timeline.contains("22:00:00.000 command entrance-night: write entrance.light = true"));
		assertTrue("Yard off at the close: " + timeline,
			timeline.contains("22:00:10.000 command entrance-night: write yard.light = false"));
		assertTrue("Entrance off a minute later: " + timeline,
			timeline.contains("22:01:10.000 command entrance-night: write entrance.light = false"));
		assertFalse(timeline, writesBetween(timeline, "entrance.light = false", "22:00:01", "22:01:09"));
	}

	public void testEntranceLeaving() throws Exception {
		String timeline = run(initial(), set("door.open", "true"), wait("10s"),
			set("door.open", "false"), wait("20s"), set("outdoor.motion", "true"), wait("10s"),
			set("door.locked", "true"), wait("4min"));
		assertTrue(timeline, timeline.contains("22:00:00.000 command entrance-night: write yard.light = true"));
		assertFalse("The lights stay on until 3 min after the lock: " + timeline,
			writesBetween(timeline, "yard.light = false", "22:00:01", "22:03:39")
				|| writesBetween(timeline, "entrance.light = false", "22:00:01", "22:03:39"));
		assertTrue("Off 3 min after the lock: " + timeline,
			timeline.contains("22:03:40.000 command entrance-night: write yard.light = false"));
		assertTrue(timeline, timeline.contains("22:03:40.000 command entrance-night: write entrance.light = false"));
	}

	public void testEntranceClosedWithoutLock() throws Exception {
		String timeline = run(initial(), set("door.open", "true"), wait("10s"),
			set("door.open", "false"), wait("3min"));
		assertFalse(timeline, writesBetween(timeline, "yard.light = false", "22:00:01", "22:01:09"));
		assertTrue("Off 1 min after the close: " + timeline,
			timeline.contains("22:01:10.000 command entrance-night: write yard.light = false"));
		assertTrue(timeline, timeline.contains("22:01:10.000 command entrance-night: write entrance.light = false"));
	}

	/**
	 * The inputs as their sensors report them at the start (a controller reads them at its start; in
	 * the simulation of a scenario nothing has a value before it is set): dark, nobody outside, the door
	 * closed and unlocked.
	 */
	private static List<Map<String, Object>> initial() {
		return List.of(set("outdoor.lux", "5.0"), set("outdoor.motion", "false"), set("door.open", "false"),
			set("door.locked", "false"));
	}

	@SafeVarargs
	private String run(List<Map<String, Object>> initial, Map<String, Object>... steps) throws Exception {
		String entrance = null;
		for (String example : examples()) {
			if (example.contains("\"entrance-night\"")) {
				entrance = example;
			}
		}
		assertNotNull(entrance);
		Map<String, Object> definitions = TestLogicTools.definitions(entrance);
		List<Map<String, Object>> all = new ArrayList<>(initial);
		all.addAll(List.of(steps));
		ToolResult result = turn(_fixture.installer(),
			c -> new SimulationTools().simulate(c, _fixture.project(), START, all, definitions));
		assertFalse(result.text(), result.failed());
		return result.text();
	}

	/** Whether the timeline has the write at a time of day between the two, inclusive. */
	private static boolean writesBetween(String timeline, String write, String from, String until) {
		Matcher matcher = Pattern.compile("(\\d\\d:\\d\\d:\\d\\d)\\.\\d{3} command entrance-night: write " + Pattern.quote(write))
			.matcher(timeline);
		while (matcher.find()) {
			String time = matcher.group(1);
			if (time.compareTo(from) >= 0 && time.compareTo(until) <= 0) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, Object> set(String datapoint, String value) {
		return Map.of("set", datapoint, "value", value);
	}

	private static Map<String, Object> wait(String duration) {
		return Map.of("wait", duration);
	}

	/** The ```json blocks of the skills, unescaped from the XML. */
	static List<String> examples() throws Exception {
		String text = Files.readString(SKILLS.toPath(), StandardCharsets.UTF_8);
		List<String> result = new ArrayList<>();
		Matcher matcher = JSON.matcher(text);
		while (matcher.find()) {
			result.add(matcher.group(1));
		}
		return result;
	}

	public static Test suite() {
		return TestLogicTools.suite(TestSkillExamples.class);
	}

}

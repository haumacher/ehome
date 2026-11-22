/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.Engine.LogCode;
import de.haumacher.ehome.rules.Engine.RuleStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.RuleSet.StartReason;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.runtime.Json;

/**
 * A rule's last error is a problem only until its site runs without it (homecontrol
 * <code>spec/rules.md</code> 9.1, homecontrol #114, haumacher/ehome #220), the cases of homecontrol's
 * host tests (<code>core/test/rules_unit_test.cpp</code>): an expression's error is resolved when the
 * expression has a value or evaluates without an error, a silence trigger's refusal when it arms, any
 * other error when its reaction runs again without a problem, one outside a reaction by the rule's
 * next clean reaction; a new error makes it a problem again; <code>errors</code> counts everything
 * logged.
 */
@SuppressWarnings("javadoc")
public class TestErrorResolution extends TestCase {

	private final long[] _now = { 1000 };

	private boolean _refuseCommands;

	private boolean _refuseReads;

	private DatapointModel _model;

	private Engine _engine;

	private final List<String> _resolved = new ArrayList<>();

	private final DatapointModel.Technology _technology = new DatapointModel.Technology() {
		@Override
		public boolean command(Datapoint datapoint, Val value) {
			return !_refuseCommands;
		}

		@Override
		public boolean read(Datapoint datapoint) {
			return !_refuseReads;
		}
	};

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_model = new DatapointModel(() -> _now[0]);
		_engine = new Engine(_model, () -> _now[0], new ParameterStore(), new Engine.TimeSource() {
			@Override
			public DateTime localNow() {
				return null;
			}

			@Override
			public TimeOfDay sunTime(SunEvent event) {
				return null;
			}
		});
		_engine.setObserver(new Engine.Observer() {
			@Override
			public void errorResolved(String rule, LogCode code) {
				_resolved.add(rule + " " + code.externalName());
			}
		});
	}

	@Override
	protected void tearDown() throws Exception {
		_engine.close();
		super.tearDown();
	}

	private void datapoint(String name, String type) {
		_model.add(name, ValueType.fromName(type), true, _technology);
	}

	private void load(String rules) throws Exception {
		assertNull(_engine.load(Json.parse(rules, 100), id -> false));
	}

	private void report(String name, Val value) {
		_model.report(name, value);
		_engine.drain();
	}

	private RuleStatus status() {
		return _engine.status(0);
	}

	private static String dp(String name) {
		return "{\"kind\": \"datapoint\", \"name\": \"" + name + "\"}";
	}

	private static String onUpdate(String name) {
		return "{\"triggers\": [{\"kind\": \"datapoint\", \"datapoint\": \"" + name + "\", \"on\": \"update\"}], ";
	}

	private static String write(String datapoint, String value) {
		return "{\"kind\": \"write\", \"datapoint\": \"" + datapoint + "\", \"value\": " + value + "}";
	}

	private static final String TRUE = "{\"kind\": \"literal\", \"type\": \"boolean\", \"value\": true}";

	private static String hoflicht() {
		return "[{\"id\": \"hoflicht\", \"name\": \"Hoflicht\", \"reactions\": [" + onUpdate("bewegung")
			+ "\"condition\": {\"kind\": \"binary\", \"op\": \"and\", \"left\": " + dp("bewegung")
			+ ", \"right\": {\"kind\": \"binary\", \"op\": \"<\", \"left\": " + dp("helligkeit")
			+ ", \"right\": {\"kind\": \"literal\", \"type\": \"integer\", \"value\": 50}}}, \"actions\": ["
			+ write("licht", TRUE) + "]}]}]";
	}

	/**
	 * rule-hoflicht on the house controller: three conditionNoValue at the boot while the illuminance
	 * had no value, then clean evaluations; the error is resolved, the same problem again is current
	 * again, and after an image swap the next clean reaction resolves it.
	 */
	public void testHoflicht() throws Exception {
		datapoint("bewegung", "boolean");
		datapoint("helligkeit", "unsigned");
		datapoint("licht", "boolean");
		load(hoflicht());
		_engine.start(StartReason.BOOT);
		_engine.drain();
		for (int i = 0; i < 3; i++) {
			_now[0] += 1000;
			report("bewegung", Val.ofBool(true));
		}
		RuleStatus status = status();
		assertEquals(LogCode.CONDITION_NO_VALUE, status.lastErrorCode());
		// Logged once until the condition had a value.
		assertEquals(1, status.errors());
		assertFalse(status.resolved());
		assertTrue(status.problem());
		long errorMs = status.lastErrorMs();

		// The illuminance arrives: the next evaluation has a value, the error is history from then on.
		_now[0] += 60000;
		report("helligkeit", Val.ofInt(0));
		assertFalse("Nothing evaluated the condition yet.", status().resolved());
		report("bewegung", Val.ofBool(true));
		status = status();
		assertTrue(status.resolved());
		assertFalse(status.problem());
		assertEquals(_now[0], status.resolvedMs());
		assertEquals(errorMs, status.lastErrorMs());
		assertEquals(LogCode.CONDITION_NO_VALUE, status.lastErrorCode());
		assertEquals(1, status.errors());
		assertEquals("@bewegung and @helligkeit < 50 – helligkeit has no value", status.lastErrorText());
		assertEquals(List.of("hoflicht conditionNoValue"), _resolved);

		// Further clean runs keep it resolved, at the time it was resolved.
		long resolvedMs = status.resolvedMs();
		_now[0] += 1000;
		report("bewegung", Val.ofBool(true));
		assertEquals(resolvedMs, status().resolvedMs());
		assertEquals(1, _resolved.size());

		// The same problem again is current again.
		_model.reportStale("helligkeit");
		_engine.drain();
		_now[0] += 1000;
		report("bewegung", Val.ofBool(true));
		status = status();
		assertFalse(status.resolved());
		assertEquals(2, status.errors());
		assertEquals(_now[0], status.lastErrorMs());

		// An image swap: the expression of the old image is gone, the problem stays one until a reaction
		// of the rule runs without a problem.
		load(hoflicht());
		_engine.drain();
		assertFalse(status().resolved());
		report("helligkeit", Val.ofInt(10));
		_now[0] += 1000;
		report("bewegung", Val.ofBool(true));
		status = status();
		assertTrue(status.resolved());
		assertEquals(_now[0], status.resolvedMs());
	}

	/**
	 * A valueNoValue is resolved when the written value has one; a clean run of another reaction of
	 * the rule does not resolve it.
	 */
	public void testValueNoValue() throws Exception {
		datapoint("go", "boolean");
		datapoint("other", "boolean");
		datapoint("lux", "unsigned");
		datapoint("level", "unsigned");
		datapoint("out", "boolean");
		load("[{\"id\": \"x\", \"name\": \"X\", \"reactions\": [" + onUpdate("go") + "\"actions\": ["
			+ write("level", dp("lux")) + "]}, " + onUpdate("other") + "\"actions\": [" + write("out", TRUE) + "]}]}]");
		_engine.start(StartReason.BOOT);
		_engine.drain();
		report("go", Val.ofBool(true));
		assertEquals(LogCode.VALUE_NO_VALUE, status().lastErrorCode());
		assertTrue(status().problem());
		report("other", Val.ofBool(true));
		assertTrue("Another reaction does not resolve an expression's error.", status().problem());
		report("lux", Val.ofInt(3));
		report("go", Val.ofBool(true));
		assertTrue(status().resolved());
	}

	/**
	 * An error without an expression is resolved by its reaction running again without a problem, not
	 * by another reaction of the rule; a refusal the log-once rule does not log again is still a
	 * problem.
	 */
	public void testReactionResolvesItsError() throws Exception {
		datapoint("a", "boolean");
		datapoint("b", "boolean");
		datapoint("out", "boolean");
		datapoint("t", "real");
		load("[{\"id\": \"x\", \"name\": \"X\", \"reactions\": [" + onUpdate("a") + "\"actions\": [" + write("out", TRUE)
			+ "]}, " + onUpdate("b") + "\"actions\": [{\"kind\": \"read\", \"datapoint\": \"t\"}]}]}]");
		_engine.start(StartReason.BOOT);
		_engine.drain();

		// writeRefused in the reaction on a: a clean run of the other reaction does not resolve it.
		_refuseCommands = true;
		report("a", Val.ofBool(true));
		assertEquals(LogCode.WRITE_REFUSED, status().lastErrorCode());
		assertFalse(status().resolved());
		report("b", Val.ofBool(true));
		assertFalse(status().resolved());
		_refuseCommands = false;
		_now[0] += 1000;
		report("a", Val.ofBool(true));
		assertTrue(status().resolved());
		assertEquals(_now[0], status().resolvedMs());

		// readRefused is logged once per action: a refusal that persists unlogged is still a problem.
		_refuseReads = true;
		report("b", Val.ofBool(true));
		assertEquals(LogCode.READ_REFUSED, status().lastErrorCode());
		assertFalse(status().resolved());
		report("b", Val.ofBool(true));
		assertFalse(status().resolved());
		assertEquals(2, status().errors());
		_refuseReads = false;
		report("b", Val.ofBool(true));
		assertTrue(status().resolved());
	}

	/** An evalError is resolved when its expression evaluates without an error. */
	public void testEvalError() throws Exception {
		datapoint("go", "boolean");
		datapoint("z", "unsigned");
		datapoint("ratio", "unsigned");
		load("[{\"id\": \"x\", \"name\": \"X\", \"reactions\": [" + onUpdate("go") + "\"actions\": ["
			+ write("ratio", "{\"kind\": \"binary\", \"op\": \"/\", \"left\": {\"kind\": \"literal\", \"type\": "
				+ "\"integer\", \"value\": 10}, \"right\": " + dp("z") + "}")
			+ "]}]}]");
		_engine.start(StartReason.BOOT);
		_engine.drain();
		report("z", Val.ofInt(0));
		report("go", Val.ofBool(true));
		assertEquals(LogCode.EVAL_ERROR, status().lastErrorCode());
		assertFalse(status().resolved());
		report("z", Val.ofInt(5));
		report("go", Val.ofBool(true));
		assertTrue(status().resolved());
		assertEquals(1, status().errors());
	}

	/**
	 * A silence trigger's refusal is resolved when the trigger arms, not by another reaction running
	 * cleanly.
	 */
	public void testSilenceRefusalResolvedByArming() throws Exception {
		datapoint("t", "real");
		load("[{\"id\": \"x\", \"name\": \"X\", "
			+ "\"parameters\": [{\"id\": \"after\", \"label\": \"After\", \"type\": \"duration\", \"default\": 50}], "
			+ "\"reactions\": [{\"triggers\": [{\"kind\": \"silence\", \"datapoint\": \"t\", \"after\": "
			+ "{\"kind\": \"parameter\", \"id\": \"after\"}}], \"actions\": []}, "
			+ onUpdate("t") + "\"actions\": []}]}]");
		_engine.start(StartReason.BOOT);
		_engine.drain();
		assertEquals(LogCode.TIMER_REFUSED, status().lastErrorCode());
		assertFalse(status().resolved());
		report("t", Val.ofReal(1));
		assertFalse("The silence trigger is still not armed.", status().resolved());
		assertEquals(Engine.ParameterStatus.OK, _engine.setParameter("x", "after", Val.ofDuration(1000)));
		_engine.drain();
		assertTrue(status().resolved());
	}

	/**
	 * An error outside any reaction (a parameter refused through the API) is resolved by the rule's
	 * next reaction that runs without a problem.
	 */
	public void testErrorOutsideReaction() throws Exception {
		datapoint("go", "boolean");
		datapoint("out", "boolean");
		load("[{\"id\": \"x\", \"name\": \"X\", "
			+ "\"parameters\": [{\"id\": \"limit\", \"label\": \"Limit\", \"type\": \"integer\", \"default\": 5, "
			+ "\"min\": 0, \"max\": 10}], "
			+ "\"reactions\": [" + onUpdate("go") + "\"actions\": [" + write("out", TRUE) + "]}]}]");
		_engine.start(StartReason.BOOT);
		_engine.drain();
		assertNotSame(Engine.ParameterStatus.OK, _engine.setParameter("x", "limit", Val.ofInt(99)));
		_engine.drain();
		assertEquals(LogCode.PARAMETER_REFUSED, status().lastErrorCode());
		assertTrue(status().problem());
		report("go", Val.ofBool(true));
		assertTrue(status().resolved());
		assertEquals(1, status().errors());
	}

	public static Test suite() {
		return new TestSuite(TestErrorResolution.class);
	}

}

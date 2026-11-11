/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.asRoot;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.create;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.runtime.DatapointSettings;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.FunctionRoleCheck;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.RuntimeFunctions;

/**
 * Which connected objects make a group address writable (haumacher/ehome #139), and connections of
 * objects switched off by their device's parameters (haumacher/ehome #137).
 *
 * <p>
 * The flags: on the KNX test system, the device's own flags of the actuator's object 0 on 0/0/34
 * (its sending address, otherwise alone there) are set to each case of the decision documented in
 * {@link DatapointSettings}. The parameters: the fixture <code>TestDynamic.knxproj</code> places
 * every communication object of its device in a channel its parameter "Kanal Generic" switches
 * off; the objects stay connected to their addresses.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDatapointEffect extends BasicTestCase {

	private static final String PREFIX = "M-0008_A-10D7-11-A0E5-O000E_";

	public void testFlags() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject channelA = address(project, "0/0/34");
			List<TLObject> connections = GroupAddressConnections.connections(channelA);
			assertEquals(1, connections.size());
			TLObject connection = connections.get(0);
			TLObject connector = connection.tContainer();
			assertEquals("send", ((com.top_logic.model.TLClassifier) connection.tValueByName("kind")).getName());

			TLObject command = function(project, "switch", "Licht", Map.of("command", channelA));
			TLObject state = function(project, "sensor", "Zustand", Map.of("value", channelA));

			// An input (C W): writable, nothing reported.
			flags(connector, "CW");
			assertDerived(channelA, true, false);
			assertEquals(List.of(), FunctionRoleCheck.problems(command));
			assertEquals(List.of(I18NConstants.FUNCTION_ROLE_NO_SENDER__FUNCTION_ROLE_ADDRESS.getKey()),
				keys(FunctionRoleCheck.problems(state)));

			// An input reporting its value (C W T U, MDT: the stop of a blind, a key's LED) alone on its
			// first address: writable, though it transmits there.
			flags(connector, "CWTU");
			assertTrue(DatapointSettings.transmits(connection));
			assertDerived(channelA, true, false);
			assertEquals(List.of(), FunctionRoleCheck.problems(command));
			assertEquals(List.of(), FunctionRoleCheck.problems(state));

			// A state output (C R T): not writable, read on start.
			flags(connector, "CRT");
			assertDerived(channelA, false, true);
			assertEquals(List.of(I18NConstants.FUNCTION_ROLE_NO_RECEIVER__FUNCTION_ROLE_ADDRESS.getKey()),
				keys(FunctionRoleCheck.problems(command)));
			assertNotNull(RuntimeFunctions.functionWarning(command));

			// A value the bus may set and query (C R W T, MDT: "Block functions"): writable and read on start.
			flags(connector, "CRWT");
			assertDerived(channelA, true, true);

			// A sender keeping its own state in step (C W T, a Gira key's switching object): not writable.
			flags(connector, "CWT");
			assertDerived(channelA, false, false);
			// The Tastsensor's key 1 (C W T) alone on 0/0/1, as in the template of the test home.
			assertDerived(address(project, "0/0/1"), false, false);

			// The setting overrides the derivation.
			TestFunctionProposal.update(channelA, "runtimeWritable", Boolean.TRUE);
			assertTrue(DatapointSettings.writable(channelA));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public void testNotInEffect() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestDynamic.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			// GA-3 (0/0/3): the key's object 0 (C W T) sends on it.
			TLObject key = address(project, "0/0/3");
			TLObject connection = GroupAddressConnections.connections(key).get(0);
			TLObject device = GroupAddressConnections.device(connection);
			Interface placed = GroupAddressConnections.object(connection);
			TLObject press = function(project, "button", "Taste", Map.of("press", key));

			QueryExecutor objectProblem = ViewScripts.compile(ExprFormat.INSTANCE.getValue("expr",
				"deviceObjectProblem($device, $object)"), List.of("device", "object"));
			QueryExecutor deviceProblems = ViewScripts.compile(ExprFormat.INSTANCE.getValue("expr",
				"deviceConnectionProblems($device)"), List.of("device"));
			QueryExecutor senders = ViewScripts.compile(ExprFormat.INSTANCE.getValue("expr",
				"groupAddressSenders($address)"), List.of("address"));

			// In effect: the key sends, nothing to report.
			assertTrue(GroupAddressConnections.inEffect(connection));
			assertEquals(1, GroupAddressConnections.senders(key));
			assertNotNull(DatapointSettings.sender(key));
			assertEquals(List.of(), GroupAddressConnections.problems(key));
			assertEquals(List.of(), GroupAddressConnections.deviceProblems(device));
			assertNull(asRoot(() -> objectProblem.execute(device, placed)));
			assertEquals(List.of(), FunctionRoleCheck.problems(press));
			int connected = connectedObjects(device);
			assertTrue(connected > 1);

			// The channel switched off: the objects stay connected but are no longer in effect. Before the
			// commit, the configuration of the transaction counts.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				DeviceSettings.setValue(device, channelSwitch(), entry(channelSwitch(), 0));
				assertFalse(GroupAddressConnections.inEffect(connection));
				tx.commit();
			}
			assertFalse(GroupAddressConnections.inEffect(connection));
			assertFalse(DatapointSettings.communicates(connection));
			assertFalse(DatapointSettings.transmits(connection));
			assertFalse(DatapointSettings.accepts(connection));
			assertEquals(0, GroupAddressConnections.senders(key));
			assertEquals(Double.valueOf(0), asRoot(() -> senders.execute(key)));
			assertNull(DatapointSettings.sender(key));
			assertEquals(new DatapointSettings.Derived(false, false), DatapointSettings.derive(key));

			// A problem of the address and of the device, for each connected object.
			List<ResKey> problems = GroupAddressConnections.problems(key);
			assertEquals(1, problems.size());
			assertEquals(de.haumacher.ehome.programming.I18NConstants.WARNING_NOT_IN_EFFECT__OBJECT.getKey(),
				problems.get(0).getKey());
			assertTrue(GroupAddressConnections.problemText(key), GroupAddressConnections.problemText(key).contains(" 0 "));
			assertEquals(connected, GroupAddressConnections.deviceProblems(device).size());
			String objectText = (String) asRoot(() -> objectProblem.execute(device, placed));
			assertNotNull(objectText);
			assertTrue(objectText, objectText.contains("0/0/3"));
			String deviceText = (String) asRoot(() -> deviceProblems.execute(device));
			assertNotNull(deviceText);
			assertEquals(connected, deviceText.split("\n").length);
			// The check before a further connection compares only what is in effect, and adds nothing.
			assertEquals(List.of(), GroupAddressConnections.problemsWith(key, device, placed));

			// The function's press has no effective sender: a warning of the function and of a deployment.
			assertEquals(List.of(I18NConstants.FUNCTION_ROLE_NO_SENDER_SWITCHED_OFF__FUNCTION_ROLE_ADDRESS.getKey()),
				keys(FunctionRoleCheck.problems(press)));
			assertEquals(FunctionRoleCheck.problems(press).size(), FunctionRoleCheck.warnings(project).size());
			String warning = RuntimeFunctions.functionWarning(press);
			assertNotNull(warning);
			assertTrue(warning, warning.contains("0/0/3"));

			// Switched on again: all is well.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				DeviceSettings.setValue(device, channelSwitch(), entry(channelSwitch(), 1));
				tx.commit();
			}
			assertTrue(GroupAddressConnections.inEffect(connection));
			assertEquals(List.of(), GroupAddressConnections.problems(key));
			assertEquals(List.of(), FunctionRoleCheck.problems(press));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static void assertDerived(TLObject address, boolean writable, boolean readOnStart) {
		assertEquals(new DatapointSettings.Derived(writable, readOnStart), DatapointSettings.derive(address));
	}

	/**
	 * Sets the device's own flags of the connector: those named by their letters (C, R, W, T, U) are
	 * enabled, the others disabled.
	 */
	private static void flags(TLObject connector, String letters) {
		TLEnumeration enablement = (TLEnumeration) TLModelUtil.findType("ehome.common:Enablement");
		String[][] flags = { { "communication", "C" }, { "canRead", "R" }, { "canWrite", "W" },
			{ "transmission", "T" }, { "update", "U" } };
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (String[] flag : flags) {
				connector.tUpdateByName(flag[0],
					enablement.getClassifier(letters.contains(flag[1]) ? "Enabled" : "Disabled"));
			}
			tx.commit();
		}
	}

	private static TLObject function(TLObject project, String kind, String name, Map<String, TLObject> roles) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject function = create("ehome.runtime:Function");
			function.tUpdateByName("name", FunctionProposal.text(Map.of("de", name)));
			function.tUpdateByName("kind", FunctionProposal.classifier(kind));
			for (Map.Entry<String, TLObject> role : roles.entrySet()) {
				function.tUpdateByName(role.getKey(), role.getValue());
			}
			List<Object> functions = new ArrayList<>(FunctionProposal.functions(project));
			functions.add(function);
			project.tUpdateByName("functions", functions);
			tx.commit();
			return function;
		}
	}

	private static List<String> keys(List<ResKey> problems) {
		List<String> result = new ArrayList<>();
		for (ResKey problem : problems) {
			result.add(problem.getKey());
		}
		return result;
	}

	private static int connectedObjects(TLObject device) {
		int result = 0;
		for (Object connector : (Collection<?>) device.tValueByName("connectors")) {
			if (!((Collection<?>) ((TLObject) connector).tValueByName("connections")).isEmpty()) {
				result++;
			}
		}
		return result;
	}

	private static ParameterView channelSwitch() {
		for (TLObject view : instances("ehome.operational:ParameterView")) {
			if ((PREFIX + "P-9010_R-9010").equals(view.tValueByName("extId"))) {
				return (ParameterView) view;
			}
		}
		throw new AssertionError("No channel switch.");
	}

	private static EnumerationValue entry(ParameterView view, int value) {
		for (EnumerationValue entry : ((EnumerationType) view.getParameter().getType()).getValues()) {
			if (entry.getValue() == value) {
				return entry;
			}
		}
		throw new AssertionError("No entry " + value);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDatapointEffect.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

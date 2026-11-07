/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The function proposal (haumacher/ehome #43) on two projects besides the test system: the demo
 * (<code>samples/EHomeDemo.knxproj</code>), whose actuator channels switch sockets and whose
 * push-button sensor sends on the same addresses, and <code>TestDetails.knxproj</code>, with a
 * device placed in a room, an inactive object, untyped addresses and a key with two objects.
 */
@SuppressWarnings("javadoc")
public class TestFunctionProposalProbe extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	/**
	 * The demo: the actuator's channels are sockets (the addresses are named "Steckdose"); the keys
	 * of the push-button sensor send on the same addresses, the switches' commands, so they give no
	 * buttons (haumacher/ehome #92).
	 */
	public void testDemo() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject project = projects.get(0);
			assertEquals(3, apply(project).size());
			assertEquals(List.of("switch command=0/0/1", "switch command=0/0/2", "switch command=0/0/3"),
				summary(project));
			// Each named after its address (haumacher/ehome #98); the keys are linked with the sockets, none
			// is unused; the demo places no device.
			assertEquals(List.of(
				"Steckdose 1 | switch | command=0/0/1 | - | used",
				"Steckdose 2 | switch | command=0/0/2 | - | used",
				"Steckdose 3 | switch | command=0/0/3 | - | used"), TestFunctionLinks.table(project));
			assertEquals(List.of(), apply(project));
			valid(project);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * TestDetails: the keys of the device in the kitchen become buttons in the kitchen, named after
	 * the address they press on (haumacher/ehome #98), the one with a short and a long press object
	 * pressing on the lower address; the inactive temperature object and the objects on untyped
	 * addresses get no function. The three keys connected to no address are unused buttons, where
	 * their devices are.
	 */
	public void testDetails() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestDetails.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject project = projects.get(0);
			List<TLObject> added = apply(project);
			assertEquals(List.of("button press=0/0/3", "button press=0/1/1", "button press=0/1/2", "button press=0/1/3",
				"button unused", "button unused", "button unused"), summary(project));
			for (TLObject function : added) {
				if (FunctionProposal.unused(function)) {
					// Without senders: where its device is.
					assertSame(((TLObject) function.tValueByName("device")).tValueByName("location"),
						function.tValueByName("room"));
				} else {
					assertEquals("Küche", ((TLObject) function.tValueByName("room")).tValueByName("name"));
				}
			}
			Map<?, ?> image = valid(project);
			List<?> functions = (List<?>) image.get("functions");
			assertEquals("P-01A0-0_BP-3", ((Map<?, ?>) functions.get(0)).get("room"));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * TestBcu1 (haumacher/ehome #50): two devices of an artificial BCU 1 program whose objects have no
	 * datapoint type, the input "Schalten" (1 bit) and the output "Rückmeldung" (1 bit), on untyped
	 * addresses. The input is a switching command by its name, so the devices get a light; both
	 * switch on the same address, so they are linked into one (haumacher/ehome #98), named after the
	 * address. The feedback alone is no function.
	 */
	public void testUntypedSwitch() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestBcu1.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject project = projects.get(0);
			List<TLObject> added = apply(project);
			assertEquals(List.of("light command=0/0/3"), summary(project));
			assertEquals("GA3", TestFunctionProposal.name(added.get(0)));
			assertEquals(List.of(), apply(project));
			valid(project);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static Map<?, ?> valid(TLObject project) {
		RuntimeImage image = RuntimeImage.build(project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		return image.getJson();
	}

	/**
	 * The kinds and role bindings of the project's functions ("unused" for an unused function),
	 * sorted.
	 */
	private static List<String> summary(TLObject project) {
		List<String> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			StringBuilder roles = new StringBuilder();
			for (String role : List.of("command", "state", "press", "value")) {
				if (function.tValueByName(role) instanceof TLObject address) {
					if (roles.length() > 0) {
						roles.append(", ");
					}
					roles.append(role).append('=')
						.append(RuntimeImage.threeLevel(((GA) address.tValueByName("address")).getValue()));
				}
			}
			result.add(FunctionProposal.kind(function) + " " + (FunctionProposal.unused(function) ? "unused" : roles));
		}
		Collections.sort(result);
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionProposalProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

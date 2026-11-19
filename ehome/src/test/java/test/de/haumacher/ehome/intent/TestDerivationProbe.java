/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.intent;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.intent.IntentFixture.control;
import static test.de.haumacher.ehome.intent.IntentFixture.create;
import static test.de.haumacher.ehome.intent.IntentFixture.device;
import static test.de.haumacher.ehome.intent.IntentFixture.function;
import static test.de.haumacher.ehome.intent.IntentFixture.inTransaction;
import static test.de.haumacher.ehome.intent.IntentFixture.named;

import java.util.List;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.intent.Derivation;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.GroupAddressDefinition;
import de.haumacher.ehome.model.logical.GroupRange;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * Probe of the derivation (haumacher/ehome #147) in a project that already has addresses made by hand:
 * the main range of the kind exists with 1/0/0 taken and the identifier "licht" in use. The derivation
 * takes the existing range instead of a second one, allocates around the taken address, gives a unique
 * identifier and leaves the hand-made address as it is; a second derivation is empty.
 */
@SuppressWarnings("javadoc")
public class TestDerivationProbe extends BasicTestCase {

	private IntentFixture _f;

	public void testProbe() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		try {
			List<TLObject> imported = SharedData.newProjects(before);
			_f = IntentFixture.create(device(imported, 1, 3, 5), device(imported, 1, 3, 50));
			TLObject project = _f.project("Probe");
			TLObject[] devices = _f.planDevices(project, "Aktor", "Taster");
			GroupAddressDefinition[] hand = { null, null };
			inTransaction(() -> {
				GroupRange main = (GroupRange) create("ehome.logical:GroupRange");
				main.setName("Licht von Hand");
				main.setRangeStart(new GA(1 << 11, null));
				main.setRangeEnd(new GA((2 << 11) - 1, null));
				((Project) project).addGroupAddresse(main);
				GroupAddress taken = (GroupAddress) create("ehome.logical:GroupAddress");
				taken.setAddress(new GA(1 << 11, null));
				taken.setName("Fremd");
				taken.setIdentifier("licht");
				main.addPart(taken);
				hand[0] = main;
				hand[1] = taken;
			});
			inTransaction(() -> FunctionProposal.apply(project));
			Function licht = (Function) named(function(project, devices[0], _f.channelA), "Licht", _f.schalten);
			control(licht, devices[1], _f.key1, _f.toggle, "toggle");

			Derivation plan = Derivation.plan(project);
			assertEquals(List.of(), plan.getRefusals());
			inTransaction(() -> Derivation.apply(project, plan.fingerprint()));

			GroupAddress command = (GroupAddress) licht.getCommand();
			GroupAddress state = (GroupAddress) licht.getState();
			assertNotNull(command);
			assertNotNull(state);
			assertNotSame(hand[1], command);
			assertNotSame(hand[1], state);
			int c = command.getAddress().getValue();
			int s = state.getAddress().getValue();
			assertTrue(c != (1 << 11) && s != (1 << 11) && c != s);
			assertTrue(c >> 11 == 1 && s >> 11 == 1);
			assertFalse("licht".equals(command.getIdentifier()));
			assertFalse(String.valueOf(command.getIdentifier()).equals(state.getIdentifier()));
			GroupAddress taken = (GroupAddress) hand[1];
			assertEquals("Fremd", taken.getName());
			assertEquals("licht", taken.getIdentifier());
			assertEquals(1 << 11, taken.getAddress().getValue());

			// One main range of the kind, the hand-made one.
			long mains = instances("ehome.logical:GroupRange").stream()
				.filter(r -> r.tContainer() == project)
				.filter(r -> ((GroupRange) r).getRangeStart().getValue() == 1 << 11).count();
			assertEquals(1, mains);

			assertTrue(Derivation.plan(project).getItems().stream()
				.allMatch(i -> i.action() == Derivation.Action.KEEP));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
			if (_f != null) {
				_f.cleanUp();
			}
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDerivationProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.intent;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.intent.IntentFixture.add;
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
			TLObject[] hand = { null, null };
			inTransaction(() -> {
				TLObject main = create("ehome.logical:GroupRange");
				main.tUpdateByName("name", "Licht von Hand");
				main.tUpdateByName("rangeStart", new GA(1 << 11, null));
				main.tUpdateByName("rangeEnd", new GA((2 << 11) - 1, null));
				add(project, "groupAddresses", main);
				TLObject taken = create("ehome.logical:GroupAddress");
				taken.tUpdateByName("address", new GA(1 << 11, null));
				taken.tUpdateByName("name", "Fremd");
				taken.tUpdateByName("identifier", "licht");
				add(main, "parts", taken);
				hand[0] = main;
				hand[1] = taken;
			});
			inTransaction(() -> FunctionProposal.apply(project));
			TLObject licht = named(function(project, devices[0], _f.channelA), "Licht", _f.schalten);
			control(licht, devices[1], _f.key1, _f.toggle, "toggle");

			Derivation plan = Derivation.plan(project);
			assertEquals(List.of(), plan.getRefusals());
			inTransaction(() -> Derivation.apply(project, plan.fingerprint()));

			TLObject command = (TLObject) licht.tValueByName("command");
			TLObject state = (TLObject) licht.tValueByName("state");
			assertNotNull(command);
			assertNotNull(state);
			assertNotSame(hand[1], command);
			assertNotSame(hand[1], state);
			int c = ((GA) command.tValueByName("address")).getValue();
			int s = ((GA) state.tValueByName("address")).getValue();
			assertTrue(c != (1 << 11) && s != (1 << 11) && c != s);
			assertTrue(c >> 11 == 1 && s >> 11 == 1);
			assertFalse("licht".equals(command.tValueByName("identifier")));
			assertFalse(String.valueOf(command.tValueByName("identifier")).equals(state.tValueByName("identifier")));
			assertEquals("Fremd", hand[1].tValueByName("name"));
			assertEquals("licht", hand[1].tValueByName("identifier"));
			assertEquals(1 << 11, ((GA) hand[1].tValueByName("address")).getValue());

			// One main range of the kind, the hand-made one.
			long mains = instances("ehome.logical:GroupRange").stream()
				.filter(r -> r.tContainer() == project)
				.filter(r -> ((GA) r.tValueByName("rangeStart")).getValue() == 1 << 11).count();
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

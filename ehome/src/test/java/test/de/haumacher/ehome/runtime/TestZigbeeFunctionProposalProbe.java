/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.FunctionProposal.Proposal;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;

/**
 * Probe of the Zigbee function proposals (haumacher/ehome #68): a device with the same function on
 * two endpoints (a two-gang socket, a sensor with two temperature endpoints) gets two proposals that
 * are told apart by name, each dismissed on its own.
 */
@SuppressWarnings("javadoc")
public class TestZigbeeFunctionProposalProbe extends BasicTestCase {

	public void testTwoEndpoints() throws Exception {
		TLObject project = TestRuleAuthoring.createProject();
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.parse("""
					{"format": "homecontrol-zigbee-export", "version": 1, "devices": [
						{"ieee": "00000000000000b1", "label": "Doppelsteckdose", "interviewed": true, "functions": [
							{"function": "on_off", "endpoint": 1, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true},
							{"function": "on_off", "endpoint": 2, "cluster": 6, "attribute": 0, "type": "boolean", "writable": true}]},
						{"ieee": "00000000000000b2", "label": "Klima", "interviewed": true, "functions": [
							{"function": "temperature", "endpoint": 1, "cluster": 1026, "attribute": 0, "type": "real", "unit": "°C"},
							{"function": "temperature", "endpoint": 2, "cluster": 1026, "attribute": 0, "type": "real", "unit": "°C"}]}
					 ]}"""));
				for (TLObject device : ZigbeeImport.devices(project)) {
					for (Object function : (Collection<?>) device.tValueByName("functions")) {
						ZigbeeBindings.createDatapoint((TLObject) function);
					}
				}
				tx.commit();
			}

			List<Proposal> proposals = FunctionProposal.propose(project);
			assertEquals(4, proposals.size());
			for (String language : List.of("de", "en")) {
				Set<String> names = new HashSet<>();
				for (Proposal proposal : proposals) {
					assertTrue("Two proposals named alike in " + language + ": " + proposal.label().get(language),
						names.add(proposal.label().get(language)));
				}
			}

			// Dismissing the first endpoint's switch keeps the second one's.
			List<TLObject> added = apply(project);
			assertEquals(4, added.size());
			TLObject first = added.stream().filter(f -> f.tValueByName("channel").toString().endsWith(":1:on_off"))
				.findFirst().orElseThrow();
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				// Deletes the function, as the view's delete does.
				FunctionProposal.dismiss(first);
				tx.commit();
			}
			assertEquals(List.of(), apply(project));
			assertEquals(3, FunctionProposal.functions(project).size());
			assertTrue(FunctionProposal.functions(project).stream()
				.anyMatch(f -> f.tValueByName("channel").toString().endsWith(":2:on_off")));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeFunctionProposalProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

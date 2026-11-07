/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;

import java.util.List;
import java.util.Map;

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

/**
 * A deleted proposed function stays dismissed (haumacher/ehome #49): on the demo project
 * (<code>samples/EHomeDemo.knxproj</code>, three sockets; the keys sending on their commands give
 * no buttons, haumacher/ehome #92), deleting a proposed socket records a dismissal for its device,
 * channel and kind, the next proposals do not bring it back (nor a button for the key switching
 * it), deleting a function the user made records nothing, and deleting the dismissal brings the
 * proposal back with the same name.
 */
@SuppressWarnings("javadoc")
public class TestFunctionDismissal extends BasicTestCase {

	public void testDismissal() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject project = projects.get(0);
			assertEquals(3, apply(project).size());
			TLObject socket = FunctionProposal.functions(project).stream()
				.filter(f -> "switch".equals(FunctionProposal.kind(f))).findFirst().orElseThrow();
			Object name = socket.tValueByName("name");
			Object device = socket.tValueByName("device");
			Object channel = socket.tValueByName("channel");
			assertNotNull(device);
			assertNotNull(channel);

			TLObject dismissal = dismiss(socket);
			assertNotNull(dismissal);
			assertFalse(socket.tValid());
			assertEquals(List.of(dismissal), dismissals(project));
			assertEquals(name, dismissal.tValueByName("name"));
			assertEquals("switch", FunctionProposal.kind(dismissal));
			assertSame(device, dismissal.tValueByName("device"));
			assertEquals(channel, dismissal.tValueByName("channel"));
			assertEquals(2, FunctionProposal.functions(project).size());

			// Two more runs of the proposal: the socket stays away, the dismissal stays.
			assertEquals(List.of(), apply(project));
			assertEquals(List.of(), apply(project));
			assertEquals(2, FunctionProposal.functions(project).size());
			assertEquals(List.of(dismissal), dismissals(project));

			// A function the user made is deleted without a dismissal.
			TLObject own;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				own = TestFunctionProposal.create("ehome.runtime:Function");
				own.tUpdateByName("name", FunctionProposal.text(Map.of("de", "Klingel")));
				own.tUpdateByName("kind", FunctionProposal.classifier("button"));
				List<TLObject> functions = FunctionProposal.functions(project);
				functions.add(own);
				project.tUpdateByName("functions", functions);
				tx.commit();
			}
			assertNull(dismiss(own));
			assertEquals(List.of(dismissal), dismissals(project));

			// Undone: the next proposal brings the socket back, named as before.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				dismissal.tDelete();
				tx.commit();
			}
			assertEquals(List.of(), dismissals(project));
			List<TLObject> back = apply(project);
			assertEquals(1, back.size());
			assertEquals("switch", FunctionProposal.kind(back.get(0)));
			assertSame(device, back.get(0).tValueByName("device"));
			assertEquals(channel, back.get(0).tValueByName("channel"));
			assertEquals(name, back.get(0).tValueByName("name"));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static TLObject dismiss(TLObject function) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject result = FunctionProposal.dismiss(function);
			tx.commit();
			return result;
		}
	}

	private static List<?> dismissals(TLObject project) {
		return List.copyOf((java.util.Collection<?>) project.tValueByName(FunctionProposal.DISMISSED));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionDismissal.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

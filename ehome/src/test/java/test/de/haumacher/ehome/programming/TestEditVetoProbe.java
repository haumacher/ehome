/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.importer.TestEtsImport;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * Probe: every editable parameter an imported device shows (of the Test project, and of the ETS 6 shaped
 * project with dynamic modules) can be changed by <code>root</code> with the access checks on, as the editor decides it
 * (<code>device-item.view.xml</code>: <code>canWrite</code> and <code>deviceEditVeto</code>, evaluated in
 * TL-Script), so that the editor shows an input, not the value as text.
 */
@SuppressWarnings("javadoc")
public class TestEditVetoProbe extends BasicTestCase {

	public void testEditable() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			TestEtsImport.importSample("samples/Test.knxproj");
			TestEtsImport.importSample("src/test/fixtures/TestModules.knxproj");
			List<String> vetoes = new ArrayList<>();
			int editable = 0;
			QueryExecutor canWriteScript = compile("d -> canWrite($d)");
			QueryExecutor vetoScript = compile("d -> v -> deviceEditVeto($d, $v)");
			for (TLObject device : SharedData.within("ehome.physical:Device", SharedData.newProjects(before))) {
				DeviceConfiguration configuration = new DeviceConfiguration(device);
				for (Option item : configuration.getShownItems().keySet()) {
					if (!(item instanceof ParameterView view) || !DeviceConfiguration.isEditable(view)) {
						continue;
					}
					ResKey veto = asRoot(() -> DeviceSettings.editVeto(device, view));
					Object canWrite = asRoot(() -> canWriteScript.execute(device));
					Object scriptVeto = asRoot(() -> vetoScript.execute(device, view));
					if (veto == null && Boolean.TRUE.equals(canWrite) && scriptVeto == null) {
						editable++;
					} else {
						vetoes.add((veto == null ? null : Resources.getInstance(Locale.ENGLISH).getString(veto)) + " canWrite="
							+ canWrite + " script=" + scriptVeto);
					}
				}
			}
			assertEquals(vetoes.toString(), List.of(), vetoes);
			assertTrue(editable > 0);

			// A block's access "Read" does not make its parameters read-only (the program STYA662AN
			// declares nearly all its blocks so, and its parameters are edited in ETS); "None" (a hidden
			// block) does. Changed in a transaction that is rolled back: the program is shared data.
			ParameterView inBlock = null;
			for (TLObject device : SharedData.within("ehome.physical:Device", SharedData.newProjects(before))) {
				for (Option item : new DeviceConfiguration(device).getShownItems().keySet()) {
					if (item instanceof ParameterView view && DeviceConfiguration.isEditable(view)
						&& view.tContainer() instanceof de.haumacher.ehome.model.operational.ParameterGroup) {
						inBlock = view;
						break;
					}
				}
				if (inBlock != null) {
					break;
				}
			}
			assertNotNull("No editable parameter in a block.", inBlock);
			TLObject block = inBlock.tContainer();
			try (com.top_logic.knowledge.service.Transaction tx =
				com.top_logic.knowledge.service.PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				block.tUpdateByName("access", accessKind("Read"));
				assertTrue("A block's access Read made its parameter read-only.", DeviceConfiguration.isEditable(inBlock));
				block.tUpdateByName("access", accessKind("None"));
				assertFalse("A parameter of a hidden block is editable.", DeviceConfiguration.isEditable(inBlock));
				tx.rollback();
			}
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}


	private static Object accessKind(String name) {
		return ((com.top_logic.model.TLEnumeration) com.top_logic.model.util.TLModelUtil
			.findType("ehome.operational:AccessKind")).getClassifier(name);
	}

	private static QueryExecutor compile(String expr) {
		try {
			return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr", expr));
		} catch (com.top_logic.basic.config.ConfigurationException ex) {
			throw new AssertionError(ex);
		}
	}

	/**
	 * As root in a person context with the access checks on, as the view evaluates it.
	 */
	private static <T> T asRoot(java.util.function.Supplier<T> value) {
		Object[] result = { null };
		ThreadContext.withSecurityChecks(true, () -> {
			result[0] = TLContextManager.inPersonContext(PersonManager.getManager().getRoot(), value::get);
		});
		@SuppressWarnings("unchecked")
		T typed = (T) result[0];
		return typed;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEditVetoProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

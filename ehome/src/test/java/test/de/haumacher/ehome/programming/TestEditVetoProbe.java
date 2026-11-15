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
import test.de.haumacher.ehome.security.AccountFixture;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.tool.boundsec.simple.CommandGroupRegistry;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;

/**
 * Probe: every editable parameter an imported device shows (of the Test project, and of the ETS 6 shaped
 * project with dynamic modules) can be changed by an editor of the project with the access checks on, as the editor
 * decides it (<code>device-item.view.xml</code>: <code>canWrite</code> and <code>deviceEditVeto</code>, evaluated
 * in TL-Script), so that the editor shows an input, not the value as text. The editor is an account of E-Home, not
 * the technical administrator, whom no function of E-Home relies on (haumacher/ehome #165).
 */
@SuppressWarnings("javadoc")
public class TestEditVetoProbe extends BasicTestCase {

	public void testEditable() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			TestEtsImport.importSample("samples/Test.knxproj");
			TestEtsImport.importSample("src/test/fixtures/TestModules.knxproj");
			List<TLObject> projects = SharedData.newProjects(before);
			Person editor = editorOf(projects);
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
					ResKey veto = asEditor(editor, () -> DeviceSettings.editVeto(device, view));
					Object canWrite = asEditor(editor, () -> canWriteScript.execute(device));
					Object scriptVeto = asEditor(editor, () -> vetoScript.execute(device, view));
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
			ThreadContext.withoutSecurityChecks(() -> {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					Person editor = Person.byName(EDITOR);
					if (editor != null) {
						editor.tDelete();
					}
					tx.commit();
				}
			});
		}
	}

	private static final String EDITOR = "ehomeTestVetoEditor";

	/**
	 * A new account, editor of the given projects.
	 */
	private static Person editorOf(List<TLObject> projects) {
		Person[] result = { null };
		ThreadContext.withoutSecurityChecks(() -> {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				result[0] = AccountFixture.person(EDITOR, null);
				for (TLObject project : projects) {
					ProjectMembers.add(project, result[0], Role.editor);
				}
				tx.commit();
			}
		});
		return result[0];
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
	 * As the given account in a person context with the access checks on, as the view evaluates it.
	 */
	private static <T> T asEditor(Person editor, java.util.function.Supplier<T> value) {
		Object[] result = { null };
		ThreadContext.withSecurityChecks(true, () -> {
			result[0] = TLContextManager.inPersonContext(editor, value::get);
		});
		@SuppressWarnings("unchecked")
		T typed = (T) result[0];
		return typed;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEditVetoProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE, InitialRolesManager.Module.INSTANCE,
			InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE, SecurityConfigurationService.Module.INSTANCE,
			CommandGroupRegistry.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.PluginRequirements;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * Test case for the programs that need what ETS has besides their product data (ETS:
 * <code>Static/Extension</code>, haumacher/ehome #159), with the artificial product file
 * <code>TestPlugins.knxprod</code> (made by <code>src/test/fixtures/make-fixtures.py</code>): a user
 * interface plug-in only, a download plug-in (with a user interface plug-in), and a data handler with
 * required external software.
 *
 * <p>
 * The import reads the requirements and reports them; the notice names them and says what E-Home does;
 * a device planned with a program needing anything but a user interface plug-in is refused the
 * download with that reason, while its image (and so the comparison with the device) is computed; a
 * program an earlier import read without its requirements is completed by the next import of its
 * file.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPluginRequirements extends BasicTestCase {

	private static final String FILE = "src/test/fixtures/TestPlugins.knxprod";

	private static final String UI = "M-0008_A-FFA2-01-0000";

	private static final String DOWNLOAD = "M-0008_A-FFA3-01-0000";

	private static final String DATA = "M-0008_A-FFA4-01-0000";

	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	/** The messages of the first import of the file, shared by the tests. */
	private static List<BufferingI18NLog.Entry> _messages;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		if (_messages == null) {
			assertTrue("Fixture already imported.", TestValueForms.withExtId(PROGRAM_TYPE, UI).isEmpty());
			_messages = importSample(FILE);
		}
	}

	/**
	 * The requirements of each program are read from the attributes of its extension, and the program
	 * is marked read.
	 */
	public void testRequirementsRead() {
		assertEquals(List.of("UserInterfacePlugin"), names(program(UI)));
		assertEquals(List.of("UserInterfacePlugin", "DownloadPlugin"), names(program(DOWNLOAD)));
		assertEquals(List.of("DataHandler", "ExternalSoftware"), names(program(DATA)));
		for (String id : List.of(UI, DOWNLOAD, DATA)) {
			assertEquals(id, Boolean.TRUE, program(id).tValueByName("requirementsRead"));
		}
		assertFalse(PluginRequirements.refusesDownload(PluginRequirements.requirements(program(UI))));
		assertTrue(PluginRequirements.refusesDownload(PluginRequirements.requirements(program(DOWNLOAD))));
		assertTrue(PluginRequirements.refusesDownload(PluginRequirements.requirements(program(DATA))));
	}

	/**
	 * The import reports a user interface plug-in as a note, every other requirement as the warning
	 * that E-Home cannot download the program.
	 */
	public void testImportMessages() {
		assertEquals(List.of(
			"INFO The application program \"" + UI + "\" uses a plug-in of the manufacturer in ETS (User interface "
				+ "plug-in). E-Home edits and downloads what its product data declare, without the functions of the "
				+ "plug-in.",
			"WARN The application program \"" + DOWNLOAD + "\" needs in ETS what E-Home does not run (User interface "
				+ "plug-in, Download plug-in): its devices can be planned, parameterized and connected, but E-Home "
				+ "cannot download them.",
			"WARN The application program \"" + DATA + "\" needs in ETS what E-Home does not run (Data handler, "
				+ "External software): its devices can be planned, parameterized and connected, but E-Home cannot "
				+ "download them."),
			requirementMessages(_messages));
	}

	/**
	 * The notice of each program in English and German, also reached through its program assignment;
	 * a program needing nothing has none.
	 */
	public void testNotices() {
		assertEquals("In ETS, the application program uses a plug-in of the manufacturer (User interface plug-in). "
			+ "E-Home edits and downloads the parameters and communication objects its product data declare; the "
			+ "functions the plug-in adds, such as the commissioning of a DALI bus, are not available in E-Home.",
			english(PluginRequirements.notice(program(UI))));
		assertEquals("In ETS, the application program needs what E-Home does not run: User interface plug-in, "
			+ "Download plug-in. A device of it is planned, parameterized and connected in E-Home, but E-Home cannot "
			+ "download it.",
			english(PluginRequirements.notice(program(DOWNLOAD))));
		assertEquals("In ETS, the application program needs what E-Home does not run: Data handler, External "
			+ "software. A device of it is planned, parameterized and connected in E-Home, but E-Home cannot download "
			+ "it.",
			english(PluginRequirements.notice(program(DATA))));
		assertEquals("In der ETS benötigt das Anwendungsprogramm, was E-Home nicht ausführt: Daten-Handler, "
			+ "Externe Software. Ein Gerät damit wird in E-Home geplant, parametriert und verbunden, aber E-Home kann "
			+ "es nicht laden.",
			german(PluginRequirements.notice(program(DATA))));
		assertEquals("In der ETS verwendet das Anwendungsprogramm ein Plug-in des Herstellers (Plug-in der "
			+ "Bedienoberfläche). E-Home bearbeitet und lädt die Parameter und Kommunikationsobjekte, die seine "
			+ "Produktdaten beschreiben; die Funktionen, die das Plug-in hinzufügt, etwa die Inbetriebnahme eines "
			+ "DALI-Busses, bietet E-Home nicht.",
			german(PluginRequirements.notice(program(UI))));

		TLObject assignment = TestValueForms.byExtId("ehome.catalog:ProgramAssignment",
			"M-0008_H-TEST.20PLUGIN.20DOWNLOAD-1_HP-FFA3-01-0000");
		assertSame(program(DOWNLOAD), PluginRequirements.program(assignment));
		assertNull(PluginRequirements.notice(null));
	}

	/**
	 * A device planned with each program: the download plug-in and the data handler refuse the
	 * download (and the download replacing the layout) naming the requirements, the user interface
	 * plug-in does not; the image is computed for all, so the comparison stays allowed, and the
	 * individual address can be programmed.
	 */
	public void testDownloadVeto() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			TLObject line = firstLine(project);
			TLObject ui;
			TLObject download;
			TLObject data;
			try (Transaction tx = kb.beginTransaction()) {
				ui = plan(line, "M-0008_H-TEST.20PLUGIN.20UI-1_P-TEST.20Plugin.20UI", 201);
				download = plan(line, "M-0008_H-TEST.20PLUGIN.20DOWNLOAD-1_P-TEST.20Plugin.20Download", 202);
				data = plan(line, "M-0008_H-TEST.20PLUGIN.20DATEN-1_P-TEST.20Plugin.20Daten", 203);
				tx.commit();
			}
			// A device of a program needing nothing has no notice.
			TLObject plain = devices(project).stream().filter(d -> !List.of(ui, download, data).contains(d))
				.findFirst().orElseThrow();
			assertNotNull(PluginRequirements.program(plain));
			assertNull(PluginRequirements.notice(PluginRequirements.program(plain)));
			assertSame(program(UI), PluginRequirements.program(ui));
			assertEquals(english(PluginRequirements.notice(program(DOWNLOAD))),
				english(PluginRequirements.notice(PluginRequirements.program(download))));

			DownloadPlan uiPlan = DownloadPlanner.plan(ui, DownloadPlan.Kind.FULL);
			assertTrue(uiPlan.getProblems().toString(), uiPlan.isExecutable());

			for (TLObject refused : List.of(download, data)) {
				Program program = PluginRequirements.program(refused);
				ResKey reason = PluginRequirements.downloadProblem(program);
				assertNotNull(reason);
				for (DownloadPlan.Kind kind : List.of(DownloadPlan.Kind.FULL, DownloadPlan.Kind.LAYOUT)) {
					DownloadPlan plan = DownloadPlanner.plan(refused, kind);
					assertFalse(plan.isExecutable());
					assertTrue(plan.getProblems().toString(), plan.getProblems().contains(reason));
				}
				assertNotNull("The comparison needs the image.", DownloadPlanner.plan(refused, DownloadPlan.Kind.FULL).getImage());
				assertTrue(DownloadPlanner.plan(refused, DownloadPlan.Kind.ADDRESS).isExecutable());
			}
			assertEquals("In ETS, the application program needs what E-Home does not run: User interface plug-in, "
				+ "Download plug-in. E-Home cannot download it.",
				english(PluginRequirements.downloadProblem(PluginRequirements.program(download))));
			assertNull(PluginRequirements.downloadProblem(PluginRequirements.program(ui)));
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				if (project.tValid()) {
					project.tDelete();
				}
				tx.commit();
			}
		}
	}

	/**
	 * A program an earlier import read without its requirements (before haumacher/ehome #159) gains them
	 * from the next import of its file, which reports them; a further import adds nothing.
	 */
	public void testKnownProgramCompleted() throws Exception {
		Program program = program(DATA);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			program.tUpdateByName(PluginRequirements.REQUIREMENTS, Set.of());
			program.tUpdateByName("requirementsRead", null);
			tx.commit();
		}
		assertEquals(List.of(), names(program));

		List<BufferingI18NLog.Entry> again = importSample(FILE);
		assertEquals(List.of("DataHandler", "ExternalSoftware"), names(program));
		assertEquals(Boolean.TRUE, program.tValueByName("requirementsRead"));
		assertEquals(List.of("WARN The application program \"" + DATA + "\" needs in ETS what E-Home does not run "
			+ "(Data handler, External software): its devices can be planned, parameterized and connected, but "
			+ "E-Home cannot download them."), requirementMessages(again));

		assertEquals(List.of(), requirementMessages(importSample(FILE)));
		assertEquals(List.of("DataHandler", "ExternalSoftware"), names(program));
	}

	private static TLObject plan(TLObject line, String variantId, int address) {
		TLObject variant = TestValueForms.byExtId("ehome.catalog:Variant", variantId);
		return DevicePlanning.create(line, variant, null, Integer.valueOf(address), null, null);
	}

	private static TLObject firstLine(TLObject project) {
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				return (TLObject) line;
			}
		}
		fail("No line.");
		return null;
	}

	private static List<TLObject> devices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					result.add((TLObject) device);
				}
			}
		}
		return result;
	}

	private static Program program(String id) {
		return (Program) TestValueForms.byExtId(PROGRAM_TYPE, id);
	}

	private static List<String> names(Program program) {
		return PluginRequirements.requirements(program).stream().map(TLClassifier::getName).toList();
	}

	/**
	 * The messages about requirements, in English, with their level.
	 */
	private static List<String> requirementMessages(List<BufferingI18NLog.Entry> messages) {
		List<String> result = new ArrayList<>();
		String uses = english(I18NConstants.INFO_PROGRAM_USES_PLUGIN__PROGRAM_REQUIREMENTS.fill("", ""));
		String refused = english(I18NConstants.WARN_PROGRAM_NOT_DOWNLOADABLE__PROGRAM_REQUIREMENTS.fill("", ""));
		for (BufferingI18NLog.Entry entry : messages) {
			// Without the position in the file the import puts before a message of the mapping.
			String text = english(entry.getMessage()).replaceFirst("^In knxproj:[^:]*: ", "");
			if (text.endsWith(uses.substring(uses.lastIndexOf(')'))) || text.endsWith(refused.substring(refused.lastIndexOf(')')))) {
				result.add((entry.getLevel() == Level.WARN ? "WARN " : "INFO ") + text);
			}
		}
		return result;
	}

	private static String english(ResKey key) {
		return ResourcesModule.getInstance().getBundle(Locale.ENGLISH).getString(key);
	}

	private static String german(ResKey key) {
		return ResourcesModule.getInstance().getBundle(Locale.GERMAN).getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestPluginRequirements.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

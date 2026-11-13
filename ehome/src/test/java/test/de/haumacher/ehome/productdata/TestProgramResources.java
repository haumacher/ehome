/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.html.SafeHTML;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.logging.Level;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.ProgramResources;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;

/**
 * Test case for the icons and help texts of an application program (ETS: <code>IconFile</code>,
 * <code>Icon</code>, <code>ContextHelpFile</code>, <code>HelpContext</code>, haumacher/ehome #156,
 * #157) with the fixtures <code>TestResources.knxprod</code> and
 * <code>TestResourcesNewer.knxprod</code> (<code>src/test/fixtures/make-fixtures.py</code>).
 *
 * <p>
 * The import reads the archives of the program and the names of its elements, unpacks the archives
 * and warns once about the icon its archive lacks and the topic without a text; the functions of
 * the views give the icons (not the SVG) and the help per language with the fallback to the default
 * language, formatted as safe HTML; a second import adds nothing, a program read before without
 * them is completed, and a newer icon archive replaces the icons. A project file holding the program
 * and the archives reads them the same way.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestProgramResources extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestResources.knxprod";

	private static final String NEWER = "src/test/fixtures/TestResourcesNewer.knxprod";

	private static final String PROGRAM = "M-0008_A-FFB5-01-0000";

	private static final String PROJECT_FIXTURE = "src/test/fixtures/TestResources.knxproj";

	private static final String PROJECT_PROGRAM = "M-0008_A-FFB6-01-0000";

	private static final String ICONS = "M-0008_BG--TEST.5FSymbole.2Ezip";

	public void testResources() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<BufferingI18NLog.Entry> messages = importProduct(FIXTURE);
		TLObject program = byExtId("ehome.operational:Program", PROGRAM);

		// The archives the program names, unpacked.
		TLObject icons = (TLObject) program.tValueByName("iconFile");
		assertEquals(ICONS, icons.tValueByName("extId"));
		assertEquals(Boolean.TRUE, icons.tValueByName("unpacked"));
		assertEquals(List.of("TEST_Aus.png", "TEST_Ein.png", "TEST_Kanal.png", "TEST_Block.png", "TEST_Vektor.svg"),
			names(icons));
		List<?> helpFiles = (List<?>) program.tValueByName("helpFiles");
		assertEquals(List.of("de:true:TEST_Hilfe_DE.zip", "en:false:TEST_Hilfe_EN.zip"),
			helpFiles.stream().map(TLObject.class::cast).map(f -> f.tValueByName("language") + ":"
				+ f.tValueByName("fallback") + ":" + ((TLObject) f.tValueByName("file")).tValueByName("name")).toList());

		// The names of the elements.
		TLObject off = byExtId("ehome.operational:EnumerationValue", PROGRAM + "_PT-Betriebsart_EN-0");
		TLObject on = byExtId("ehome.operational:EnumerationValue", PROGRAM + "_PT-Betriebsart_EN-1");
		TLObject stairs = byExtId("ehome.operational:EnumerationValue", PROGRAM + "_PT-Betriebsart_EN-2");
		TLObject unchanged = byExtId("ehome.operational:EnumerationValue", PROGRAM + "_PT-Verhalten_EN-0");
		TLObject channel = byExtId("ehome.operational:Channel", PROGRAM + "_CH-1");
		TLObject block = byExtId("ehome.operational:ParameterGroup", PROGRAM + "_PB-1");
		TLObject operation = byExtId("ehome.operational:ParameterView", PROGRAM + "_P-1_R-1");
		TLObject delay = byExtId("ehome.operational:ParameterView", PROGRAM + "_P-2_R-2");
		TLObject behaviour = byExtId("ehome.operational:ParameterView", PROGRAM + "_P-3_R-3");
		TLObject time = byExtId("ehome.operational:ParameterView", PROGRAM + "_P-4_R-4");
		assertEquals("TEST_Aus", off.tValueByName("icon"));
		assertEquals("TEST_Fehlt", stairs.tValueByName("icon"));
		assertNull(unchanged.tValueByName("icon"));
		assertEquals("TEST_Kanal", channel.tValueByName("icon"));
		assertEquals("TEST_Block", block.tValueByName("icon"));
		assertEquals("Allgemein", block.tValueByName("helpTopic"));
		assertEquals("Betriebsart", operation.tValueByName("helpTopic"));
		assertEquals("TEST_Vektor", time.tValueByName("icon"));
		assertEquals("Größe", time.tValueByName("helpTopic"));

		// The warnings: once each, naming the first icon missing and the number of topics without text.
		assertEquals(List.of(
			"The application program \"" + PROGRAM + "\" names 1 icons its icon archive does not hold, the first "
				+ "\"TEST_Fehlt\"; their elements show their text alone.",
			"The application program \"" + PROGRAM + "\" names 1 help topics without a text in its default language; "
				+ "nothing is shown for them."),
			warnings(messages).stream().filter(w -> w.contains(PROGRAM)).toList());

		// The icons shown: the pictures, not the SVG, nothing for a missing one or none.
		BinaryData offIcon = ProgramResources.icon(off);
		assertEquals("TEST_Aus.png", offIcon.getName());
		assertEquals("image/png", offIcon.getContentType());
		assertEquals("TEST_Ein.png", ProgramResources.icon(on).getName());
		assertNull(ProgramResources.icon(stairs));
		assertNull(ProgramResources.icon(unchanged));
		assertEquals("TEST_Kanal.png", ProgramResources.icon(channel).getName());
		assertEquals("TEST_Block.png", ProgramResources.icon(block).getName());
		assertNotNull(ProgramResources.iconEntry(time));
		assertNull(ProgramResources.icon(time));
		assertNull(ProgramResources.icon(program));
		assertEquals("TEST_Kanal.png", ((BinaryData) script("x -> parameterIcon($x)").execute(channel)).getName());
		assertNull(script("x -> parameterIcon($x)").execute(Long.valueOf(1)));

		// The help per language, else in the default language; nothing for an empty topic.
		assertEquals("**General:** artificial help text for the test.", ProgramResources.helpText(block, Locale.ENGLISH));
		assertTrue(ProgramResources.helpText(block, Locale.GERMAN).startsWith("**Allgemein:** künstlicher"));
		assertEquals("Nur auf Deutsch.", ProgramResources.helpText(behaviour, Locale.ENGLISH));
		assertEquals("Nur auf Deutsch.", ProgramResources.helpText(behaviour, Locale.FRENCH));
		// A file named in the original encoding of the ZIP format (IBM 437), as in Theben's archives.
		assertEquals("Die *Größe* in Zentimetern.", ProgramResources.helpText(time, Locale.ENGLISH));
		assertNull(ProgramResources.helpText(delay, Locale.GERMAN));
		assertNull(ProgramResources.help(delay, Locale.ENGLISH));
		assertNull(ProgramResources.help(channel, Locale.GERMAN));

		// Formatted, safely: Markdown as HTML, raw HTML escaped, line breaks kept.
		assertEquals("<p><strong>Switching:</strong> the output follows the object.</p>",
			ProgramResources.help(operation, Locale.ENGLISH));
		assertEquals("<p><strong>Schalten:</strong> Der Ausgang folgt dem Objekt.</p>\n"
			+ "<p><em><strong>Treppenlicht</strong></em>: Der Ausgang schaltet nach der <em>Zeit</em> aus.<br>\n"
			+ "Zweite Zeile.</p>\n<hr />\n<p><strong>Hinweis:</strong> künstlich</p>",
			ProgramResources.help(operation, Locale.GERMAN));
		String general = ProgramResources.help(block, Locale.GERMAN);
		assertEquals("<p><strong>Allgemein:</strong> künstlicher Hilfetext für den Test.</p>\n"
			+ "<p>&lt;script&gt;alert(&quot;TEST&quot;)&lt;/script&gt;</p>", general);
		SafeHTML.getInstance().check(general);
		SafeHTML.getInstance().check(ProgramResources.help(operation, Locale.GERMAN));
		assertEquals("<p>a picture</p>", ProgramResources.html("![a picture](https://example.com/x.png)"));
		String links = ProgramResources.html("[web](https://example.com) [link](javascript:alert(1))");
		assertTrue(links, links.contains("href=\"https://example.com\">web</a>"));
		assertFalse(links, links.contains("javascript"));
		assertEquals(ProgramResources.help(operation, Resources.getInstance().getLocale()),
			script("x -> parameterHelp($x)").execute(operation));

		// The parameter editor of a device.
		checkDeviceEditor();

		// A second import adds nothing and warns about nothing.
		int helpFileCount = instances("ehome.operational:HelpFile").size();
		int entryCount = instances("ehome.catalog:ArchiveEntry").size();
		assertEquals(List.of(), warnings(importProduct(FIXTURE)).stream().filter(w -> w.contains(PROGRAM)).toList());
		assertEquals(helpFileCount, instances("ehome.operational:HelpFile").size());
		assertEquals(entryCount, instances("ehome.catalog:ArchiveEntry").size());
		assertEquals(names(icons), names((TLObject) program.tValueByName("iconFile")));

		// A program an earlier import read without them is completed by the next import of its file.
		try (Transaction tx = kb.beginTransaction()) {
			program.tUpdateByName("resourcesRead", null);
			program.tUpdateByName("iconFile", null);
			for (Object helpFile : List.copyOf((Collection<?>) program.tValueByName("helpFiles"))) {
				((TLObject) helpFile).tDelete();
			}
			channel.tUpdateByName("icon", null);
			operation.tUpdateByName("helpTopic", null);
			tx.commit();
		}
		List<BufferingI18NLog.Entry> completion = importProduct(FIXTURE);
		assertTrue(english(completion).contains(
			"The application program \"" + PROGRAM + "\", imported earlier without its icons and help texts, gets them."));
		assertEquals(Boolean.TRUE, program.tValueByName("resourcesRead"));
		assertEquals("TEST_Kanal", channel.tValueByName("icon"));
		assertEquals("Betriebsart", operation.tValueByName("helpTopic"));
		assertEquals(2, ((Collection<?>) program.tValueByName("helpFiles")).size());
		assertEquals(helpFileCount, instances("ehome.operational:HelpFile").size());
		assertEquals(entryCount, instances("ehome.catalog:ArchiveEntry").size());

		// A newer version of the icon archive replaces the icons: the missing one is there, another changed.
		BinaryData onBefore = ProgramResources.icon(on);
		List<BufferingI18NLog.Entry> newer = importProduct(NEWER);
		assertEquals(List.of(), warnings(newer).stream().filter(w -> w.contains(PROGRAM)).toList());
		assertEquals(List.of("TEST_Aus.png", "TEST_Ein.png", "TEST_Kanal.png", "TEST_Block.png", "TEST_Vektor.svg",
			"TEST_Fehlt.png"), names(icons));
		assertEquals("TEST_Fehlt.png", ProgramResources.icon(stairs).getName());
		assertFalse(java.util.Arrays.equals(bytes(onBefore), bytes(ProgramResources.icon(on))));
		assertEquals(entryCount + 1, instances("ehome.catalog:ArchiveEntry").size());
	}

	/**
	 * The parameter editor of a device: its pages and entries give the icons and the help of the
	 * elements they show, and the value chosen its icon, as the views ask for them.
	 */
	private void checkDeviceEditor() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject variant = byExtId("ehome.catalog:Variant", "M-0008_H-TEST.20SYMBOLE-1_P-TEST.20Symbole");
		TLObject assignment = ((Collection<?>) ((TLObject) variant.tValueByName("product")).tValueByName("programs"))
			.stream()
			.map(TLObject.class::cast)
			.filter(a -> PROGRAM.equals(((TLObject) a.tValueByName("program")).tValueByName("extId")))
			.findFirst().orElseThrow();
		TLObject project;
		TLObject device;
		try (Transaction tx = kb.beginTransaction()) {
			device = create("ehome.physical:Device");
			device.tUpdateByName("address", 78);
			device.tUpdateByName("type", variant);
			device.tUpdateByName("program", assignment);
			TLObject line = create("ehome.physical:Line");
			line.tUpdateByName("address", 1);
			line.tUpdateByName("devices", List.of(device));
			TLObject area = create("ehome.physical:Area");
			area.tUpdateByName("address", 1);
			area.tUpdateByName("lines", List.of(line));
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "TEST Symbole");
			project.tUpdateByName("areas", List.of(area));
			tx.commit();
		}
		try {
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			List<Object> pages = new java.util.ArrayList<>(configuration.getPages(device));
			assertEquals(1, pages.size());
			Object channel = pages.get(0);
			assertEquals("TEST_Kanal.png", ((BinaryData) script("x -> parameterIcon($x)").execute(channel)).getName());
			Object block = configuration.getPages(channel).get(0);
			assertEquals("TEST_Block.png", ((BinaryData) script("x -> parameterIcon($x)").execute(block)).getName());
			assertEquals("<p><strong>General:</strong> artificial help text for the test.</p>",
				ProgramResources.help(block, Locale.ENGLISH));
			ParameterView operation = configuration.getItems(block).stream()
				.filter(ParameterView.class::isInstance)
				.map(ParameterView.class::cast)
				.filter(v -> (PROGRAM + "_P-1_R-1").equals(v.getExtId()))
				.findFirst().orElseThrow();
			assertTrue(ProgramResources.help(operation, Locale.ENGLISH).contains("Switching"));
			// The value chosen: the default "Schalten" (TEST_Ein), then "Aus" (TEST_Aus); none for a value without icon.
			QueryExecutor valueIcon = script("d -> i -> parameterIcon(deviceValue($d, $i))");
			assertEquals("TEST_Ein.png", ((BinaryData) valueIcon.execute(device, operation)).getName());
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.setValue(device, operation,
					byExtId("ehome.operational:EnumerationValue", PROGRAM + "_PT-Betriebsart_EN-0"));
				tx.commit();
			}
			assertEquals("TEST_Aus.png", ((BinaryData) valueIcon.execute(device, operation)).getName());
			ParameterView behaviour = configuration.getItems(block).stream()
				.filter(ParameterView.class::isInstance)
				.map(ParameterView.class::cast)
				.filter(v -> (PROGRAM + "_P-3_R-3").equals(v.getExtId()))
				.findFirst().orElseThrow();
			assertNull(valueIcon.execute(device, behaviour));
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	/**
	 * A project file: the program it holds gets its icons and help texts from the baggage of the same
	 * file, read once the references of the whole file are resolved.
	 */
	public void testProjectFile() throws Exception {
		List<TLObject> projects = instances("ehome.project:Project");
		List<BufferingI18NLog.Entry> messages;
		try {
			messages = test.de.haumacher.ehome.importer.TestEtsImport.importSample(PROJECT_FIXTURE);
		} finally {
			test.de.haumacher.ehome.importer.SharedData.deleteProjects(
				test.de.haumacher.ehome.importer.SharedData.newProjects(projects));
		}
		TLObject program = byExtId("ehome.operational:Program", PROJECT_PROGRAM);
		assertEquals(ICONS, ((TLObject) program.tValueByName("iconFile")).tValueByName("extId"));
		assertEquals(2, ((Collection<?>) program.tValueByName("helpFiles")).size());
		TLObject channel = byExtId("ehome.operational:Channel", PROJECT_PROGRAM + "_CH-1");
		assertEquals("TEST_Kanal.png", ProgramResources.icon(channel).getName());
		TLObject operation = byExtId("ehome.operational:ParameterView", PROJECT_PROGRAM + "_P-1_R-1");
		assertEquals("<p><strong>Switching:</strong> the output follows the object.</p>",
			ProgramResources.help(operation, Locale.ENGLISH));
		assertTrue(warnings(messages).toString(), warnings(messages).contains("The application program \""
			+ PROJECT_PROGRAM + "\" names 1 icons its icon archive does not hold, the first \"TEST_Fehlt\"; their "
			+ "elements show their text alone."));
	}

	private static byte[] bytes(BinaryData data) throws java.io.IOException {
		try (InputStream in = data.getStream()) {
			return in.readAllBytes();
		}
	}

	private static List<String> names(TLObject archive) {
		return ((Collection<?>) archive.tValueByName("entries")).stream()
			.map(TLObject.class::cast)
			.map(e -> (String) e.tValueByName("name"))
			.toList();
	}

	private static List<String> warnings(List<BufferingI18NLog.Entry> messages) {
		return messages.stream()
			.filter(m -> m.getLevel() == Level.WARN)
			.map(m -> Resources.getInstance(Locale.ENGLISH).getString(m.getMessage()))
			.toList();
	}

	private static String english(List<BufferingI18NLog.Entry> messages) {
		StringBuilder result = new StringBuilder();
		for (BufferingI18NLog.Entry message : messages) {
			result.append(Resources.getInstance(Locale.ENGLISH).getString(message.getMessage())).append('\n');
		}
		return result.toString();
	}

	private static QueryExecutor script(String expr) throws ConfigurationException {
		return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr", expr));
	}

	private static List<BufferingI18NLog.Entry> importProduct(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importProductData(in);
				tx.commit();
			}
		}
		return importer.getMessages();
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProgramResources.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SafeHTML.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKeyUtil;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.FillTextParameter;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ModulePlacement;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DeviceTexts;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadRecord;

/**
 * Dynamic modules (haumacher/ehome #119) with the artificial fixture
 * <code>src/test/fixtures/TestModules.knxproj</code> (see <code>make-fixtures.py</code>, which
 * describes its programs and devices). The expected values follow from the rules of the design:
 *
 * <ul>
 * <li>The program keeps each module definition once as a template; a device's instances are computed
 * from the program and its values: one per placement in effect (in a case taken), a repeat places its
 * content as many times as its parameter says, an allocator hands out its values one after the other
 * (a module's allocator per instance of the module, on top of the base its placement names). The ID of
 * an instance is <code>MD-n_M-m_MI-k</code> (<code>k</code> the counters of the enclosing repeats),
 * of a sub-module's instance the parent's ID, the placement and its own counters; the repeat index
 * holds, per enclosing repeat and for the placement, the position of its element in its dynamic part
 * and the iteration.</li>
 * <li>Object number = <code>Number</code> + the argument of <code>BaseNumber</code>, memory offset =
 * <code>Offset</code> + the argument of <code>BaseOffset</code>, <code>{{argument}}</code> replaced in
 * the texts, <code>{{0}}</code> filled with the value of the object's text parameter.</li>
 * <li>The import attaches the device's settings and objects to the instances by their IDs and compares
 * the argument values with the file; a difference is warned, recorded and refuses the download until a
 * download replacing the layout clears it.</li>
 * <li>A count change adds and removes instances; a removed instance keeps its settings and connections,
 * which apply again when it comes back; a value beyond an allocator's limit is refused.</li>
 * </ul>
 *
 * <p>
 * Runs in the database shared with the other tests: it checks the objects of its projects and the
 * programs of its fixture by their IDs, and deletes its projects again. Three imports of the fixture
 * (about a second each once its programs are known) make it take a few seconds.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDynamicModules extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestModules.knxproj";

	/** The program with fixed placements and a placement in a case. */
	private static final String FIXED = "M-0008_A-FFF7-01-0000";

	/** The program with repeats, allocators and a sub-module. */
	private static final String REPEAT = "M-0008_A-FFF8-01-0000";

	private static final String F = FIXED + "_";

	private static final String R = REPEAT + "_";

	private static final int SEGMENT = 0x100;

	public void testImport() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			Importer importer = importFixture();
			List<String> warnings = warnings(importer);
			// The only warning: the other layout of the device 1.0.2.
			assertEquals(warnings.toString(), 1, warnings.size());
			assertTrue(warnings.get(0), warnings.get(0).contains("MD-1_M-1_MI-1") && warnings.get(0).contains("80")
				&& warnings.get(0).contains("96") && warnings.get(0).contains("ParamOffsBase"));

			checkFixedProgram();
			checkFixedDevice(device(before, 1));
			checkBranchNotTaken(device(before, 2));
			checkRepeatProgram();
			checkRepeatDevice(device(before, 3));

			// A second import reuses the programs with their templates: nothing is duplicated.
			List<Integer> counts = programCounts();
			Importer again = importFixture();
			assertEquals(1, warnings(again).size());
			assertEquals(counts, programCounts());
			assertTrue(again.getCreated("ehome.operational:Program").isEmpty());
			assertTrue(again.getCreated("ehome.operational:Parameter").isEmpty());
			assertTrue(again.getCreated("ehome.operational:ModuleDefinition").isEmpty());
			assertTrue(again.getCreated("ehome.operational:Allocator").isEmpty());
			assertTrue(again.getCreated("ehome.operational:ModulePlacement").isEmpty());
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The changes of a device's values (count changes, allocator limits), the recorded other layout and
	 * its download, and a device planned in E-Home.
	 */
	public void testChanges() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importFixture();
			checkCountChange(device(before, 3));
			checkLayout(device(before, 2));
			checkKeptInstance(device(before, 2));
			checkPlanning(device(before, 3));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The program as an earlier import left it (marked, without its module definitions, allocators
	 * and placements) is completed by an import of the file; the devices of the project imported with it
	 * get their instances, module settings and objects.
	 */
	public void testCompletion() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importFixture();
			SharedData.deleteProjects(SharedData.newProjects(before));
			stripModules(program(FIXED));
			stripModules(program(REPEAT));
			assertTrue(program(FIXED).getUsesModules());
			assertTrue(program(FIXED).getModules().isEmpty());

			Importer importer = importFixture();
			for (String program : List.of(FIXED, REPEAT)) {
				String completed = String.valueOf(I18NConstants.INFO_MODULES_COMPLETED__PROGRAM.fill(program));
				assertTrue(program, importer.getMessages().stream().anyMatch(
					m -> m.getLevel() == Level.INFO && String.valueOf(m.getMessage()).contains(completed)));
			}
			assertEquals(1, warnings(importer).size());
			assertTrue(importer.getCreated("ehome.operational:Program").isEmpty());
			checkFixedProgram();
			checkFixedDevice(device(before, 1));
			checkRepeatProgram();
			checkRepeatDevice(device(before, 3));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The text of a communication object filled with the value of its text parameter, and the
	 * arguments of an instance in a text.
	 */
	public void testTexts() {
		assertEquals("Kanal B: WZ", FillTextParameter.fill("Kanal B: {{0}}", "WZ"));
		assertEquals("Kanal B", FillTextParameter.fill("Kanal B: {{0}}", ""));
		assertEquals("Kanal B", FillTextParameter.fill("Kanal B - {{0}}", null));
		assertEquals("Kanal B: frei", FillTextParameter.fill("Kanal B: {{0:frei}}", ""));
		assertEquals("Kanal B: 3", FillTextParameter.fill("Kanal B: {{0}}", Double.valueOf(3)));
		assertEquals("Wert $1", FillTextParameter.fill("Wert {{0}}", "$1"));
		assertEquals("Kanal {{ChNo}}", FillTextParameter.fill("Kanal {{ChNo}}", "WZ"));
		assertNull(FillTextParameter.fill(null, "WZ"));
	}

	private void checkFixedProgram() {
		Program program = program(FIXED);
		assertFalse(program.getUsesModules());
		assertEquals(1, program.getModules().size());
		ModuleDefinition definition = program.getModules().get(0);
		assertEquals(F + "MD-1", definition.getExtId());
		assertEquals(List.of("ParamOffsBase", "ObjNumberBase", "ChNo"),
			definition.getArguments().stream().map(a -> a.getName()).toList());
		// The templates once, the program's own parts apart.
		assertEquals(2, definition.getParameters().size());
		assertEquals(2, definition.getPorts().size());
		assertEquals(2, program.getParameters().size());
		assertEquals(1, program.getPorts().size());

		// The placements in the structure: "Kanal A" at the top, "Kanal B" in the case "Ein".
		List<?> contents = program.getContents();
		assertEquals(List.of("ehome.operational:ChannelIndependent", "ehome.operational:ModulePlacement",
			"ehome.operational:Switch"), types(contents));
		ModulePlacement first = (ModulePlacement) contents.get(1);
		assertEquals(F + "MD-1_M-1", first.getExtId());
		assertEquals(Integer.valueOf(6), first.getOrder());
		assertSame(definition, first.getModule());
		assertEquals(List.of("96", "1", "A"), first.getArguments().stream().map(a -> a.getValue()).toList());
		TLObject inCase = list(list((TLObject) contents.get(2), "alternatives").get(0), "contents").get(0);
		assertEquals(F + "MD-1_M-2", inCase.tValueByName("extId"));
		assertEquals(Integer.valueOf(12), inCase.tValueByName("order"));
	}

	private void checkFixedDevice(TLObject device) {
		assertEquals(List.of("MD-1_M-1_MI-1 6x1 true {ParamOffsBase=96, ObjNumberBase=1, ChNo=A}",
			"MD-1_M-2_MI-1 12x1 true {ParamOffsBase=112, ObjNumberBase=3, ChNo=B}"), stored(device));

		// The settings on their instances.
		Map<String, TLObject> settings = settings(device);
		assertEquals(Set.of(F + "P-1_R-1", F + "MD-1_M-1_MI-1_P-1_R-1", F + "MD-1_M-1_MI-1_P-2_R-1",
			F + "MD-1_M-2_MI-1_P-2_R-1"), settings.keySet());
		assertEquals(Integer.valueOf(1),
			((TLObject) settings.get(F + "MD-1_M-1_MI-1_P-1_R-1").tValueByName("choice")).tValueByName("value"));
		assertEquals("WZ", settings.get(F + "MD-1_M-1_MI-1_P-2_R-1").tValueByName("text"));

		// The objects on their instances with their group addresses, numbered by the instances.
		Map<String, TLObject> connectors = connectors(device);
		assertEquals(Set.of(F + "O-0_R-1", F + "MD-1_M-1_MI-1_O-2-0_R-1", F + "MD-1_M-1_MI-1_O-2-1_R-1",
			F + "MD-1_M-2_MI-1_O-2-0_R-1"), connectors.keySet());
		assertEquals(Set.of("send P-01A0-0_GA-2"), connections(connectors.get(F + "MD-1_M-1_MI-1_O-2-0_R-1")));
		assertEquals(Set.of("send P-01A0-0_GA-4", "receive P-01A0-0_GA-1"),
			connections(connectors.get(F + "MD-1_M-2_MI-1_O-2-0_R-1")));
		assertEquals(3, ModuleParts.port(connectors.get(F + "MD-1_M-2_MI-1_O-2-0_R-1")).getPort().getIndex());

		// The labels: {{ChNo}} replaced, {{0}} filled with the instance's text parameter (its value, or
		// left out where empty), in both languages.
		assertEquals("0 Zentral Haus: Ein/Aus", label(connectors.get(F + "O-0_R-1"), Locale.GERMAN));
		assertEquals("1 Kanal A: WZ: Ein/Aus", label(connectors.get(F + "MD-1_M-1_MI-1_O-2-0_R-1"), Locale.GERMAN));
		assertEquals("Channel A: {{0}}", ResKeyUtil.getTranslation(
			(ResKey) ModuleParts.port(connectors.get(F + "MD-1_M-1_MI-1_O-2-0_R-1")).tValueByName("name"), Locale.ENGLISH));
		assertEquals("2 Kanal A: Status: Status", label(connectors.get(F + "MD-1_M-1_MI-1_O-2-1_R-1"), Locale.GERMAN));
		assertEquals("3 Kanal B: Ein/Aus", label(connectors.get(F + "MD-1_M-2_MI-1_O-2-0_R-1"), Locale.GERMAN));
		TLObject connection = list(connectors.get(F + "MD-1_M-1_MI-1_O-2-0_R-1"), "connections").get(0);
		String objectText = GroupAddressConnections.objectText(connection);
		// In the language of the test's resources.
		assertTrue(objectText, objectText.endsWith(" A: WZ") && !objectText.contains("{{"));
		String connectionLabel = GroupAddressConnections.label(connection);
		assertTrue(connectionLabel, connectionLabel.contains(" 1 ") && connectionLabel.contains(" A: WZ"));

		// The configuration: the parameters of the instances at their offsets, with their titles.
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Map<String, ParameterView> shown = shownViews(configuration);
		assertEquals(0x62, offset(configuration, shown.get(F + "MD-1_M-1_MI-1_P-1_R-1")));
		assertEquals(0x73, offset(configuration, shown.get(F + "MD-1_M-2_MI-1_P-2_R-1")));
		assertEquals("Channel B: Mode",
			inLocale(Locale.ENGLISH, () -> shown.get(F + "MD-1_M-2_MI-1_P-1_R-1").getTitle()));
		assertEquals("Kanal A: Beschreibung", shown.get(F + "MD-1_M-1_MI-1_P-2_R-1").getTitle());

		// The download: the instances' values at their offsets, their objects by their numbers.
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		DownloadImage image = plan.getImage();
		// "Modus" of Kanal A = 1: 2 bits at bit offset 4 of 0x62; "Beschreibung" "WZ" from 0x63.
		assertEquals(image.dump(), Integer.valueOf(0x04), image.byteAt(SEGMENT + 0x62));
		assertEquals(Integer.valueOf('W'), image.byteAt(SEGMENT + 0x63));
		assertTrue(associatedObjects(image).containsAll(List.of(0, 1, 2, 3)));
	}

	/**
	 * The device 1.0.2 has the default "Aus" of "Kanal B": the placement in the case "Ein" has no
	 * instance.
	 */
	private void checkBranchNotTaken(TLObject device) {
		// The file names a setting and an object of the instance of "Kanal B": kept in an instance not
		// in effect (without values yet), as a change of a value keeps a removed instance.
		assertEquals(List.of("MD-1_M-1_MI-1 6x1 true {ParamOffsBase=96, ObjNumberBase=1, ChNo=A}",
			"MD-1_M-2_MI-1 null false {}"), stored(device));
		assertEquals(Set.of(F + "MD-1_M-2_MI-1_P-2_R-1"), settings(device).keySet());
		TLObject connector = connectors(device).get(F + "MD-1_M-2_MI-1_O-2-0_R-1");
		assertEquals(Set.of("send P-01A0-0_GA-4"), connections(connector));
		assertFalse(new DeviceConfiguration(device).getConnectors().contains(connector));
		// The difference to the file is recorded on the argument.
		assertEquals(List.of("80"), ModuleInstances.differences(device).stream()
			.map(a -> a.tValueByName(ModuleInstances.DEVICE_VALUE)).toList());
	}

	private void checkRepeatProgram() {
		Program program = program(REPEAT);
		assertFalse(program.getUsesModules());
		assertEquals(List.of(R + "MD-1", R + "MD-2"), program.getModules().stream().map(m -> m.getExtId()).toList());
		ModuleDefinition channel = program.getModules().get(1);
		assertEquals(List.of(R + "MD-2_SM-1"), channel.getModules().stream().map(m -> m.getExtId()).toList());
		assertEquals(3, channel.getAllocators().size());
		assertEquals(5, program.getAllocators().size());
		TLObject repeat = (TLObject) program.getContents().get(1);
		assertEquals("ehome.operational:Repeat", TLModelUtil.qualifiedName(repeat.tType()));
		assertEquals(Integer.valueOf(5), repeat.tValueByName("order"));
		assertEquals(R + "P-1_R-1", ((TLObject) repeat.tValueByName("countParameter")).tValueByName("extId"));
	}

	private void checkRepeatDevice(TLObject device) {
		// Three signals (offsets 160, 192, 224 from Signal_params, objects from 10 by 2, numbers from 1),
		// two channels (offsets 96, 108, objects 20, 24, numbers after the signals'), one scene in the
		// first channel and two in the second (offsets 2, 4 on top of the channel's, objects 1, 2 on top
		// of the channel's).
		assertEquals(List.of(
			"MD-1_M-1_MI-1 5x1 6x1 true {ParamsOffsetBase=160, ObjNumberBase=10, SigNo=1}",
			"MD-1_M-1_MI-2 5x2 6x1 true {ParamsOffsetBase=192, ObjNumberBase=12, SigNo=2}",
			"MD-1_M-1_MI-3 5x3 6x1 true {ParamsOffsetBase=224, ObjNumberBase=14, SigNo=3}",
			"MD-2_M-1_MI-1 10x1 11x1 true {KaOffs=96, KaObj=20, KaNo=4}",
			"MD-2_M-1_MI-1_SM-1_M-1_MI-1-1-1 10x1 11x1 5x1 6x1 true {SzOffs=98, SzObj=21, KaNo=4, SzNo=1}",
			"MD-2_M-1_MI-2 10x2 11x1 true {KaOffs=108, KaObj=24, KaNo=5}",
			"MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-1 10x2 11x1 5x1 6x1 true {SzOffs=110, SzObj=25, KaNo=5, SzNo=1}",
			"MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-2 10x2 11x1 5x2 6x1 true {SzOffs=112, SzObj=26, KaNo=5, SzNo=2}"),
			stored(device));
		assertTrue(ModuleInstances.differences(device).isEmpty());

		Map<String, TLObject> settings = settings(device);
		assertEquals(Set.of(R + "P-1_R-1", R + "MD-1_M-1_MI-2_P-2_R-1", R + "MD-1_M-1_MI-3_P-2_R-1",
			R + "MD-2_M-1_MI-2_P-1_R-1", R + "MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-2_P-1_R-1"), settings.keySet());
		checkEditorTexts(device);
		assertEquals("Süd", settings.get(R + "MD-1_M-1_MI-2_P-2_R-1").tValueByName("text"));
		assertEquals(Long.valueOf(7), settings.get(R + "MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-2_P-1_R-1").tValueByName("number"));

		Map<String, TLObject> connectors = connectors(device);
		TLObject signal = connectors.get(R + "MD-1_M-1_MI-2_O-1-0_R-1");
		TLObject scene = connectors.get(R + "MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-2_O-1-0_R-1");
		assertEquals(Set.of("send P-01A0-0_GA-2"), connections(signal));
		assertEquals(Set.of("send P-01A0-0_GA-3"), connections(scene));
		assertEquals("12 Signal 2: Süd: Wert", label(signal, Locale.GERMAN));
		assertEquals("26 Kanal 5, Szene 2: Szene", label(scene, Locale.GERMAN));

		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		DownloadImage image = plan.getImage();
		assertEquals(image.dump(), Integer.valueOf(3), image.byteAt(SEGMENT + 0x58));
		// "Typ" of signal 3 (default 1) at 224, "Name" of signal 2 from 193.
		assertEquals(Integer.valueOf(1), image.byteAt(SEGMENT + 224));
		assertEquals(Integer.valueOf('S'), image.byteAt(SEGMENT + 193));
		assertEquals(Integer.valueOf(0xFC), image.byteAt(SEGMENT + 194));
		// "Anzahl Szenen" of channel 2 at 108, the "Wert" of its scenes at 110 (default 5) and 112 (7).
		assertEquals(Integer.valueOf(2), image.byteAt(SEGMENT + 108));
		assertEquals(Integer.valueOf(5), image.byteAt(SEGMENT + 110));
		assertEquals(Integer.valueOf(7), image.byteAt(SEGMENT + 112));
		assertEquals(Set.of(12, 26), Set.copyOf(associatedObjects(image)));
	}

	/**
	 * The texts the parameter editor shows for the pages and objects of the device 1.0.3 (the
	 * functions its views call: the pages of the tree, the entries of a page, their labels by
	 * <code>deviceText</code>): <code>{{0}}</code> filled from the text parameter of the channel, block
	 * and object (the "Name" of the signal: "Süd", empty for signal 3), <code>{{0:default}}</code> with
	 * its default where the value is empty.
	 */
	private void checkEditorTexts(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Map<String, String> pages = new LinkedHashMap<>();
		Map<String, String> objects = new LinkedHashMap<>();
		collectTexts(device, configuration, device, pages, objects);
		assertEquals("Signal 2: Süd", pages.get(R + "MD-1_M-1_MI-2_CH-1"));
		assertEquals("Signal 3", pages.get(R + "MD-1_M-1_MI-3_CH-1"));
		assertEquals("Süd", pages.get(R + "MD-1_M-1_MI-2_PB-1"));
		assertEquals("Signal 3", pages.get(R + "MD-1_M-1_MI-3_PB-1"));
		assertEquals("Sig", pages.get(R + "MD-1_M-1_MI-1_PB-1"));
		assertEquals("Signal 2: Süd", objects.get(R + "MD-1_M-1_MI-2_O-1-0_R-1"));
		assertEquals("Signal 3", objects.get(R + "MD-1_M-1_MI-3_O-1-0_R-1"));
		for (String text : pages.values()) {
			assertFalse(text, text.contains("{{"));
		}
		for (String text : objects.values()) {
			assertFalse(text, text.contains("{{"));
		}
	}

	private static void collectTexts(TLObject device, DeviceConfiguration configuration, Object page,
			Map<String, String> pages, Map<String, String> objects) {
		for (Object item : configuration.getItems(page)) {
			if (item instanceof Interface placed) {
				objects.put(ModuleParts.partId(placed), DeviceTexts.label(device, placed));
			}
		}
		for (Object inner : configuration.getPages(page)) {
			pages.put(ModuleParts.partId((TLObject) inner), DeviceTexts.label(device, inner));
			collectTexts(device, configuration, inner, pages, objects);
		}
	}

	/**
	 * A count change on the device 1.0.3.
	 */
	private void checkCountChange(TLObject device) {
		ParameterView signals = byId(new DeviceConfiguration(device).getShownItems().keySet(), R + "P-1_R-1");
		TLObject groupAddress;
		try (Transaction tx = kb().beginTransaction()) {
			// Signal 3 gets a name and a connection.
			ParameterView name3 = shownViews(new DeviceConfiguration(device)).get(R + "MD-1_M-1_MI-3_P-2_R-1");
			DeviceSettings.setValue(device, name3, "Ost");
			Interface value3 = interfaceOf(device, R + "MD-1_M-1_MI-3_O-1-0_R-1");
			groupAddress = DeviceSettings.groupAddresses(device).get(3);
			DeviceSettings.connect(device, value3, groupAddress, kind("send"));

			// Two signals: the third is no longer in effect, the channels are numbered after two signals.
			DeviceSettings.setValue(device, signals, Integer.valueOf(2));
			tx.commit();
		}
		List<String> instances = stored(device);
		assertTrue(instances.toString(), instances.contains("MD-1_M-1_MI-3 5x3 6x1 false {ParamsOffsetBase=224, ObjNumberBase=14, SigNo=3}"));
		assertTrue(instances.toString(), instances.contains("MD-2_M-1_MI-1 10x1 11x1 true {KaOffs=96, KaObj=20, KaNo=3}"));
		Map<String, TLObject> settings = settings(device);
		assertTrue("The setting of the removed instance stays.", settings.containsKey(R + "MD-1_M-1_MI-3_P-2_R-1"));
		TLObject connector = connectors(device).get(R + "MD-1_M-1_MI-3_O-1-0_R-1");
		assertNotNull("The connector of the removed instance stays.", connector);
		assertEquals(1, list(connector, "connections").size());
		DeviceConfiguration two = new DeviceConfiguration(device);
		assertFalse(two.getConnectors().contains(connector));
		assertNull(shownViews(two).get(R + "MD-1_M-1_MI-3_P-2_R-1"));
		DownloadImage image = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getImage();
		assertFalse(associatedObjects(image).contains(14));
		// The static data where the third signal was: not written by its values.
		assertEquals(Integer.valueOf(0), image.byteAt(SEGMENT + 225));

		// Back to three: the instance with its setting and connection applies again.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(device, signals, Integer.valueOf(3));
			tx.commit();
		}
		assertTrue(stored(device).contains("MD-1_M-1_MI-3 5x3 6x1 true {ParamsOffsetBase=224, ObjNumberBase=14, SigNo=3}"));
		DeviceConfiguration three = new DeviceConfiguration(device);
		assertTrue(three.getConnectors().contains(connector));
		assertEquals("Ost", three.valueHolder(shownViews(three).get(R + "MD-1_M-1_MI-3_P-2_R-1")).tValueByName("text"));
		image = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getImage();
		assertTrue(associatedObjects(image).contains(14));
		assertEquals(Integer.valueOf('O'), image.byteAt(SEGMENT + 225));

		// Four signals would need the offset 256 of Signal_params, beyond its limit 224: refused, naming
		// the module and the limit; the value stays.
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(device, signals, Integer.valueOf(4));
			fail("Four signals exceed the allocator.");
		} catch (TopLogicException ex) {
			String message = Resources.getInstance().getString(ex.getErrorKey());
			assertTrue(message, message.contains("Signal") && message.contains("224"));
		}
		assertEquals("3", new DeviceConfiguration(device).value(signals));
		assertFalse(stored(device).toString(), stored(device).toString().contains("MD-1_M-1_MI-4"));
	}

	/**
	 * The device 1.0.2 holds another layout: its download is refused until the download replacing the
	 * layout, confirmed naming the difference, clears it.
	 */
	private void checkLayout(TLObject device) {
		DownloadPlan full = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertFalse(full.isExecutable());
		String problems = full.getProblems().stream().map(p -> Resources.getInstance().getString(p))
			.collect(Collectors.joining(" "));
		assertTrue(problems, problems.contains("MD-1_M-1_MI-1") && problems.contains("80"));

		DownloadPlan layout = DownloadPlanner.plan(device, DownloadPlan.Kind.LAYOUT);
		assertTrue(layout.getProblems().toString(), layout.isExecutable());
		String confirmation = Resources.getInstance().getString(
			de.haumacher.ehome.programming.download.I18NConstants.CONFIRM_LAYOUT__DEVICE_ADDRESS_GATEWAY_DIFFERENCES
				.fill("x", "1.0.2", "-", ModuleInstances.differenceText(device)));
		assertTrue(confirmation, confirmation.contains("ParamOffsBase") && confirmation.contains("80"));

		// The confirmed download succeeded: the device holds E-Home's layout.
		DownloadRecord.record(device, DownloadPlan.Kind.LAYOUT, layout.getRevision());
		assertTrue(ModuleInstances.differences(device).isEmpty());
		assertTrue(DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).isExecutable());
		assertFalse(DownloadPlanner.plan(device, DownloadPlan.Kind.LAYOUT).isExecutable());
	}

	/**
	 * The instance of "Kanal B" the import kept not in effect on the device 1.0.2 comes into effect
	 * with "Kanal B" = "Ein": its setting and its connection apply.
	 */
	private void checkKeptInstance(TLObject device) {
		ParameterView kanalB = byId(new DeviceConfiguration(device).getShownItems().keySet(), F + "P-1_R-1");
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.setValue(device, kanalB, Integer.valueOf(1));
			tx.commit();
		}
		assertTrue(stored(device).toString(),
			stored(device).contains("MD-1_M-2_MI-1 12x1 true {ParamOffsBase=112, ObjNumberBase=3, ChNo=B}"));
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		assertEquals("Bad", configuration.valueHolder(shownViews(configuration).get(F + "MD-1_M-2_MI-1_P-2_R-1"))
			.tValueByName("text"));
		TLObject connector = connectors(device).get(F + "MD-1_M-2_MI-1_O-2-0_R-1");
		assertTrue(configuration.getConnectors().contains(connector));
		assertEquals(List.of(3), associatedObjects(DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getImage()));
	}

	/**
	 * A device of the repeat program planned in E-Home: its instances for the defaults, no settings, a
	 * download with the instances' offsets and object numbers.
	 */
	private void checkPlanning(TLObject imported) {
		TLObject planned;
		try (Transaction tx = kb().beginTransaction()) {
			planned = DevicePlanning.create(imported.tContainer(), (TLObject) imported.tValueByName("type"),
				(TLObject) imported.tValueByName("program"), Integer.valueOf(10), "TEST geplant", null);
			tx.commit();
		}
		assertTrue(list(planned, "settings").isEmpty());
		assertEquals(List.of(
			"MD-1_M-1_MI-1 5x1 6x1 true {ParamsOffsetBase=160, ObjNumberBase=10, SigNo=1}",
			"MD-1_M-1_MI-2 5x2 6x1 true {ParamsOffsetBase=192, ObjNumberBase=12, SigNo=2}",
			"MD-2_M-1_MI-1 10x1 11x1 true {KaOffs=96, KaObj=20, KaNo=3}",
			"MD-2_M-1_MI-1_SM-1_M-1_MI-1-1-1 10x1 11x1 5x1 6x1 true {SzOffs=98, SzObj=21, KaNo=3, SzNo=1}",
			"MD-2_M-1_MI-2 10x2 11x1 true {KaOffs=108, KaObj=24, KaNo=4}",
			"MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-1 10x2 11x1 5x1 6x1 true {SzOffs=110, SzObj=25, KaNo=4, SzNo=1}"),
			stored(planned));

		// The parameter editor shows the parameters of the instances with the modules' defaults.
		DeviceConfiguration configuration = new DeviceConfiguration(planned);
		Map<String, ParameterView> shown = shownViews(configuration);
		ParameterView type2 = shown.get(R + "MD-1_M-1_MI-2_P-1_R-1");
		assertEquals("Signal 2: Typ", type2.getTitle());
		assertEquals("1", configuration.value(type2));
		assertEquals(192, offset(configuration, type2));
		assertEquals("Szene 1", pageTitle(configuration, shown, R + "MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-1_P-1_R-1"));

		// An object gets its connector when it is connected.
		try (Transaction tx = kb().beginTransaction()) {
			Interface value2 = interfaceOf(planned, R + "MD-1_M-1_MI-2_O-1-0_R-1");
			DeviceSettings.connect(planned, value2, DeviceSettings.groupAddresses(planned).get(0), kind("send"));
			tx.commit();
		}
		assertEquals(Set.of(R + "MD-1_M-1_MI-2_O-1-0_R-1"), connectors(planned).keySet());

		DownloadPlan plan = DownloadPlanner.plan(planned, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		DownloadImage image = plan.getImage();
		assertEquals(image.dump(), Integer.valueOf(1), image.byteAt(SEGMENT + 160));
		assertEquals(Integer.valueOf(1), image.byteAt(SEGMENT + 192));
		assertEquals(Integer.valueOf('S'), image.byteAt(SEGMENT + 193));
		assertEquals(Integer.valueOf(1), image.byteAt(SEGMENT + 96));
		assertEquals(Integer.valueOf(5), image.byteAt(SEGMENT + 98));
		assertEquals(Integer.valueOf(5), image.byteAt(SEGMENT + 110));
		assertEquals(List.of(12), associatedObjects(image));
	}

	/**
	 * The stored instances of the device: ID, repeat index, whether in effect, argument values.
	 */
	private static List<String> stored(TLObject device) {
		List<String> result = new ArrayList<>();
		for (TLObject instance : ModuleInstances.stored(device)) {
			Map<String, String> values = new LinkedHashMap<>();
			for (Object element : (Collection<?>) instance.tValueByName(ModuleInstances.ARGUMENTS)) {
				TLObject argument = (TLObject) element;
				values.put((String) ((TLObject) argument.tValueByName(ModuleInstances.ARGUMENT)).tValueByName("name"),
					(String) argument.tValueByName(ModuleInstances.VALUE));
			}
			result.add(instance.tValueByName(ModuleInstances.NAME) + " " + instance.tValueByName(ModuleInstances.REPEAT_INDEX)
				+ " " + instance.tValueByName(ModuleInstances.ACTIVE) + " " + values);
		}
		return result;
	}

	private static Map<String, TLObject> settings(TLObject device) {
		return list(device, "settings").stream()
			.collect(Collectors.toMap(s -> ModuleParts.partId(s, ModuleParts.PARAMETER), s -> s));
	}

	private static Map<String, TLObject> connectors(TLObject device) {
		return list(device, "connectors").stream()
			.collect(Collectors.toMap(c -> ModuleParts.partId(c, ModuleParts.PORT), c -> c));
	}

	private static Map<String, ParameterView> shownViews(DeviceConfiguration configuration) {
		Map<String, ParameterView> result = new LinkedHashMap<>();
		for (Object item : configuration.getShownItems().keySet()) {
			if (item instanceof ParameterView view) {
				result.put(ModuleParts.partId(view), view);
			}
		}
		return result;
	}

	private static String pageTitle(DeviceConfiguration configuration, Map<String, ParameterView> shown, String id) {
		Object page = configuration.getShownItems().get(shown.get(id));
		return MetaLabelProvider.INSTANCE.getLabel(page);
	}

	/**
	 * The byte offset in its segment of the parameter of the given placement, as the configuration has
	 * it.
	 */
	private static int offset(DeviceConfiguration configuration, ParameterView view) {
		Parameter parameter = configuration.parameter(view);
		return parameter.getAddress().getByteOffset();
	}

	private static Interface interfaceOf(TLObject device, String id) {
		for (Interface placed : new DeviceConfiguration(device).getActiveInterfaces().values()) {
			if (id.equals(ModuleParts.partId(placed))) {
				return placed;
			}
		}
		fail("No object " + id);
		return null;
	}

	/**
	 * The numbers of the objects in the association table of the image (BCU 1: the number of entries,
	 * then per entry the index of the address and the number of the object).
	 */
	private static List<Integer> associatedObjects(DownloadImage image) {
		byte[] data = image.getAssociationTable().data();
		List<Integer> result = new ArrayList<>();
		for (int n = 0, cnt = data[0] & 0xFF; n < cnt; n++) {
			result.add(Integer.valueOf(data[2 + 2 * n] & 0xFF));
		}
		return result;
	}

	private static TLObject kind(String name) {
		return (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#" + name);
	}

	/**
	 * Removes what an import of the modules adds to the program, and marks it: the program as an import
	 * before haumacher/ehome #119 left it.
	 */
	private static void stripModules(Program program) {
		try (Transaction tx = kb().beginTransaction()) {
			removePlacements(program);
			for (ModuleDefinition definition : new ArrayList<>(program.getModules())) {
				definition.tDelete();
			}
			for (TLObject allocator : new ArrayList<TLObject>(program.getAllocators())) {
				allocator.tDelete();
			}
			program.setUsesModules(true);
			tx.commit();
		}
	}

	private static void removePlacements(TLObject fragment) {
		for (TLObject option : list(fragment, "contents")) {
			String type = TLModelUtil.qualifiedName(option.tType());
			if (type.equals("ehome.operational:ModulePlacement") || type.equals("ehome.operational:Repeat")) {
				option.tDelete();
				continue;
			}
			removePlacements(option);
			for (TLObject alternative : list(option, "alternatives")) {
				removePlacements(alternative);
			}
		}
	}

	private static Importer importFixture() throws Exception {
		KnowledgeBase kb = kb();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(FIXTURE)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importStream(in);
				tx.commit();
			}
		}
		return importer;
	}

	private static List<String> warnings(Importer importer) {
		List<BufferingI18NLog.Entry> messages = TestEtsImport.withoutMasterData(importer.getMessages());
		return messages.stream().filter(m -> m.getLevel() == Level.WARN || m.getLevel() == Level.ERROR)
			.map(m -> Resources.getInstance().getString((ResKey) m.getMessage())).toList();
	}

	private static List<Integer> programCounts() {
		List<Integer> result = new ArrayList<>();
		for (String type : List.of("ehome.operational:Program", "ehome.operational:Parameter", "ehome.operational:Port",
			"ehome.operational:ParameterView", "ehome.operational:Interface", "ehome.operational:ModuleDefinition",
			"ehome.operational:ModuleArgument", "ehome.operational:ModulePlacement", "ehome.operational:Repeat",
			"ehome.operational:Allocator", "ehome.operational:PlacementArgument")) {
			result.add(instances(type).size());
		}
		return result;
	}

	private static Program program(String id) {
		List<TLObject> programs = instances("ehome.operational:Program").stream()
			.filter(p -> id.equals(p.tValueByName("extId")))
			.toList();
		assertEquals(1, programs.size());
		return (Program) programs.get(0);
	}

	private static TLObject device(List<TLObject> before, int address) {
		List<TLObject> devices = SharedData.within("ehome.physical:Device", SharedData.newProjects(before)).stream()
			.filter(d -> Integer.valueOf(address).equals(d.tValueByName("address"))).toList();
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	@SuppressWarnings("unchecked")
	private static <T> T byId(Collection<?> objects, String id) {
		for (Object obj : objects) {
			if (obj instanceof TLObject tlObject && id.equals(ModuleParts.partId(tlObject))) {
				return (T) tlObject;
			}
		}
		return null;
	}

	private static Set<String> connections(TLObject connector) {
		return list(connector, "connections").stream()
			.map(c -> ((TLClassifier) c.tValueByName("kind")).getName() + " "
				+ ((TLObject) c.tValueByName("target")).tValueByName("extId"))
			.collect(Collectors.toSet());
	}

	private static String label(TLObject connector, Locale locale) {
		return inLocale(locale, () -> (String) derived(connector, "displayName"));
	}

	private static <T> T inLocale(Locale locale, Supplier<T> function) {
		TLContext context = TLContext.getContext();
		Locale before = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return function.get();
		} finally {
			context.setCurrentLocale(before);
		}
	}

	private static List<String> types(List<?> objects) {
		return objects.stream().map(o -> TLModelUtil.qualifiedName(((TLObject) o).tType())).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<TLObject> list(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value instanceof Collection<?> collection ? List.copyOf((Collection<TLObject>) collection) : List.of();
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDynamicModules.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

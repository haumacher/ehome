/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.schema23;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.importer.TestEtsImport;
import test.de.haumacher.ehome.roundtrip.TestRoundTrip;

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
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.model.building.Room;
import de.haumacher.ehome.model.datapoint.DatapointSubtype;
import de.haumacher.ehome.model.datapoint.NamedValue;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.ColorType;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.MaskConfiguration;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.ResourceLocation;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.physical.Connection;
import de.haumacher.ehome.model.physical.Connector;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.physical.Line;
import de.haumacher.ehome.model.physical.ParameterSetting;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.monitor.DatapointCodec;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * Test of the import of an ETS 6 project file (project schema 23) with the artificial fixture
 * <code>src/test/fixtures/TestEts6.knxproj</code> (see <code>make-fixtures.py</code>): the
 * constructs of schema 23 in the master data, the program and the project, the named values of
 * datapoint types and their use in the send form of the group monitor, and the re-import.
 *
 * <p>
 * Runs in the database shared with the other tests: it checks the objects of its project and the
 * shared objects of its fixture by their IDs, and deletes its projects again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestEts6Import extends BasicTestCase {

	private static final String PROGRAM = "M-0008_A-10D7-11-A0E5-O0024";

	private static final String P = PROGRAM + "_";

	private static final Dpt HVAC_MODE = new Dpt(20, 102);

	private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

	public void testImport() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			Importer importer = Ets6Files.importFile(Files.readAllBytes(Path.of(Ets6Files.FIXTURE)), null);
			assertEquals(23, importer.getSchemaVersion());
			List<BufferingI18NLog.Entry> messages = importer.getMessages();
			assertEquals(List.of(), Ets6Files.errors(messages));
			// The only warning: the device's object of an instance of the dynamic module, which is none of
			// the module and is left out. The setting of the instance is kept: the module (its argument from
			// an allocator) is placed in a case the device's value does not take, so its instance is kept
			// not in effect (besides the warnings about mask versions the database holds from other master
			// data, see TestEtsImport.withoutMasterData).
			List<ResKey> warnings = TestEtsImport.withoutMasterData(messages).stream().filter(m -> m.getLevel() == Level.WARN)
				.map(m -> m.getMessage()).toList();
			assertEquals(warnings.toString(), 1, warnings.size());
			String left = warnings.get(0).toString();
			assertTrue(left, left.contains("WARN_UNRESOLVED_PROGRAM_PARTS__DEVICE_SETTINGS_OBJECTS") && left.endsWith("/i0/i1"));

			checkProject();
			checkProgram();
			checkMasterData();
			checkNamedValues();
			checkSendForm();

			// The export is a schema 11 file (Connectors, full IDs, no ETS 6 elements) that imports again
			// without errors and duplicates no shared data.
			List<Integer> shared = sharedCounts();
			byte[] exported = TestRoundTrip.export(projects().get(0));
			String program = new String(Ets6Files.entries(exported).get("M-0008/" + PROGRAM + ".xml"),
				StandardCharsets.UTF_8);
			assertTrue(program.contains("xmlns=\"http://knx.org/xml/project/11\""));
			assertFalse(program.contains("TypeColor"));
			assertFalse(program.contains("ModuleDefs"));
			Importer reimport = Ets6Files.importFile(exported, null);
			assertEquals(List.of(), Ets6Files.errors(reimport.getMessages()));
			assertEquals(shared, sharedCounts());
			assertEquals(2, projects().size());
			TLObject copy = projects().stream().filter(p -> p != projects().get(0)).findFirst().orElseThrow();
			deleteProject(copy);

			// A second import reuses all shared data: nothing of it is duplicated.
			Importer again = Ets6Files.importFile(Files.readAllBytes(Path.of(Ets6Files.FIXTURE)), null);
			assertEquals(List.of(), Ets6Files.errors(again.getMessages()));
			assertEquals(shared, sharedCounts());
			assertEquals(2, projects().size());
			assertEquals(1, again.getKnown("ehome.operational:Program").size());
			assertTrue(again.getCreated("ehome.operational:Program").isEmpty());
			assertTrue(again.getCreated("ehome.datapoint:NamedValue").isEmpty());
			checkNamedValues();
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The numbers of the objects of the data shared between projects.
	 */
	private static List<Integer> sharedCounts() {
		return List.of("ehome.datapoint:DatapointMainType", "ehome.datapoint:DatapointSubtype",
			"ehome.datapoint:NamedValue", "ehome.operational:MaskVersion", "ehome.operational:LoadStep",
			"ehome.catalog:Manufacturer", "ehome.catalog:Product", "ehome.catalog:Variant",
			"ehome.catalog:ProgramAssignment", "ehome.operational:Program", "ehome.operational:Parameter",
			"ehome.operational:ParameterView", "ehome.operational:SharedPlacement", "ehome.operational:Interface",
			"ehome.operational:ColorType").stream()
			.map(type -> instances(type).size())
			.toList();
	}

	private static void deleteProject(TLObject project) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			project.tDelete();
			tx.commit();
		}
	}

	private static List<TLObject> projects() {
		return instances("ehome.project:Project").stream()
			.filter(p -> "TestEts6".equals(((Project) p).getName()))
			.toList();
	}

	private void checkProject() {
		List<TLObject> projects = projects();
		assertEquals(1, projects.size());
		Project project = (Project) projects.get(0);
		assertEquals("P-01A0", project.getExtId());

		// The device lies in a segment of its line (ETS 6); the model has no segment.
		List<TLObject> devices = SharedData.within("ehome.physical:Device", projects);
		assertEquals(1, devices.size());
		TLObject device = devices.get(0);
		assertEquals("P-01A0-0_L-1", ((Line) device.tContainer()).getExtId());
		assertEquals(P + "P-9102_R-9102", setting(device, "M-0008_A-10D7-11-A0E5-O0024_P-9102_R-9102"));
		for (TLObject setting : list(device, "settings")) {
			assertNotNull("The setting of the module's parameter is left out.", ((ParameterSetting) setting).getParameter());
		}
		checkLabelsWithoutProgramPart(device);

		// The load state, written as "true" and "false".
		assertEquals(Set.of("IndividualAddress"), ((Device) device).getLoaded().stream()
			.map(TLClassifier::getName).collect(Collectors.toSet()));

		// Communication objects named relative to the program and linked by the attribute Links.
		List<TLObject> connectors = list(device, "connectors");
		assertEquals(18, connectors.size());
		for (TLObject connector : connectors) {
			assertNotNull(((Connector) connector).getPort());
		}
		assertEquals(10, connectors.stream().filter(c -> !list(c, "connections").isEmpty()).count());
		assertEquals(11, SharedData.within("ehome.physical:Connection", projects).size());
		TLObject twoLinks = connectors.stream()
			.filter(c -> "M-0008_A-10D7-11-A0E5-O0024_O-0_R-81".equals(((Connector) c).getPort().getExtId()))
			.findFirst().orElseThrow();
		List<TLObject> connections = list(twoLinks, "connections");
		// The connections of an object are a set.
		assertEquals(Set.of("send P-01A0-0_GA-3", "receive P-01A0-0_GA-1"), connections.stream()
			.map(c -> ((Connection) c).getKind().getName() + " " + ((Connection) c).getTarget().getExtId())
			.collect(Collectors.toSet()));
		assertEquals(10, SharedData.within("ehome.logical:GroupAddress", projects).size());

		// The location of the device: a room in a building (ETS 5/6: Locations with spaces).
		TLObject room = ((Device) device).getLocation();
		assertEquals("ehome.building:Room", TLModelUtil.qualifiedName(room.tType()));
		assertEquals("TEST Raum", ((Room) room).getName());
		assertEquals("ehome.building:Building", TLModelUtil.qualifiedName(room.tContainer().tType()));
	}

	/**
	 * A setting or a communication object whose part of the program is missing names its device and
	 * why, not "unnamed object" or "null null": the model's check of the mandatory reference shows
	 * these labels when a commit is refused.
	 */
	private static void checkLabelsWithoutProgramPart(TLObject device) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ParameterSetting setting = (ParameterSetting) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.physical:ParameterSetting"));
			Connector connector = (Connector) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.physical:Connector"));
			Device typed = (Device) device;
			List<ParameterSetting> settings = new ArrayList<>(typed.getSettings());
			settings.add(setting);
			typed.setSettings(settings);
			Set<Connector> connectors = new HashSet<>(typed.getConnectors());
			connectors.add(connector);
			typed.setConnectors(connectors);
			String deviceLabel = TestEtsImport.asRoot(() -> MetaLabelProvider.INSTANCE.getLabel(device));
			String settingLabel = TestEtsImport.asRoot(() -> MetaLabelProvider.INSTANCE.getLabel(setting));
			String connectorLabel = TestEtsImport.asRoot(() -> MetaLabelProvider.INSTANCE.getLabel(connector));
			for (String label : List.of(settingLabel, connectorLabel)) {
				assertTrue(label, label.startsWith(deviceLabel + ": "));
				assertFalse(label, label.contains("null"));
				assertTrue(label, label.contains("dynamic module") || label.contains("dynamischen Moduls"));
			}
			tx.rollback();
		}
	}

	private static String setting(TLObject device, String refId) {
		return list(device, "settings").stream()
			.map(s -> ((ParameterSetting) s).getParameter().getExtId())
			.filter(refId::equals)
			.findFirst().orElse(null);
	}

	private void checkProgram() {
		Program program = program();
		assertEquals("TEST: ETS 6 (künstliche Testdaten)", derived(program, "name") instanceof ResKey key
			? ResKeyUtil.getTranslation(key, Locale.GERMAN) : null);
		// The parameters of the Test program (2187, union members included) and the four of the test;
		// the parameter of the module is part of its definition (haumacher/ehome #119).
		assertEquals(2191, program.getParameters().size());
		assertEquals(3925, program.getParameters().stream().mapToInt(p -> p.getViews().size()).sum());
		assertEquals(92, program.getPorts().size());
		assertFalse(program.getUsesModules());
		assertEquals(1, program.getModules().size());
		assertEquals(1, program.getModules().get(0).getParameters().size());
		assertEquals(1, program.getAllocators().size());

		List<?> contents = program.getContents();
		assertEquals(3, contents.size());
		TLObject independent = (TLObject) contents.get(0);
		assertEquals("ehome.operational:ChannelIndependent", TLModelUtil.qualifiedName(independent.tType()));
		TLObject block = list(((ConfigFragment) independent).getContents()).get(0);
		List<TLObject> blockContents = list(((ConfigFragment) block).getContents());
		assertEquals(List.of("ehome.operational:ParameterView", "ehome.operational:Switch"), types(blockContents));
		List<TLObject> cases = list(((Switch) blockContents.get(1)).getAlternatives());
		assertEquals(3, cases.size());

		// The reference placed in two cases is owned by the first, the second refers to it.
		List<TLObject> first = list(((Case) cases.get(0)).getContents());
		List<TLObject> second = list(((Case) cases.get(1)).getContents());
		assertEquals(List.of("ehome.operational:ParameterView", "ehome.operational:Interface"), types(first));
		// ... and the placement of the module (haumacher/ehome #119).
		assertEquals(List.of("ehome.operational:SharedPlacement", "ehome.operational:SharedPlacement",
			"ehome.operational:ModulePlacement"), types(second));
		assertSame(first.get(0), ((SharedPlacement) second.get(0)).getPlaced());
		assertSame(first.get(1), ((SharedPlacement) second.get(1)).getPlaced());
		assertEquals(P + "P-9102_R-9102", ((ParameterView) first.get(0)).getExtId());
		assertEquals(derived(first.get(0), "title"), derived(second.get(0), "displayName"));
		// One reference, one identity: the device setting refers to it.
		assertEquals(1, ((ParameterView) first.get(0)).getParameter().getViews() instanceof Collection<?> views
			? views.size() : -1);

		// A parameter block directly in the program, with a color parameter.
		TLObject direct = (TLObject) contents.get(1);
		assertEquals("ehome.operational:ParameterGroup", TLModelUtil.qualifiedName(direct.tType()));
		assertEquals(P + "PB-9103", ((ParameterGroup) direct).getExtId());
		TLObject color = list(((ParameterGroup) direct).getContents()).get(1);
		Parameter colorParameter = ((ParameterView) color).getParameter();
		TLObject colorType = colorParameter.getType();
		assertEquals("ehome.operational:ColorType", TLModelUtil.qualifiedName(colorType.tType()));
		assertEquals("RGB", ((ColorType) colorType).getSpace());
		assertEquals("#FF8000", colorParameter.getText());
		assertEquals("#FF8000", derived(colorParameter, "displayValue"));

		assertEquals("ehome.operational:Channel", TLModelUtil.qualifiedName(((TLObject) contents.get(2)).tType()));

		// A program whose modules are read is not refused for its modules.
		TLObject device = SharedData.within("ehome.physical:Device", projects()).get(0);
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertFalse(plan.getProblems().toString(), plan.getProblems().contains(
			de.haumacher.ehome.programming.download.I18NConstants.ERROR_PROGRAM_USES_MODULES));
	}

	private static Program program() {
		List<TLObject> programs = instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(((Program) p).getExtId()))
			.toList();
		assertEquals(1, programs.size());
		return (Program) programs.get(0);
	}

	private void checkMasterData() {
		MaskVersion mask = instances("ehome.operational:MaskVersion").stream()
			.map(MaskVersion.class::cast)
			.filter(m -> "MV-FFF0".equals(m.getExtId()))
			.findFirst().orElseThrow();
		assertEquals("IoT", mask.getMedium().getName());
		MaskConfiguration configuration = mask.getConfigurations().get(0);
		MaskResource resource = configuration.getResources().get(0);
		ResourceLocation location = resource.getLocation();
		assertEquals("RelativeMemoryByObjectType", location.getAddressSpace().getName());
		List<? extends LoadStep> steps = configuration.getProcedures().get(0).getSteps();
		assertEquals(List.of("Connect", "MasterReset", "Disconnect"), steps.stream()
			.map(s -> s.getKind().getName()).toList());
		assertEquals(Integer.valueOf(2), steps.get(1).getEraseCode());
		assertEquals(Integer.valueOf(0), steps.get(1).getChannelNumber());
	}

	private void checkNamedValues() {
		TLObject hvac = subtype("DPST-20-102");
		List<TLObject> values = list(hvac, "values");
		assertEquals(List.of(0, 1, 2, 3, 4), values.stream().map(v -> ((NamedValue) v).getValue()).toList());
		assertEquals(List.of("Auto", "Comfort", "Standby", "Economy", "Building Protection"),
			texts(values, Locale.ENGLISH));
		assertEquals("Komfort", ResKeyUtil.getTranslation(((NamedValue) values.get(1)).getText(), Locale.GERMAN));
		assertEquals("DPST-20-102_F-1-1", ((NamedValue) values.get(1)).getExtId());
		// The named values of the fixture's master data are those of 20.102, each once.
		assertEquals(5, instances("ehome.datapoint:NamedValue").stream()
			.filter(v -> String.valueOf(((NamedValue) v).getExtId()).startsWith("DPST-20-102_")).count());
		assertEquals(List.of(), instances("ehome.datapoint:NamedValue").stream()
			.filter(v -> String.valueOf(((NamedValue) v).getExtId()).startsWith("DPST-10-1_")).toList());

		// An enumeration naming one field of a composed value is no named value of the subtype.
		assertTrue(list(subtype("DPST-10-1"), "values").isEmpty());
	}

	private static List<String> texts(List<TLObject> values, Locale locale) {
		return values.stream().map(v -> ResKeyUtil.getTranslation(((NamedValue) v).getText(), locale)).toList();
	}

	private static TLObject subtype(String extId) {
		return instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> extId.equals(((DatapointSubtype) t).getExtId()))
			.findFirst().orElseThrow();
	}

	/**
	 * The send form of the group monitor offers the named values of the master data in the user's
	 * language, else in English; the frames are those Calimero's names produce.
	 */
	private void checkSendForm() {
		assertEquals(List.of("Automatik", "Komfort", "Bereitschaft", "Sparbetrieb", "Building Protection"),
			inLocale(Locale.GERMAN, () -> DatapointCodec.choices(HVAC_MODE)));
		assertEquals(List.of("Auto", "Comfort", "Standby", "Economy", "Building Protection"),
			inLocale(Locale.ENGLISH, () -> DatapointCodec.choices(HVAC_MODE)));
		assertEquals("00 80 01", HEX.formatHex(DatapointCodec.encodeWrite(HVAC_MODE, "Komfort")));
		assertEquals("00 80 01", HEX.formatHex(DatapointCodec.encodeWrite(HVAC_MODE, "Comfort")));
		// Calimero's name and the number give the same frame.
		assertEquals("00 80 01", HEX.formatHex(DatapointCodec.encodeWrite(HVAC_MODE, "comfort")));
		assertEquals("00 80 04", HEX.formatHex(DatapointCodec.encodeWrite(HVAC_MODE, "Building Protection")));
		assertEquals("00 80 03", HEX.formatHex(DatapointCodec.encodeWrite(HVAC_MODE, Integer.valueOf(3))));
		// A subtype without named values in the master data keeps Calimero's.
		assertEquals("Auto", inLocale(Locale.GERMAN, () -> DatapointCodec.choices(new Dpt(20, 105))).get(0));
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

	private static List<String> types(List<TLObject> objects) {
		return objects.stream().map(o -> TLModelUtil.qualifiedName(o.tType())).toList();
	}

	@SuppressWarnings("unchecked")
	static List<TLObject> list(TLObject obj, String attribute) {
		return List.copyOf((Collection<TLObject>) obj.tValueByName(attribute));
	}

	static List<TLObject> list(Collection<? extends TLObject> values) {
		return List.copyOf(values);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEts6Import.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

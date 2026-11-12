/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.HeatingCurve;
import de.haumacher.ehome.blocks.HeatingDemand;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.BlockTexts;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The virtual devices of a project (haumacher/ehome #75, homecontrol <code>spec/blocks.md</code>): the
 * export of the example instance of <code>spec/blocks.md</code> 4 to exactly its JSON with its
 * datapoints <code>block/wz-heating/*</code>, the refusals of the validator with the device's paths
 * (a missing input, a parameter out of bounds, a bound datapoint deleted), a function, a page element
 * and a chart series on the valve, and the proposal of a climate function with its dismissal by the
 * instance's identifier. The project is built by hand (no import): the test takes well under a second.
 */
@SuppressWarnings("javadoc")
public class TestBlockInstances extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

	/** The instance of spec/blocks.md 4, as the image carries it. */
	private static final String EXAMPLE = """
		[{"id": "wz-heating", "type": "org.homecontrol.room-controller", "version": "1.0",
		  "label": {"en": "Living room heating", "de": "Heizung Wohnzimmer"},
		  "inputs": {"temperature": ["1/3/0", "1/3/1"], "window": "wz.window", "mode": "1/3/5"},
		  "aggregation": {"temperature": "min"},
		  "outputs": {"valve": "1/3/30", "state": "wz.heating.state"},
		  "parameters": {"system": "radiator", "comfort": 21.0}}]
		""";

	private final List<TLObject> _types = new ArrayList<>();

	private TLObject _project;

	private TLObject _room;

	private TLObject _t1;

	private TLObject _t2;

	private TLObject _mode;

	private TLObject _valve;

	private TLObject _window;

	private TLObject _state;

	private TLObject _block;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject real = mainType(9);
			TLObject percent = mainType(5);
			TLObject hvac = mainType(20);
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Blocks");
			_project.tUpdateByName("extId", "P-TEST-75");
			_t1 = address("Temperatur Wand", 1, 3, 0, real);
			_t2 = address("Temperatur Decke", 1, 3, 1, real);
			_mode = address("Betriebsart", 1, 3, 5, hvac);
			_valve = address("Ventil", 1, 3, 30, percent);
			_valve.tUpdateByName("runtimeWritable", Boolean.TRUE);
			_project.tUpdateByName("groupAddresses", List.of(_t1, _t2, _mode, _valve));
			TLObject building = part("ehome.building:Building", "Haus", "P-TEST-75-B");
			_room = part("ehome.building:Room", "WZ", "P-TEST-75-WZ");
			building.tUpdateByName("parts", List.of(_room));
			_project.tUpdateByName("buildings", List.of(building));
			_window = application("wz.window", "boolean", false);
			_state = application("wz.heating.state", "enumeration", true);
			_project.tUpdateByName("applicationDatapoints", List.of(_window, _state));

			_block = BlockInstances.create(_project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()), _room,
				ResKey.builder().add(Locale.ENGLISH, "Living room heating").add(Locale.GERMAN, "Heizung Wohnzimmer")
					.build());
			_block.tUpdateByName("identifier", "wz-heating");
			TLObject temperature = BlockInstances.port(_block, "temperature");
			BlockInstances.bind(temperature, _t1);
			BlockInstances.bind(temperature, _t2);
			temperature.tUpdateByName("aggregation", "min");
			BlockInstances.bind(BlockInstances.port(_block, "window"), _window);
			BlockInstances.bind(BlockInstances.port(_block, "mode"), _mode);
			BlockInstances.bind(BlockInstances.port(_block, "valve"), _valve);
			BlockInstances.bind(BlockInstances.port(_block, "state"), _state);
			BlockInstances.parameter(_block, "system").tUpdateByName("value", Double.valueOf(0));
			BlockInstances.parameter(_block, "comfort").tUpdateByName("value", Double.valueOf(21));
			tx.commit();
		}
	}

	@Override
	protected void tearDown() throws Exception {
		SharedData.deleteProjects(List.of(_project));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject type : _types) {
				type.tDelete();
			}
			tx.commit();
		}
		_types.clear();
		super.tearDown();
	}

	/**
	 * The instance of spec/blocks.md 4 exports to exactly its JSON; its ports are datapoints of the
	 * image in the order of spec/blocks.md 6, with their types.
	 */
	public void testExportExample() throws Exception {
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		assertEquals(RuntimeImage.SCHEMA_VERSION, ((Map<?, ?>) image.getJson().get("image")).get("schemaVersion"));
		// The JSON values compared, the numbers as numbers (E-Home writes 21.0 as 21).
		assertEquals(numbers(Json.parse(EXAMPLE, 20)), numbers(Json.parse(image.getText(), 20) instanceof Map<?, ?> json
			? json.get("blocks") : null));

		ImageValidator.Directory directory = ImageValidator.directory(image.getText());
		assertEquals(ValueType.BOOLEAN, directory.type("block/wz-heating/enabled"));
		assertTrue(directory.writable("block/wz-heating/enabled"));
		assertEquals(ValueType.REAL, directory.type("block/wz-heating/comfort"));
		assertTrue(directory.writable("block/wz-heating/comfort"));
		assertEquals(ValueType.REAL, directory.type("block/wz-heating/valve"));
		assertFalse(directory.writable("block/wz-heating/valve"));
		assertEquals(ValueType.ENUMERATION, directory.type("block/wz-heating/state"));
		assertEquals(ValueType.REAL, directory.type("block/wz-heating/temperature"));
		assertNull("A single-datapoint input has no mirror.", directory.type("block/wz-heating/window"));
		assertNull("A parameter that is not adjustable has no mirror.", directory.type("block/wz-heating/xp"));

		// The project's view of the same datapoints: instance, adjustable parameters, outputs, aggregated inputs.
		List<String> ids = new ArrayList<>();
		for (TLObject datapoint : BlockInstances.datapoints(_project)) {
			ids.add(Pages.imageId(datapoint));
		}
		assertEquals(List.of("block/wz-heating/enabled", "block/wz-heating/comfort", "block/wz-heating/standby",
			"block/wz-heating/night", "block/wz-heating/frost", "block/wz-heating/valve", "block/wz-heating/switch",
			"block/wz-heating/demand", "block/wz-heating/activeSetpoint", "block/wz-heating/state",
			"block/wz-heating/p", "block/wz-heating/i", "block/wz-heating/d", "block/wz-heating/error",
			"block/wz-heating/filtered", "block/wz-heating/temperature"), ids);
		assertEquals(_block, image.elementAt("blocks[0].parameters.comfort").tContainer());
		assertEquals(BlockInstances.port(_block, "temperature"), image.elementAt("blocks[0].inputs.temperature[1]"));
	}

	/** A missing required input is the device's missingInput at its path. */
	public void testMissingTemperature() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (Object binding : (Collection<?>) BlockInstances.port(_block, "temperature").tValueByName("bindings")) {
				((TLObject) binding).tDelete();
			}
			tx.commit();
		}
		assertRefused("blocks[0].inputs.temperature",
			"block 'wz-heating': missingInput: the required input 'temperature' of org.homecontrol.room-controller is not bound");
	}

	/** A parameter outside its bounds is the device's invalidParameter; so is outputMin not below outputMax. */
	public void testParameterBounds() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			BlockInstances.parameter(_block, "xp").tUpdateByName("value", Double.valueOf(25));
			tx.commit();
		}
		assertRefused("blocks[0].parameters.xp", "block 'wz-heating': invalidParameter: xp: outside min..max");
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			BlockInstances.parameter(_block, "xp").tUpdateByName("value", Double.valueOf(4));
			BlockInstances.parameter(_block, "outputMin").tUpdateByName("value", Double.valueOf(60));
			BlockInstances.parameter(_block, "outputMax").tUpdateByName("value", Double.valueOf(50));
			tx.commit();
		}
		assertRefused("blocks[0].parameters.outputMin",
			"block 'wz-heating': invalidParameter: outputMin must be below outputMax");
	}

	/**
	 * A group address deleted after it was bound (as a re-import that drops it would) is a refusal
	 * naming the instance, the port and the address, never a silent drop.
	 */
	public void testDeletedAddress() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<Object> addresses = new ArrayList<>((Collection<?>) _project.tValueByName("groupAddresses"));
			addresses.remove(_t2);
			_project.tUpdateByName("groupAddresses", addresses);
			_t2.tDelete();
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertFalse(report.isValid());
		ImageReport.Refusal refusal = report.getRefusals().get(0);
		assertEquals("blocks[0].inputs.temperature[1]", refusal.path());
		assertTrue(refusal.reason(), refusal.reason().contains("wz-heating"));
		assertTrue(refusal.reason(), refusal.reason().contains("'temperature'"));
		assertTrue(refusal.reason(), refusal.reason().contains("1/3/1"));
		assertEquals(BlockInstances.port(_block, "temperature"), image.elementAt(refusal.path()));
		// The binding is still there, without datapoint: nothing was dropped.
		assertEquals(2, ((List<?>) BlockInstances.port(_block, "temperature").tValueByName("bindings")).size());
	}

	/**
	 * A function, a page element and a chart series on the valve: the function's role and the element
	 * name block/wz-heating/valve, the series the address the valve is bound to (the mirror keeps no
	 * history, spec/blocks.md 6), which gets the history the chart needs.
	 */
	public void testValveOnFunctionPageChart() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject valve = BlockInstances.port(_block, "valve");
			TLObject function = create("ehome.runtime:Function");
			function.tUpdateByName("name", ResKey.text("Ventil"));
			function.tUpdateByName("identifier", "wz-valve");
			function.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
			function.tUpdateByName("room", _room);
			function.tUpdateByName("value", valve);
			_project.tUpdateByName("functions", List.of(function));
			TLObject page = Pages.insert(_project, null, Pages.PAGE, null);
			TLObject section = Pages.insert(_project, page, Pages.SECTION, null);
			Pages.insert(_project, section, Pages.DATAPOINT, List.of(valve));
			TLObject chart = Pages.insert(_project, section, Pages.CHART, null);
			Pages.insert(_project, chart, Pages.SERIES, List.of(valve));
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		Map<?, ?> function = (Map<?, ?>) ((List<?>) image.getJson().get("functions")).get(0);
		assertEquals(Map.of("value", "block/wz-heating/valve"), function.get("datapoints"));
		List<?> elements = (List<?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) image.getJson()
			.get("ui")).get("pages")).get(0)).get("sections")).get(0)).get("elements");
		assertEquals("block/wz-heating/valve", ((Map<?, ?>) elements.get(0)).get("datapoint"));
		Map<?, ?> chart = (Map<?, ?>) ((Map<?, ?>) elements.get(1)).get("chart");
		assertEquals("1/3/30", ((Map<?, ?>) ((List<?>) chart.get("series")).get(0)).get("datapoint"));
		Map<?, ?> valveDatapoint = TestRuntimeImage.datapoint((List<?>) image.getJson().get("datapoints"), "1/3/30");
		assertNotNull("The chart derives a history for the bound address.", valveDatapoint.get("history"));
	}

	/**
	 * "Propose functions" proposes a climate function of the room controller; deleted, it is dismissed
	 * by the instance's identifier and not proposed again, also after the instance was renamed.
	 */
	public void testClimateProposal() throws Exception {
		TLObject function;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<TLObject> added = FunctionProposal.apply(_project);
			assertEquals(1, added.size());
			function = added.get(0);
			tx.commit();
		}
		assertEquals("climate", FunctionProposal.kind(function));
		assertEquals(FunctionProposal.BLOCK_CHANNEL + "wz-heating", function.tValueByName("channel"));
		assertEquals(_room, function.tValueByName("room"));
		assertEquals(BlockInstances.port(_block, "filtered"), function.tValueByName("temperature"));
		assertEquals(BlockInstances.port(_block, "activeSetpoint"), function.tValueByName("setpoint"));
		assertEquals(BlockInstances.parameter(_block, "comfort"), function.tValueByName("setpointCommand"));
		assertEquals(BlockInstances.port(_block, "demand"), function.tValueByName("heating"));
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		Map<?, ?> exported = (Map<?, ?>) ((List<?>) image.getJson().get("functions")).get(0);
		assertEquals(Map.of("temperature", "block/wz-heating/filtered", "setpoint", "block/wz-heating/activeSetpoint",
			"setpointCommand", "block/wz-heating/comfort", "heating", "block/wz-heating/demand"),
			exported.get("datapoints"));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			assertNull("Proposed once.", FunctionProposal.apply(_project).isEmpty() ? null : "again");
			assertNotNull(FunctionProposal.dismiss(function));
			_block.tUpdateByName("name", ResKey.text("Heizung"));
			assertEquals(List.of(), FunctionProposal.apply(_project));
			tx.commit();
		}
	}

	/**
	 * Review probe: a second instance reads the first one's filtered value (a block datapoint as an
	 * input), the first is renamed (its identifier stays, so the binding exports unchanged), a block
	 * reading its own mirror is refused as on the device, and the exported image runs in the
	 * simulation: the second controller follows the first one's filtered temperature.
	 */
	public void testProbeChainRenameSelfBinding() throws Exception {
		TLObject echo;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_t1.tUpdateByName("runtimeWritable", Boolean.TRUE);
			_t2.tUpdateByName("runtimeWritable", Boolean.TRUE);
			echo = BlockInstances.create(_project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()), _room,
				ResKey.text("Echo"));
			BlockInstances.bind(BlockInstances.port(echo, "temperature"), BlockInstances.port(_block, "filtered"));
			_block.tUpdateByName("name", ResKey.text("Ganz anders"));
			tx.commit();
		}
		assertEquals("wz-heating", _block.tValueByName("identifier"));
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		List<?> blocks = (List<?>) image.getJson().get("blocks");
		assertEquals(2, blocks.size());
		Map<?, ?> second = (Map<?, ?>) blocks.get(1);
		String echoId = (String) second.get("id");
		assertEquals("block/wz-heating/filtered", ((Map<?, ?>) second.get("inputs")).get("temperature"));

		long start = de.haumacher.ehome.rules.Values.daysOf(2026, 11, 4) * 86400 + 8 * 3600;
		try (de.haumacher.ehome.simulation.Simulation simulation = new de.haumacher.ehome.simulation.Simulation(
			(Map<?, ?>) Json.parse(image.getText(), 100), start, new de.haumacher.ehome.rules.ParameterStore())) {
			assertNull(simulation.start());
			assertEquals(de.haumacher.ehome.rules.DatapointModel.CommandStatus.OK,
				simulation.command("1/3/0", de.haumacher.ehome.rules.Val.ofReal(19.5)));
			assertEquals(de.haumacher.ehome.rules.DatapointModel.CommandStatus.OK,
				simulation.command("1/3/1", de.haumacher.ehome.rules.Val.ofReal(20.5)));
			simulation.advance(30 * 60_000);
			double filtered = simulation.value("block/wz-heating/filtered").real();
			assertEquals("min aggregation, filtered towards 19.5", 19.5, filtered, 0.05);
			assertEquals(simulation.value("block/wz-heating/filtered").real(),
				simulation.value("block/" + echoId + "/filtered").real(), 0.05);
		}

		// A block reading its own mirror: forbidden, at the device's path.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			BlockInstances.bind(BlockInstances.port(echo, "temperature"), BlockInstances.port(echo, "valve"));
			tx.commit();
		}
		ImageReport refused = RuntimeImage.build(_project, 1, NOW).validate();
		assertFalse(refused.isValid());
		assertEquals(refused.toString(), "blocks[1].inputs.temperature[1]", refused.getRefusals().get(0).path());
		assertTrue(refused.getRefusals().get(0).reason(), refused.getRefusals().get(0).reason().contains("forbidden"));
	}

	/**
	 * Every port, parameter and choice of every built-in type has its German label (the views are
	 * generic over the manifest, so a type without its texts would show English in a German UI).
	 */
	public void testGermanTexts() {
		for (BlockType type : BlockInstances.registry().types()) {
			Manifest m = type.manifest();
			assertNotNull(m.type(), BlockTexts.typeLabelDe(m.type()));
			for (Port port : m.ports()) {
				assertNotNull(m.type() + " " + port.id(), BlockTexts.label(m.type(), port.id(), null, "de"));
			}
			for (Param param : m.params()) {
				assertNotNull(m.type() + " " + param.id(), BlockTexts.label(m.type(), param.id(), null, "de"));
				for (Choice choice : param.choices()) {
					String id = param.id() + "." + choice.id();
					assertNotNull(m.type() + " " + id, BlockTexts.label(m.type(), id, null, "de"));
				}
			}
		}
	}

	/**
	 * A heating demand on the room controller's valve and a heating curve on the outside temperature
	 * and the demand's offset, whose summer switch feeds the demand back: E-Home's generic instances
	 * export them, its validator accepts the image, and the simulation runs the chain.
	 */
	public void testHeatingChain() throws Exception {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_t1.tUpdateByName("runtimeWritable", Boolean.TRUE);
			_t2.tUpdateByName("runtimeWritable", Boolean.TRUE);
			TLObject demand = BlockInstances.create(_project, BlockInstances.typeKey(HeatingDemand.INSTANCE.manifest()),
				_room, ResKey.text("Wärmebedarf"));
			demand.tUpdateByName("identifier", "hd");
			TLObject curve = BlockInstances.create(_project, BlockInstances.typeKey(HeatingCurve.INSTANCE.manifest()),
				_room, ResKey.text("Heizkurve"));
			curve.tUpdateByName("identifier", "hk");
			BlockInstances.bind(BlockInstances.port(demand, "valves"), BlockInstances.port(_block, "valve"));
			BlockInstances.bind(BlockInstances.port(demand, "summer"), BlockInstances.port(curve, "summer"));
			BlockInstances.parameter(demand, "offsetGain").tUpdateByName("value", Double.valueOf(1));
			BlockInstances.bind(BlockInstances.port(curve, "outside"), _t2);
			BlockInstances.bind(BlockInstances.port(curve, "offset"), BlockInstances.port(demand, "flowSetpointOffset"));
			BlockInstances.parameter(curve, "outsideFilter").tUpdateByName("value", Double.valueOf(0));
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		List<?> blocks = (List<?>) image.getJson().get("blocks");
		assertEquals(3, blocks.size());
		Map<?, ?> demand = (Map<?, ?>) blocks.get(1);
		assertEquals(HeatingDemand.TYPE, demand.get("type"));
		assertEquals(Map.of("valves", "block/wz-heating/valve", "summer", "block/hk/summer"), demand.get("inputs"));
		Map<?, ?> curve = (Map<?, ?>) blocks.get(2);
		assertEquals(Map.of("outside", "1/3/1", "offset", "block/hd/flowSetpointOffset"), curve.get("inputs"));

		long start = de.haumacher.ehome.rules.Values.daysOf(2026, 11, 4) * 86400 + 8 * 3600;
		try (de.haumacher.ehome.simulation.Simulation simulation = new de.haumacher.ehome.simulation.Simulation(
			(Map<?, ?>) Json.parse(image.getText(), 100), start, new de.haumacher.ehome.rules.ParameterStore())) {
			assertNull(simulation.start());
			// 1/3/0 is the room temperature, 1/3/1 doubles as the outside temperature here.
			assertEquals(de.haumacher.ehome.rules.DatapointModel.CommandStatus.OK,
				simulation.command("1/3/0", de.haumacher.ehome.rules.Val.ofReal(18.0)));
			assertEquals(de.haumacher.ehome.rules.DatapointModel.CommandStatus.OK,
				simulation.command("1/3/1", de.haumacher.ehome.rules.Val.ofReal(5.0)));
			simulation.advance(20 * 60_000);
			assertTrue(simulation.value("block/hd/level").real() > 20);
			assertTrue(simulation.value("block/hd/request").bool());
			assertTrue(simulation.value("block/hd/flowSetpointOffset").real() > 0);
			// slope 1.2 at room 20 and outside 5 is 38 °C; the demand's offset raises it.
			assertTrue(simulation.value("block/hk/flowSetpoint").real() > 38);
			assertFalse(simulation.value("block/hk/summer").bool());
		}
	}

	private static Object numbers(Object json) {
		if (json instanceof Number number) {
			return Double.valueOf(number.doubleValue());
		}
		if (json instanceof Map<?, ?> map) {
			Map<Object, Object> result = new LinkedHashMap<>();
			map.forEach((key, value) -> result.put(key, numbers(value)));
			return result;
		}
		if (json instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			list.forEach(value -> result.add(numbers(value)));
			return result;
		}
		return json;
	}

	private void assertRefused(String path, String reason) {
		ImageReport report = RuntimeImage.build(_project, 1, NOW).validate();
		assertFalse(report.isValid());
		assertEquals(report.toString(), path, report.getRefusals().get(0).path());
		assertEquals(reason, report.getRefusals().get(0).reason());
	}

	// ---- building the project ----------------------------------------------------------------

	private TLObject mainType(int number) {
		TLObject type = create("ehome.datapoint:DatapointMainType");
		type.tUpdateByName("number", Integer.valueOf(number));
		type.tUpdateByName("extId", "TEST-BLOCKS-" + number);
		_types.add(type);
		return type;
	}

	private static TLObject address(String name, int main, int middle, int sub, TLObject type) {
		TLObject address = create("ehome.logical:GroupAddress");
		address.tUpdateByName("name", name);
		address.tUpdateByName("address", new GA((main << 11) | (middle << 8) | sub, GroupAddressStyle.ThreeLevel));
		address.tUpdateByName("datapointTypes", List.of(type));
		return address;
	}

	private static TLObject application(String id, String type, boolean writable) {
		TLObject datapoint = create("ehome.runtime:ApplicationDatapoint");
		datapoint.tUpdateByName("identifier", id);
		datapoint.tUpdateByName("type",
			((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(type));
		datapoint.tUpdateByName("writable", Boolean.valueOf(writable));
		return datapoint;
	}

	private static TLObject part(String type, String name, String extId) {
		TLObject part = create(type);
		part.tUpdateByName("name", name);
		part.tUpdateByName("extId", extId);
		return part;
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBlockInstances.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

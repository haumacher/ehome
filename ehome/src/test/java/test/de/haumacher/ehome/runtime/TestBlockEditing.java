/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.BlockSet;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.HeatingCurve;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.PvSurplus;
import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.blocks.SunShading;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.BlockDiagnostics;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.BlockInstances.UnitFit;
import de.haumacher.ehome.runtime.BlockValues;
import de.haumacher.ehome.runtime.ExportRuntimeImageAction;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The views of the virtual devices (haumacher/ehome #78): a choice by its label in both languages, a
 * duration in the manifest's unit and back to ms, a time of day as a time and back, the values
 * applied from the inputs (the default removes the value, a value of no choice is refused), the
 * aggregation chosen from the port's, the unit filter of the bind dialog, the datapoints of the
 * virtual devices in the image summary, the blind proposed for a sun shading with its dismissal,
 * and the diagnostics read from the controller ({@link FakeController}, <code>GET /api/blocks</code>
 * in pages). The project is built by hand (no import): the test takes well under a second.
 */
@SuppressWarnings("javadoc")
public class TestBlockEditing extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

	private final List<TLObject> _cleanup = new ArrayList<>();

	private TLObject _project;

	private TLObject _room;

	private TLObject _temperature;

	private TLObject _lux;

	private TLObject _scene;

	private TLObject _percent;

	private TLObject _slatAddress;

	private TLObject _upDown;

	private TLObject _untyped;

	private TLObject _free;

	private TLObject _block;

	private FakeController _fake;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject real = mainType(9);
			TLObject celsius = subtype(real, 1);
			TLObject lux = subtype(real, 4);
			TLObject scaled = mainType(5);
			TLObject percent = subtype(scaled, 1);
			TLObject sceneMain = mainType(17);
			TLObject scene = subtype(sceneMain, 1);
			TLObject bool = mainType(1);
			TLObject upDown = subtype(bool, 8);

			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "Blocks #78");
			_project.tUpdateByName("extId", "P-TEST-78");
			_temperature = address("Temperatur", 1, 3, 0, celsius, false);
			_lux = address("Helligkeit", 1, 3, 1, lux, false);
			_scene = address("Szene", 1, 3, 2, scene, true);
			_percent = address("Behang Position", 1, 3, 3, percent, true);
			_slatAddress = address("Lamelle Position", 1, 3, 4, percent, true);
			_upDown = address("Behang Auf/Ab", 1, 3, 5, upDown, true);
			_untyped = address("Wert ohne Untertyp", 1, 3, 6, real, true);
			_project.tUpdateByName("groupAddresses",
				List.of(_temperature, _lux, _scene, _percent, _slatAddress, _upDown, _untyped));
			TLObject building = part("ehome.building:Building", "Haus", "P-TEST-78-B");
			_room = part("ehome.building:Room", "WZ", "P-TEST-78-WZ");
			building.tUpdateByName("parts", List.of(_room));
			_project.tUpdateByName("buildings", List.of(building));
			_free = create("ehome.runtime:ApplicationDatapoint");
			_free.tUpdateByName("identifier", "free.real");
			_free.tUpdateByName("type",
				((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier("real"));
			_free.tUpdateByName("writable", Boolean.TRUE);
			_project.tUpdateByName("applicationDatapoints", List.of(_free));

			_block = BlockInstances.create(_project, BlockInstances.typeKey(RoomController.INSTANCE.manifest()), _room,
				ResKey.text("Heizung"));
			_block.tUpdateByName("identifier", "wz-heating");
			BlockInstances.bind(BlockInstances.port(_block, "temperature"), _temperature);
			tx.commit();
		}
		_fake = new FakeController(TOKEN, 262144);
	}

	@Override
	protected void tearDown() throws Exception {
		_fake.stop();
		SharedData.deleteProjects(List.of(_project));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject obj : _cleanup) {
				if (obj.tValid()) {
					obj.tDelete();
				}
			}
			tx.commit();
		}
		_cleanup.clear();
		super.tearDown();
	}

	/**
	 * A choice is shown and chosen by its label in the user's language, never by its id: the room
	 * controller's heating system in German and English, its range as the labels.
	 */
	public void testChoiceLabels() {
		Param system = param(RoomController.INSTANCE, "system");
		String type = RoomController.TYPE;
		assertEquals(BlockValues.Kind.CHOICE, BlockValues.kind(system));
		assertEquals(List.of("Heizkörper", "Fußbodenheizung", "Konvektor", "Benutzerdefiniert"),
			inLocale(Locale.GERMAN, () -> BlockValues.choiceLabels(type, system)));
		assertEquals(List.of("Radiator", "Underfloor heating", "Convector", "Custom"),
			inLocale(Locale.ENGLISH, () -> BlockValues.choiceLabels(type, system)));
		assertEquals("Fußbodenheizung", inLocale(Locale.GERMAN, () -> BlockValues.display(type, system, 1)));
		assertEquals("Heizkörper, Fußbodenheizung, Konvektor, Benutzerdefiniert",
			inLocale(Locale.GERMAN, () -> BlockValues.range(type, system)));
		assertEquals("Konvektor", inLocale(Locale.GERMAN,
			() -> BlockValues.editValue(type, system, 2, TimeZone.getTimeZone("UTC"))));
		assertEquals(Double.valueOf(2), inLocale(Locale.GERMAN,
			() -> BlockValues.parse(type, system, "Konvektor", TimeZone.getTimeZone("UTC"))));
		assertEquals("", BlockValues.unit(system));
		// Every choice of every type has a label of its own in both languages, so the input can map back.
		for (BlockType blockType : BlockInstances.registry().types()) {
			for (Param decl : blockType.manifest().params()) {
				for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
					List<String> labels = inLocale(locale, () -> BlockValues.choiceLabels(blockType.manifest().type(), decl));
					assertEquals(decl.id() + " " + labels, labels.size(), new HashSet<>(labels).size());
				}
			}
		}
	}

	/**
	 * A duration is stored in ms and shown and entered in the unit of its manifest (min, s, h): both
	 * ways, for every duration of every type; the label's unit is the unit of the shown value.
	 */
	public void testDurations() {
		Param manual = param(SunShading.INSTANCE, "manualDuration");
		assertEquals(BlockValues.Kind.DURATION, BlockValues.kind(manual));
		assertEquals("min", BlockValues.unit(manual));
		assertEquals("120 min", BlockValues.display(SunShading.TYPE, manual, 7200000));
		assertEquals(Double.valueOf(120), BlockValues.editValue(SunShading.TYPE, manual, 7200000, TimeZone.getDefault()));
		assertEquals(Double.valueOf(5400000), BlockValues.parse(SunShading.TYPE, manual, Double.valueOf(90), TimeZone.getDefault()));
		assertEquals("1 min .. 1440 min", BlockValues.range(SunShading.TYPE, manual));
		Param filter = param(PvSurplus.INSTANCE, "surplusFilter");
		assertEquals("s", BlockValues.unit(filter));
		assertEquals("120 s", BlockValues.display(PvSurplus.TYPE, filter, 120000));
		assertEquals(Double.valueOf(1500), BlockValues.parse(PvSurplus.TYPE, filter, Double.valueOf(1.5), TimeZone.getDefault()));
		Param outside = param(HeatingCurve.INSTANCE, "outsideFilter");
		assertEquals("h", BlockValues.unit(outside));
		assertEquals("12 h", BlockValues.display(HeatingCurve.TYPE, outside, 12 * 3600000));
		// A real in minutes is no duration: shown as it is stored.
		Param tn = param(RoomController.INSTANCE, "tn");
		assertEquals(BlockValues.Kind.NUMBER, BlockValues.kind(tn));
		assertEquals("90 min", BlockValues.display(RoomController.TYPE, tn, 90));

		for (BlockType type : BlockInstances.registry().types()) {
			for (Param decl : type.manifest().params()) {
				if (decl.type() != ParamType.DURATION) {
					continue;
				}
				assertFalse(decl.id(), BlockValues.unit(decl).isEmpty());
				for (Double ms : bounds(decl)) {
					Object shown = BlockValues.editValue(type.manifest().type(), decl, ms.doubleValue(), TimeZone.getDefault());
					assertEquals(type.manifest().type() + " " + decl.id(), ms,
						BlockValues.parse(type.manifest().type(), decl, shown, TimeZone.getDefault()));
				}
			}
		}
	}

	/** A time of day is a time on the input and the seconds since midnight in the model, in any zone. */
	public void testTimeOfDay() {
		Param time = param(RoomController.INSTANCE, "protectionTime");
		assertEquals(BlockValues.Kind.TIME, BlockValues.kind(time));
		assertEquals("10:00", BlockValues.display(RoomController.TYPE, time, 36000));
		assertEquals("06:30:15", BlockValues.display(RoomController.TYPE, time, 6 * 3600 + 30 * 60 + 15));
		for (String zone : List.of("UTC", "Europe/Berlin", "America/New_York")) {
			TimeZone tz = TimeZone.getTimeZone(zone);
			Date shown = (Date) BlockValues.editValue(RoomController.TYPE, time, 36000 + 90, tz);
			Calendar calendar = Calendar.getInstance(tz);
			calendar.setTime(shown);
			assertEquals(zone, 10, calendar.get(Calendar.HOUR_OF_DAY));
			assertEquals(zone, 1, calendar.get(Calendar.MINUTE));
			assertEquals(zone, Double.valueOf(36090), BlockValues.parse(RoomController.TYPE, time, shown, tz));
		}
	}

	/**
	 * The values the inputs apply: a choice by its label, a duration in its unit; the default and an
	 * empty input remove the value (the image leaves it out); a label of no choice is refused with a
	 * reason and changes nothing.
	 */
	public void testApplyValues() {
		TLObject system = BlockInstances.parameter(_block, "system");
		TLObject cycle = BlockInstances.parameter(_block, "cycle");
		TimeZone utc = TimeZone.getTimeZone("UTC");
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			assertNull(inLocale(Locale.GERMAN, () -> BlockInstances.applyValue(system, "Fußbodenheizung", utc)));
			assertEquals(Double.valueOf(1), system.tValueByName("value"));
			assertNull(BlockInstances.applyValue(cycle, Double.valueOf(45), utc));
			assertEquals(Double.valueOf(2700000), cycle.tValueByName("value"));
			String refused = inLocale(Locale.GERMAN, () -> BlockInstances.applyValue(system, "underfloor", utc));
			assertNotNull("The id is no label.", refused);
			assertTrue(refused, refused.contains("Heizsystem"));
			assertEquals(Double.valueOf(1), system.tValueByName("value"));
			tx.commit();
		}
		Map<?, ?> parameters = (Map<?, ?>) block(RuntimeImage.build(_project, 1, NOW)).get("parameters");
		assertEquals("underfloor", parameters.get("system"));
		assertEquals(Long.valueOf(2700000), parameters.get("cycle"));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			assertNull(inLocale(Locale.ENGLISH, () -> BlockInstances.applyValue(system, "Radiator", utc)));
			assertNull("The default removes the value.", system.tValueByName("value"));
			assertNull(BlockInstances.applyValue(cycle, null, utc));
			assertNull(cycle.tValueByName("value"));
			tx.commit();
		}
		assertNull(block(RuntimeImage.build(_project, 1, NOW)).get("parameters"));
	}

	/**
	 * The aggregation of an input binding several datapoints is chosen from the port's by label; its
	 * default removes it; a single-datapoint input has none.
	 */
	public void testAggregation() {
		TLObject temperature = BlockInstances.port(_block, "temperature");
		TLObject setpoint = BlockInstances.port(_block, "setpoint");
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			assertNull(BlockInstances.applyAggregation(temperature, BlockValues.aggregationLabel(Aggregation.MIN)));
			assertEquals("min", temperature.tValueByName("aggregation"));
			assertNull(BlockInstances.applyAggregation(temperature, BlockValues.aggregationLabel(Aggregation.MEAN)));
			assertNull("The default removes the choice.", temperature.tValueByName("aggregation"));
			assertNotNull(BlockInstances.applyAggregation(temperature, BlockValues.aggregationLabel(Aggregation.ANY)));
			assertNotNull(BlockInstances.applyAggregation(setpoint, BlockValues.aggregationLabel(Aggregation.MIN)));
			assertNull(temperature.tValueByName("aggregation"));
			tx.commit();
		}
		assertEquals("Mittelwert", inLocale(Locale.GERMAN, () -> BlockValues.aggregationLabel(Aggregation.MEAN)));
	}

	/**
	 * The bind dialog offers datapoints of the port's unit: a °C input no lux or % address and no scene
	 * number, a % output no scene number; a datapoint without unit only where nothing says what it
	 * measures, with a hint.
	 */
	public void testUnitFilter() {
		TLObject temperature = BlockInstances.port(_block, "temperature");
		List<TLObject> options = BlockInstances.bindingOptions(temperature);
		assertFalse("Bound already.", options.contains(_temperature));
		assertFalse(options.contains(_lux));
		assertFalse(options.contains(_percent));
		assertFalse(options.contains(_scene));
		assertTrue(options.contains(_untyped));
		assertTrue(options.contains(_free));
		assertEquals(UnitFit.SAME, BlockInstances.unitFit(BlockInstances.portDecl(temperature), _temperature));
		assertEquals(UnitFit.OTHER, BlockInstances.unitFit(BlockInstances.portDecl(temperature), _lux));
		assertEquals(UnitFit.UNKNOWN, BlockInstances.unitFit(BlockInstances.portDecl(temperature), _untyped));
		assertEquals("lx", BlockInstances.bindingUnit(temperature, _lux));
		String hint = BlockInstances.bindingUnit(temperature, _free);
		assertTrue(hint, hint.contains("°C"));

		TLObject valve = BlockInstances.port(_block, "valve");
		List<TLObject> outputs = BlockInstances.bindingOptions(valve);
		assertTrue(outputs.contains(_percent));
		assertFalse("A scene number is no position.", outputs.contains(_scene));
		assertEquals(UnitFit.OTHER, BlockInstances.unitFit(BlockInstances.portDecl(valve), _scene));
		assertFalse(outputs.contains(_temperature));
		assertTrue(outputs.contains(_free));

		// A rule parameter by its unit; a duration carries ms; without a unit: to check.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLEnumeration types = (TLEnumeration) TLModelUtil.findType("ehome.runtime:RuleParameterType");
			TLObject celsius = ruleParameter(types, "real", "°C");
			TLObject delay = ruleParameter(types, "duration", "min");
			TLObject bare = ruleParameter(types, "real", null);
			Port decl = BlockInstances.portDecl(temperature);
			assertEquals(UnitFit.SAME, BlockInstances.unitFit(decl, celsius));
			assertEquals("ms", BlockInstances.datapointUnit(delay));
			assertEquals(UnitFit.OTHER, BlockInstances.unitFit(decl, delay));
			assertEquals(UnitFit.UNKNOWN, BlockInstances.unitFit(decl, bare));
			tx.rollback();
		}

		// A port without unit (a truth value) takes any datapoint of its type.
		TLObject window = BlockInstances.port(_block, "window");
		assertEquals(UnitFit.SAME, BlockInstances.unitFit(BlockInstances.portDecl(window), _upDown));
		assertTrue(BlockInstances.bindingOptions(window).contains(_upDown));
		// The datapoints of a virtual device: by the manifest's unit; a duration in ms.
		assertEquals("%", BlockInstances.datapointUnit(valve));
		assertEquals("ms", BlockInstances.datapointUnit(BlockInstances.parameter(_block, "cycle")));
	}

	/**
	 * Review probe: the unit filter across virtual devices. A heating demand's valves (%) are offered
	 * a room controller's valve output (%), its heat requests (no unit) the room controller's demand,
	 * and not its filtered temperature (°C); the house mode's hvacMode reaches a room controller's
	 * mode (an enumeration without unit).
	 */
	public void testProbeUnitsAcrossBlocks() {
		TLObject demand;
		TLObject house;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			demand = BlockInstances.create(_project, BlockInstances.typeKey(de.haumacher.ehome.blocks.HeatingDemand.INSTANCE.manifest()),
				_room, ResKey.text("Bedarf"));
			house = BlockInstances.create(_project, BlockInstances.typeKey(de.haumacher.ehome.blocks.HouseMode.INSTANCE.manifest()),
				_room, ResKey.text("Haus"));
			tx.commit();
		}
		List<TLObject> valves = BlockInstances.bindingOptions(BlockInstances.port(demand, "valves"));
		assertTrue(valves.contains(BlockInstances.port(_block, "valve")));
		assertFalse(valves.contains(BlockInstances.port(_block, "filtered")));
		List<TLObject> demands = BlockInstances.bindingOptions(BlockInstances.port(demand, "demands"));
		assertTrue(demands.contains(BlockInstances.port(_block, "demand")));
		List<TLObject> modes = BlockInstances.bindingOptions(BlockInstances.port(_block, "mode"));
		assertTrue(modes.contains(BlockInstances.port(house, "hvacMode")));
		assertFalse("A block never reads its own output.", modes.contains(BlockInstances.port(_block, "state")));
	}

	/** The image summary counts the datapoints of the virtual devices with the image's own. */
	public void testSummaryCount() {
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		int listed = ((List<?>) image.getJson().get("datapoints")).size();
		int blocks = BlockSet.impliedDatapoints(image.getJson().get("blocks"), BlockInstances.registry()).size();
		assertEquals(BlockInstances.datapoints(_project).size(), blocks);
		assertTrue(blocks > 10);
		String summary = ExportRuntimeImageAction.summary(image);
		assertTrue(summary, summary.startsWith(text(I18NConstants.EXPORT_VALID__DATAPOINTS_LOCATIONS_FUNCTIONS_SETTINGS_BYTES
			.fill(Integer.valueOf(listed + blocks), Integer.valueOf(((List<?>) image.getJson().get("locations")).size()),
				Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(image.getBytes().length)))));
		assertTrue(summary, summary.contains(text(I18NConstants.EXPORT_BLOCKS__BLOCKS_DATAPOINTS.fill(Integer.valueOf(1),
			Integer.valueOf(blocks)))));
	}

	/**
	 * A sun shading is proposed as a blind on the datapoints its outputs command, keyed by its
	 * identifier; deleted, it stays dismissed, also after a rename. A shading without its up/down
	 * output bound proposes nothing.
	 */
	public void testBlindProposal() {
		TLObject shading;
		TLObject function;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			shading = BlockInstances.create(_project, BlockInstances.typeKey(SunShading.INSTANCE.manifest()), _room,
				ResKey.text("Beschattung Süd"));
			shading.tUpdateByName("identifier", "shade-south");
			BlockInstances.create(_project, BlockInstances.typeKey(SunShading.INSTANCE.manifest()), _room,
				ResKey.text("Beschattung Nord"));
			BlockInstances.bind(BlockInstances.port(shading, "upDown"), _upDown);
			BlockInstances.bind(BlockInstances.port(shading, "position"), _percent);
			BlockInstances.bind(BlockInstances.port(shading, "slat"), _slatAddress);
			List<TLObject> added = FunctionProposal.apply(_project);
			List<String> kinds = new ArrayList<>();
			for (TLObject f : added) {
				kinds.add(FunctionProposal.kind(f));
			}
			assertEquals("The room controller's climate, the bound shading's blind.", List.of("climate", "blind"), kinds);
			function = added.get(1);
			tx.commit();
		}
		assertEquals(FunctionProposal.BLOCK_CHANNEL + "shade-south", function.tValueByName("channel"));
		assertEquals(_room, function.tValueByName("room"));
		assertEquals(_upDown, function.tValueByName("move"));
		assertNull(function.tValueByName("stop"));
		assertEquals(_percent, function.tValueByName("position"));
		assertEquals(_percent, function.tValueByName("positionCommand"));
		assertEquals(_slatAddress, function.tValueByName("slat"));
		assertEquals(_slatAddress, function.tValueByName("slatCommand"));
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			assertEquals(List.of(), FunctionProposal.apply(_project));
			assertNotNull(FunctionProposal.dismiss(function));
			shading.tUpdateByName("name", ResKey.text("Markise"));
			assertEquals("Dismissed by block:shade-south.", List.of(), FunctionProposal.apply(_project));
			tx.commit();
		}
	}

	/**
	 * The diagnostics of the deployed instance from the controller, found on the second page of
	 * <code>GET /api/blocks</code>: status, calls, outputs and inputs with values, the inhabitant's
	 * parameter, the state; a fault with its reason.
	 */
	public void testDiagnostics() throws Exception {
		connect(_fake.getPort());
		_fake.store(RuntimeImage.build(_project, 3, NOW).getText());
		Map<String, Object> ours = entry("wz-heating", "running");
		_fake.setBlocks(List.of(entry("other", "running"), ours), 1);
		String text = inLocale(Locale.ENGLISH, () -> BlockDiagnostics.read(_block));
		assertEquals(2, _fake.getBlockRequests());
		assertTrue(text, text.startsWith(en(I18NConstants.BLOCK_DIAG_RUNNING__REVISION.fill(Long.valueOf(3)))));
		assertTrue(text, text.contains(en(I18NConstants.BLOCK_DIAG_CALLS__CALLS_TIME_LONGEST_ERRORS.fill("1503", "4", "1", "0"))));
		assertTrue(text, text.contains(en(I18NConstants.BLOCK_DIAG_CALL_PERIOD__AGO.fill("0 s"))));
		assertTrue(text, text.contains("  Valve: 37 % → 1/3/30"));
		assertTrue(text, text.contains("  P term: 3.3 %"));
		assertTrue(text, text.contains("  Temperature: 20.9 °C ← 1/3/0, 1/3/1 ("
			+ en(I18NConstants.BLOCK_AGGREGATION_MIN) + ")"));
		assertFalse("An input without binding says nothing.", text.contains("Window open"));
		assertTrue(text, text.contains(en(I18NConstants.BLOCK_DIAG_OVERRIDDEN__PARAMETER_VALUE_IMAGE.fill("Comfort",
			"22 °C", "21 °C"))));
		assertTrue(text, text.contains(en(I18NConstants.BLOCK_DIAG_TIMERS__TIMERS.fill(
			en(I18NConstants.BLOCK_DIAG_TIMER__TIMER_IN.fill("pwm", "3 min"))))));
		assertTrue(text, text.contains(en(I18NConstants.BLOCK_DIAG_STATE__FIELDS.fill("integral = 23.4, filtered = 20.9"))));
		String german = inLocale(Locale.GERMAN, () -> BlockDiagnostics.read(_block));
		assertTrue(german, german.contains("  Ventil: 37 % → 1/3/30"));

		Map<String, Object> fault = entry("wz-heating", "fault");
		fault.put("fault", Map.of("reason", "trap: division by zero", "ms", Long.valueOf(30211)));
		fault.put("lastError", Map.of("code", "fault", "text", "trap", "ms", Long.valueOf(30211)));
		_fake.setBlocks(List.of(fault), 4);
		text = inLocale(Locale.ENGLISH, () -> BlockDiagnostics.read(_block));
		assertTrue(text, text.startsWith(en(I18NConstants.BLOCK_DIAG_FAULT__REASON_AGO.fill("trap: division by zero", "60 s"))));
		assertTrue(text, text.contains(en(I18NConstants.BLOCK_DIAG_LAST_ERROR__CODE_TEXT_AGO.fill("fault", "trap", "60 s"))));
	}

	/**
	 * Where there is nothing to diagnose, the reason is said: no controller, a controller that cannot
	 * be reached, no image, an image without the instance.
	 */
	public void testNotDeployed() throws Exception {
		assertEquals(text(I18NConstants.BLOCK_DIAG_NO_CONTROLLER), BlockDiagnostics.read(_block));
		connect(_fake.getPort());
		String text = BlockDiagnostics.read(_block);
		assertTrue(text, text.startsWith(text(I18NConstants.BLOCK_DIAG_NOT_DEPLOYED__STATUS.fill(""))));
		_fake.store(RuntimeImage.build(_project, 2, NOW).getText());
		_fake.setBlocks(List.of(entry("other", "running")), 4);
		assertEquals(text(I18NConstants.BLOCK_DIAG_NOT_IN_IMAGE__ID_REVISION.fill("wz-heating", Long.valueOf(2))),
			BlockDiagnostics.read(_block));
		FakeController gone = new FakeController(TOKEN, 1024);
		int port = gone.getPort();
		gone.stop();
		connect(port);
		text = BlockDiagnostics.read(_block);
		assertTrue(text, text.startsWith(text(I18NConstants.BLOCK_DIAG_UNREACHABLE__STATUS.fill(""))));
	}

	// ---- helpers ---------------------------------------------------------------------------

	private Map<String, Object> entry(String id, String status) {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("id", id);
		block.put("type", RoomController.TYPE);
		block.put("version", "1.0");
		block.put("enabled", Boolean.TRUE);
		block.put("enabledAdjustable", Boolean.TRUE);
		block.put("enabledOverridden", Boolean.FALSE);
		block.put("status", status);
		block.put("fault", null);
		block.put("calls", Long.valueOf(1503));
		block.put("lastCall", Map.of("kind", "period", "ms", Long.valueOf(90000)));
		block.put("timeMs", Long.valueOf(4));
		block.put("longestMs", Long.valueOf(1));
		block.put("errors", Long.valueOf(0));
		block.put("lastError", null);
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("integral", Double.valueOf(23.4));
		state.put("filtered", Double.valueOf(20.9));
		block.put("state", state);
		block.put("inputs", List.of(
			map("id", "temperature", "datapoints", List.of("1/3/0", "1/3/1"), "aggregation", "min", "value",
				Double.valueOf(20.9), "quality", "good"),
			map("id", "window", "datapoints", List.of(), "value", null, "quality", "unknown")));
		block.put("outputs", List.of(
			map("id", "valve", "datapoint", "1/3/30", "value", Long.valueOf(37), "unit", "%"),
			map("id", "p", "datapoint", null, "value", Double.valueOf(3.3), "diagnostic", Boolean.TRUE, "unit", "%")));
		block.put("timers", List.of(map("id", "pwm", "remaining_ms", Long.valueOf(230000))));
		block.put("parameters", List.of(
			map("id", "comfort", "label", "Comfort", "type", "real", "unit", "°C", "adjustable", Boolean.TRUE,
				"overridden", Boolean.TRUE, "value", Long.valueOf(22), "default", Long.valueOf(21)),
			map("id", "standby", "label", "Standby", "type", "real", "unit", "°C", "adjustable", Boolean.TRUE,
				"overridden", Boolean.FALSE, "value", Long.valueOf(19), "default", Long.valueOf(19))));
		return block;
	}

	private static Map<String, Object> map(Object... entries) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < entries.length; i += 2) {
			result.put((String) entries[i], entries[i + 1]);
		}
		return result;
	}

	private void connect(int port) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject controller = create("ehome.runtime:Controller");
			controller.tUpdateByName("name", "Fake #78");
			controller.tUpdateByName("host", "127.0.0.1");
			controller.tUpdateByName("port", Integer.valueOf(port));
			controller.tUpdateByName("token", TOKEN);
			_cleanup.add(controller);
			_project.tUpdateByName("controller", controller);
			tx.commit();
		}
	}

	private static Map<?, ?> block(RuntimeImage image) {
		return (Map<?, ?>) ((List<?>) image.getJson().get("blocks")).get(0);
	}

	private static List<Double> bounds(Param decl) {
		List<Double> result = new ArrayList<>();
		result.add(Double.valueOf(decl.defaultValue()));
		if (decl.min() != null) {
			result.add(decl.min());
		}
		if (decl.max() != null) {
			result.add(decl.max());
		}
		return result;
	}

	private static Param param(BlockType type, String id) {
		Manifest m = type.manifest();
		return m.params().get(m.param(id));
	}

	private TLObject mainType(int number) {
		TLObject type = create("ehome.datapoint:DatapointMainType");
		type.tUpdateByName("number", Integer.valueOf(number));
		type.tUpdateByName("extId", "TEST-78-" + number);
		_cleanup.add(type);
		return type;
	}

	private static TLObject subtype(TLObject main, int number) {
		TLObject type = create("ehome.datapoint:DatapointSubtype");
		type.tUpdateByName("number", Integer.valueOf(number));
		type.tUpdateByName("extId", "TEST-78-" + main.tValueByName("number") + "." + number);
		List<Object> subtypes = new ArrayList<>((Collection<?>) main.tValueByName("subtypes"));
		subtypes.add(type);
		main.tUpdateByName("subtypes", subtypes);
		return type;
	}

	private static TLObject address(String name, int main, int middle, int sub, TLObject type, boolean writable) {
		TLObject address = create("ehome.logical:GroupAddress");
		address.tUpdateByName("name", name);
		address.tUpdateByName("address", new GA((main << 11) | (middle << 8) | sub, GroupAddressStyle.ThreeLevel));
		address.tUpdateByName("datapointTypes", List.of(type));
		if (writable) {
			address.tUpdateByName("runtimeWritable", Boolean.TRUE);
		}
		return address;
	}

	private static TLObject ruleParameter(TLEnumeration types, String type, String unit) {
		TLObject parameter = create("ehome.runtime:RuleParameter");
		parameter.tUpdateByName("identifier", "p");
		parameter.tUpdateByName("type", types.getClassifier(type));
		parameter.tUpdateByName("unit", unit);
		return parameter;
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

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	private static String en(ResKey key) {
		return inLocale(Locale.ENGLISH, () -> text(key));
	}

	private static <T> T inLocale(Locale locale, Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return value.get();
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBlockEditing.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.HistoryConfig;
import de.haumacher.ehome.runtime.HistoryConfig.Archive;
import de.haumacher.ehome.runtime.HistoryConfig.History;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The history of a datapoint derived from the charts of the control pages, set by hand, migrated and
 * counted against the budget (haumacher/ehome #73, homecontrol #56): the acceptance tests of the
 * issue and the refusals of the archives by the image's check.
 *
 * <p>
 * The project is built without an ETS import: group addresses of two datapoint main types created for
 * the test (9, a real value; 1, a truth value) and an application datapoint.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestHistoryConfig extends BasicTestCase {

	private static final java.time.Instant NOW = java.time.Instant.parse("2026-11-03T12:00:00Z");

	/** Acceptance 1: 24 h at 15 min in the raw ring, 30 days hourly, a year daily. */
	private static final String YEAR_CHART_HISTORY = """
		{"retention": 86400, "spacing": 900, "archives": [
		  {"resolution": 3600, "retention": 2592000},
		  {"resolution": 86400, "retention": 31536000}]}
		""";

	private TLObject _project;

	private final List<TLObject> _types = new ArrayList<>();

	private TLObject _temperature;

	private TLObject _humidity;

	private TLObject _meter;

	private TLObject _window;

	private TLObject _counter;

	private TLObject _section;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject real = mainType(9);
			TLObject bool = mainType(1);
			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", "History");
			_project.tUpdateByName("extId", "P-TEST-73");
			_temperature = address("Temperatur", 1, 3, 1, real);
			_humidity = address("Feuchte", 1, 3, 2, real);
			_meter = address("Zähler", 1, 3, 3, real);
			_window = address("Fenster", 1, 3, 4, bool);
			_project.tUpdateByName("groupAddresses", List.of(_temperature, _humidity, _meter, _window));
			_counter = RuleFunctions.create(_project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			_counter.tUpdateByName("identifier", "counter");
			_counter.tUpdateByName("type", classifier("DatapointValueType", "unsigned"));
			TLObject page = Pages.insert(_project, null, Pages.PAGE, null);
			page.tUpdateByName("identifier", "climate");
			_section = Pages.insert(_project, page, Pages.SECTION, null);
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
	 * Acceptance 1: a chart with the default range 24 h and the presets up to a year on a temperature
	 * gives the raw ring for the 24 h (48 points: 30 min, rounded down to 15 min) and an archive each
	 * for the longer presets: 7 d (3.5 h, rounded down to 1 h) and 30 d (18.75 h, 1 h) share the hourly
	 * archive kept for 30 days, a year (7.6 d) takes the daily one.
	 */
	public void testYearChart() throws Exception {
		chart("day", List.of("day", "week", "month", "year"), "mean", _temperature);
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		assertEquals(Json.write(Json.parse(YEAR_CHART_HISTORY, 10)), Json.write(history(image, "1/3/1")));
		// 97 samples, 721 and 366 slots.
		assertEquals(97 * 8 + 721 * 32 + 366 * 32, HistoryConfig.bytes(_temperature));
		assertEquals(35560, HistoryConfig.bytes(_temperature));
		assertFalse(HistoryConfig.isManual(_temperature));
		// A datapoint no chart draws keeps none.
		assertNull(history(image, "1/3/2"));
		assertNull(HistoryConfig.effective(_humidity));
	}

	/**
	 * Acceptance 2: two charts on the same datapoint are united: the finest resolution per range (the
	 * hour of the first chart at 1 min, the day of the second at 15 min: one raw ring at 1 min for the
	 * day), the longest retention per resolution.
	 */
	public void testUnion() throws Exception {
		chart("hour", List.of(), null, _temperature);
		chart("day", List.of("day", "week", "year"), null, _temperature);
		History history = HistoryConfig.derived(_temperature);
		assertEquals(new History(86400, 60, List.of(new Archive(3600, 7 * 86400), new Archive(86400, 365 * 86400))),
			history);
		assertEquals(1441 * 8 + 169 * 32 + 366 * 32, history.bytes());
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		assertEquals(Json.write(Json.parse("""
			{"retention": 86400, "spacing": 60, "archives": [
			  {"resolution": 3600, "retention": 604800}, {"resolution": 86400, "retention": 31536000}]}
			""", 10)), Json.write(history(image, "1/3/1")));
	}

	/**
	 * The consumption bars of a meter: per range a bar of its own width (24 h: 1 h, 7 d and 30 d: 1 d,
	 * a year: a twelfth, rounded down to 1 d) over the bars and one more; the daily archive reaches the
	 * 13 twelfths of the year bars, which covers the week and the month as well.
	 */
	public void testConsumptionBars() throws Exception {
		chart("day", List.of("day", "week", "month", "year"), "consumption", _meter);
		assertEquals(new History(25 * 3600, 3600, List.of(new Archive(86400, 13 * 2628000L))),
			HistoryConfig.derived(_meter));
		assertTrue(RuntimeImage.build(_project, 1, NOW).validate().isValid());
		// A truth value has no bars (the image's check refuses its consumption): its on/off strip needs the
		// line's resolution.
		chart("week", List.of(), "consumption", _window);
		assertEquals(new History(3600, 900, List.of(new Archive(3600, 7 * 86400))), HistoryConfig.derived(_window));
	}

	/**
	 * A function's sparkline and sheet keep 24 hours at 15 minutes of a datapoint a chart draws only over
	 * long ranges; without the function the raw ring only feeds the archive (the step below its
	 * resolution, for one slot).
	 */
	public void testFunctionNeed() throws Exception {
		chart("month", List.of(), null, _humidity);
		assertEquals(new History(3600, 900, List.of(new Archive(3600, 30 * 86400))), HistoryConfig.derived(_humidity));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject sensor = create("ehome.runtime:Function");
			sensor.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Feuchte").build());
			sensor.tUpdateByName("identifier", "humidity");
			sensor.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
			sensor.tUpdateByName("value", _humidity);
			_project.tUpdateByName("functions", List.of(sensor));
			tx.commit();
		}
		assertEquals(new History(86400, 900, List.of(new Archive(3600, 30 * 86400))), HistoryConfig.derived(_humidity));
	}

	/**
	 * The derivation's rules for the device's limits: an archive beyond 2048 slots takes the next
	 * coarser step; more than four archives merge the finest into the next; an archive a finer store
	 * reaches as far is dropped; the raw ring's spacing stays below the finest archive.
	 */
	public void testLimits() {
		// 30 days at 15 min would be 2881 slots: hourly.
		assertEquals(new History(3600, 900, List.of(new Archive(3600, 30 * 86400))),
			HistoryConfig.derive(List.of(new HistoryConfig.Need(900, 30 * 86400)), false));
		// The hourly archive reaches as far as the daily one: the daily one is dropped.
		assertEquals(new History(3600, 900, List.of(new Archive(3600, 30 * 86400))),
			HistoryConfig.derive(List.of(new HistoryConfig.Need(3600, 30 * 86400), new HistoryConfig.Need(86400, 8 * 86400)),
				false));
		// The raw ring at 1 h below an hourly archive: 15 min.
		assertEquals(new History(90000, 900, List.of(new Archive(3600, 7 * 86400))),
			HistoryConfig.derive(List.of(new HistoryConfig.Need(3600, 90000), new HistoryConfig.Need(3600, 7 * 86400)),
				false));
		// An archive at 1 min would be beyond the slots for any coverage beyond the raw ring's: 5 min.
		assertEquals(List.of(new Archive(300, 6 * 86400)),
			HistoryConfig.derive(List.of(new HistoryConfig.Need(60, 3 * 86400), new HistoryConfig.Need(300, 6 * 86400)),
				false).archives());
		// Five archives (only with a resolution off the ladder; the charts give at most two): the finest
		// merges into the next coarser one.
		History five = HistoryConfig.derive(List.of(new HistoryConfig.Need(300, 3 * 86400),
			new HistoryConfig.Need(900, 6 * 86400), new HistoryConfig.Need(1800, 10 * 86400),
			new HistoryConfig.Need(3600, 30 * 86400), new HistoryConfig.Need(86400, 365 * 86400)), false);
		assertEquals(new History(900, 300, List.of(new Archive(900, 6 * 86400), new Archive(1800, 10 * 86400),
			new Archive(3600, 30 * 86400), new Archive(86400, 365 * 86400))), five);
		assertNull(HistoryConfig.derive(List.of(), true));
	}

	/**
	 * Acceptance 3: a history set by hand replaces the derived one in the image entirely, the derived
	 * one stays visible beside it, and a reset returns to it.
	 */
	public void testOverride() throws Exception {
		chart("day", List.of("day", "week", "month", "year"), "mean", _temperature);
		String derived = HistoryConfig.describeDerived(_temperature);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			HistoryConfig.setManual(_temperature);
			tx.commit();
		}
		// It starts from the derived one.
		assertTrue(HistoryConfig.isManual(_temperature));
		assertEquals(Json.write(Json.parse(YEAR_CHART_HISTORY, 10)),
			Json.write(history(RuntimeImage.build(_project, 1, NOW), "1/3/1")));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_temperature.tUpdateByName("historyRetention", Integer.valueOf(172800));
			_temperature.tUpdateByName("historySpacing", Integer.valueOf(300));
			List<TLObject> archives = HistoryConfig.manualArchives(_temperature);
			archives.get(0).tDelete();
			archives.get(1).tUpdateByName("retention", Integer.valueOf(2 * 365 * 86400));
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		assertEquals(Json.write(Json.parse("""
			{"retention": 172800, "spacing": 300, "archives": [{"resolution": 86400, "retention": 63072000}]}
			""", 10)), Json.write(history(image, "1/3/1")));
		// The derived one, beside it.
		assertEquals(derived, HistoryConfig.describeDerived(_temperature));
		assertTrue(HistoryConfig.describeEffective(_temperature),
			HistoryConfig.describeEffective(_temperature).contains("1 d for 730 d"));

		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			HistoryConfig.reset(_temperature);
			tx.commit();
		}
		assertFalse(HistoryConfig.isManual(_temperature));
		assertNull(_temperature.tValueByName("historyRetention"));
		assertEquals(List.of(), HistoryConfig.manualArchives(_temperature));
		assertEquals(Json.write(Json.parse(YEAR_CHART_HISTORY, 10)),
			Json.write(history(RuntimeImage.build(_project, 1, NOW), "1/3/1")));

		// An archive entered for a derived history (the form shows its table, TL #30134) is refused.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject stray = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.logical:HistoryArchive"));
			stray.tUpdateByName("resolution", Integer.valueOf(3600));
			stray.tUpdateByName("retention", Integer.valueOf(86400));
			_temperature.tUpdateByName("historyArchives", List.of(stray));
			assertEquals(I18NConstants.HISTORY_ARCHIVES_NOT_MANUAL, HistoryConfig.archivesProblem(_temperature));
			try {
				tx.commit();
				fail("An archive of a derived history must be refused.");
			} catch (TopLogicException ex) {
				// Expected: the constraint of the archive.
			}
		}
		assertEquals(List.of(), HistoryConfig.manualArchives(_temperature));
		assertEquals(Json.write(Json.parse(YEAR_CHART_HISTORY, 10)),
			Json.write(history(RuntimeImage.build(_project, 1, NOW), "1/3/1")));

		// Set by hand without a raw ring: no history, and a chart of it is refused.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_temperature.tUpdateByName("historyManual", Boolean.TRUE);
			tx.commit();
		}
		assertNotNull(Pages.seriesProblem(_temperature));
		assertFalse(RuntimeImage.build(_project, 1, NOW).validate().isValid());
	}

	/**
	 * Acceptance 4, the migration's effect on a datapoint: a group address and an application datapoint
	 * with a retention and a spacing from before #73 are set by hand without archives (the flag the
	 * migration writes), so their image does not change although a chart draws them; and the
	 * migration's SQL gives exactly the datapoints with a history the flag, for every revision of it.
	 */
	public void testMigration() throws Exception {
		chart("day", List.of("day", "year"), null, _temperature, _counter);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject datapoint : List.of(_temperature, _counter)) {
				datapoint.tUpdateByName("historyRetention", Integer.valueOf(7 * 86400));
				datapoint.tUpdateByName("historySpacing", Integer.valueOf(600));
				// What Issue_73_History_set_by_hand writes for it.
				datapoint.tUpdateByName("historyManual", Boolean.TRUE);
			}
			tx.commit();
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		Map<?, ?> before = Map.of("retention", Long.valueOf(7 * 86400), "spacing", Long.valueOf(600));
		assertEquals(before, history(image, "1/3/1"));
		assertEquals(before, history(image, "counter"));
		assertTrue(image.validate().toString(), image.validate().isValid());

		// The SQL of the migration on a table of the layout of FLEX_DATA.
		try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:issue73", "sa", "");
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE FLEX_DATA (TYPE VARCHAR(70) NOT NULL, IDENTIFIER BIGINT NOT NULL,"
				+ " REV_MAX BIGINT NOT NULL, ATTR VARCHAR(254) NOT NULL, REV_MIN BIGINT NOT NULL, DATA_TYPE TINYINT,"
				+ " LONG_DATA BIGINT, DOUBLE_DATA DOUBLE PRECISION, VARCHAR_DATA VARCHAR(254), CLOB_DATA CLOB,"
				+ " BLOB_DATA BLOB, PRIMARY KEY (TYPE, IDENTIFIER, REV_MAX, ATTR))");
			// Address 1: a retention changed once (two revisions); address 2: a spacing alone; 3: none.
			statement.execute("INSERT INTO FLEX_DATA VALUES"
				+ " ('EHGroupAddress', 1, 20, 'historyRetention', 10, 11, 3600, NULL, NULL, NULL, NULL),"
				+ " ('EHGroupAddress', 1, 9223372036854775807, 'historyRetention', 21, 11, 86400, NULL, NULL, NULL, NULL),"
				+ " ('EHGroupAddress', 1, 9223372036854775807, 'historySpacing', 10, 11, 60, NULL, NULL, NULL, NULL),"
				+ " ('EHGroupAddress', 2, 9223372036854775807, 'historySpacing', 12, 11, 60, NULL, NULL, NULL, NULL),"
				+ " ('EHGroupAddress', 3, 9223372036854775807, 'name', 12, 30, NULL, NULL, 'x', NULL, NULL),"
				+ " ('GenericObject', 4, 9223372036854775807, 'historyRetention', 13, 11, 600, NULL, NULL, NULL, NULL)");
			String script = Files.readString(new File(
				"src/main/webapp/WEB-INF/kbase/migration/ehome/Issue_73_History_set_by_hand_scripts/markManual.h2.sql")
				.toPath(), StandardCharsets.UTF_8);
			for (String sql : script.replaceAll("(?m)^--.*$", "").split(";")) {
				if (!sql.isBlank()) {
					statement.execute(sql);
				}
			}
			List<String> rows = new ArrayList<>();
			try (ResultSet result = statement.executeQuery("SELECT TYPE, IDENTIFIER, REV_MIN, REV_MAX, DATA_TYPE"
				+ " FROM FLEX_DATA WHERE ATTR = 'historyManual' ORDER BY TYPE, IDENTIFIER, REV_MIN")) {
				while (result.next()) {
					rows.add(result.getString(1) + " " + result.getLong(2) + " " + result.getLong(3) + ".."
						+ result.getLong(4) + " " + result.getInt(5));
				}
			}
			assertEquals(List.of("EHGroupAddress 1 10..20 1", "EHGroupAddress 1 21..9223372036854775807 1",
				"EHGroupAddress 2 12..9223372036854775807 1", "GenericObject 4 13..9223372036854775807 1"), rows);
		}
	}

	/**
	 * Acceptance 5: the derived histories exceeding the budget are refused at <code>history</code>, naming
	 * the biggest; the bytes are those of the device (homecontrol #56): 2 days raw at 5 min (577
	 * samples), 31 days hourly (745 slots) and 2 years daily (731 slots) take 4616 + 23840 + 23392 =
	 * 51848 bytes.
	 */
	public void testBudget() throws Exception {
		History fixture = new History(172800, 300, List.of(new Archive(3600, 31 * 86400), new Archive(86400, 730 * 86400)));
		assertEquals(577, fixture.samples());
		assertEquals(745, fixture.archives().get(0).slots());
		assertEquals(731, fixture.archives().get(1).slots());
		assertEquals(51848, fixture.bytes());

		// The image's check counts the same.
		String datapoint = """
			{"id": "1/3/1", "type": "real", "history": %s, "knx": {"dpt": "9.001", "listen": ["1/3/1"]}}
			""".formatted(Json.write(fixture.json()));
		assertTrue(ImageValidator.validate(image(datapoint, 51848)).isValid());
		ImageReport refused = ImageValidator.validate(image(datapoint, 51847));
		assertRefused(refused, "history", "the histories need 51848 bytes, the image's budget is 51847");
		assertRefused(refused, "history", "'1/3/1' (51848 bytes)");

		// The derived histories of a project against its budget.
		chart("day", List.of("day", "week", "month", "year"), "mean", _temperature, _humidity);
		chart("hour", List.of(), null, _window);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_project.tUpdateByName("historyBudget", Integer.valueOf(40000));
			tx.commit();
		}
		assertEquals(List.of(_temperature, _humidity, _window), HistoryConfig.datapoints(_project));
		assertEquals(35560 + 35560 + 61 * 8, HistoryConfig.totalBytes(_project));
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertFalse(report.isValid());
		assertRefused(report, "history", "the histories need 71608 bytes, the image's budget is 40000; the biggest: "
			+ "'1/3/1' (35560 bytes), '1/3/2' (35560 bytes), '1/3/4' (488 bytes)");
		assertSame(_project, image.elementAt("history"));
		assertTrue(HistoryConfig.usage(_project), HistoryConfig.usage(_project).contains("1/3/1 Temperatur"));
		assertEquals(2 * 35560, HistoryConfig.chartBytes(HistoryConfig.charts(_project).get(0)));
	}

	/**
	 * The refusals of the archives by the image's check (homecontrol #56): at most four, a resolution
	 * of 60 s to 31 days coarser than the raw spacing, a retention of at least the resolution and at
	 * most 10 years, at most 2048 slots, both members present.
	 */
	public void testArchiveRefusals() {
		String history = """
			{"retention": 86400, "spacing": 300, "archives": [
			  {"resolution": 30, "retention": 86400},
			  {"resolution": 300, "retention": 86400},
			  {"resolution": 3600, "retention": 1800},
			  {"resolution": 86400, "retention": 400000000}]}
			""";
		String datapoints = """
			{"id": "1/3/1", "type": "real", "history": %s, "knx": {"dpt": "9.001", "listen": ["1/3/1"]}},
			{"id": "1/3/2", "type": "real", "history": {"retention": 86400, "spacing": 300, "archives": [
			  {"resolution": 900, "retention": 86400}, {"resolution": 3600, "retention": 86400},
			  {"resolution": 7200, "retention": 86400}, {"resolution": 86400, "retention": 86400},
			  {"resolution": 172800, "retention": 172800}]}, "knx": {"dpt": "9.001", "listen": ["1/3/2"]}},
			{"id": "1/3/3", "type": "real", "history": {"retention": 3600, "spacing": 60, "archives": [
			  {"resolution": 600, "retention": 2000000}]}, "knx": {"dpt": "9.001", "listen": ["1/3/3"]}},
			{"id": "1/3/4", "type": "real", "history": {"retention": 3600, "spacing": 60, "archives": {}},
			 "knx": {"dpt": "9.001", "listen": ["1/3/4"]}},
			{"id": "1/3/5", "type": "real", "history": {"retention": 3600, "spacing": 60, "archives": [{"resolution": 3600}]},
			 "knx": {"dpt": "9.001", "listen": ["1/3/5"]}}
			""".formatted(history);
		ImageReport report = ImageValidator.validate(image(datapoints, 65536));
		String archives = "datapoints[0].history.archives";
		assertRefused(report, archives + "[0].resolution", "resolution (seconds)");
		assertRefused(report, archives + "[1].resolution", "the resolution must be larger than the raw spacing (300 s)");
		assertRefused(report, archives + "[2].retention", "retention (seconds, at least the resolution, at most 10 years)");
		assertRefused(report, archives + "[3].retention", "at most 10 years");
		assertRefused(report, "datapoints[4].history.archives[0]", "an archive needs resolution and retention");
		assertRefused(report, "datapoints[1].history.archives", "more than 4 archives");
		assertRefused(report, "datapoints[2].history.archives[0]", "more than 2048 slots");
		assertRefused(report, "datapoints[3].history.archives", "archives");
		// A refused history is no history: the chart of it would be refused, too.
		assertTrue(ImageValidator.validate(image("""
			{"id": "1/3/1", "type": "real", "history": {"retention": 86400, "spacing": 300, "archives": [
			  {"resolution": 2678400, "retention": 315360000}]}, "knx": {"dpt": "9.001", "listen": ["1/3/1"]}}
			""", 65536)).isValid());
	}

	/**
	 * Probe of the review: a truth value a function shows and an hour chart draws keeps the function's
	 * 24 h at the chart's minute; set by hand without a chart it has no history (and no refusal); deleting
	 * a datapoint with archives set by hand deletes the archives and the chart's series of it, and the
	 * image stays valid.
	 */
	public void testProbeComposition() throws Exception {
		chart("hour", List.of(), null, _window);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject contact = create("ehome.runtime:Function");
			contact.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Fenster").build());
			contact.tUpdateByName("identifier", "window");
			contact.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
			contact.tUpdateByName("value", _window);
			_project.tUpdateByName("functions", List.of(contact));
			tx.commit();
		}
		assertEquals(new History(86400, 60, List.of()), HistoryConfig.derived(_window));
		assertEquals(1441 * 8, HistoryConfig.bytes(_window));

		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			HistoryConfig.setManual(_humidity);
			tx.commit();
		}
		assertTrue(HistoryConfig.isManual(_humidity));
		assertNull(HistoryConfig.effective(_humidity));
		assertNull(HistoryConfig.archivesProblem(_humidity));

		chart("day", List.of("day", "year"), null, _temperature, _humidity);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			HistoryConfig.setManual(_temperature);
			tx.commit();
		}
		List<TLObject> archives = HistoryConfig.manualArchives(_temperature);
		assertFalse(archives.isEmpty());
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_humidity.tUpdateByName("historyManual", null);
			_temperature.tDelete();
			tx.commit();
		}
		for (TLObject archive : archives) {
			assertFalse(archive.tValid());
		}
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		assertNotNull(history(image, "1/3/2"));
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static String image(String datapoints, int budget) {
		return """
			{"image": {"schemaVersion": "1.1", "projectId": "history", "revision": 1},
			 "datapoints": [%s],
			 "history": {"budget": %d}}
			""".formatted(datapoints, Integer.valueOf(budget));
	}

	private static Object history(RuntimeImage image, String id) {
		for (Object datapoint : (List<?>) image.getJson().get("datapoints")) {
			if (id.equals(((Map<?, ?>) datapoint).get("id"))) {
				return ((Map<?, ?>) datapoint).get("history");
			}
		}
		fail("No datapoint " + id);
		return null;
	}

	/** A chart of the given range, presets and consolidation with a series per datapoint. */
	private TLObject chart(String range, List<String> presets, String consolidation, TLObject... datapoints) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject chart = Pages.insert(_project, _section, Pages.CHART, null);
			chart.tUpdateByName("range", classifier("ChartRange", range));
			List<Object> list = new ArrayList<>();
			for (String preset : presets) {
				list.add(classifier("ChartRange", preset));
			}
			chart.tUpdateByName("presets", list);
			Pages.insert(_project, chart, Pages.SERIES, List.of(datapoints));
			if (consolidation != null) {
				for (TLObject series : RuleFunctions.objects(chart, "series")) {
					series.tUpdateByName("consolidation", classifier("ChartConsolidation", consolidation));
				}
			}
			tx.commit();
			return chart;
		}
	}

	private static void assertRefused(ImageReport report, String path, String reason) {
		for (ImageReport.Refusal refusal : report.getRefusals()) {
			if (refusal.path().equals(path) && refusal.reason().contains(reason)) {
				return;
			}
		}
		fail("No refusal at " + path + " with '" + reason + "': " + report);
	}

	private TLObject mainType(int number) {
		TLObject type = create("ehome.datapoint:DatapointMainType");
		type.tUpdateByName("number", Integer.valueOf(number));
		type.tUpdateByName("extId", "TEST-HISTORY-" + number);
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

	private static Object classifier(String enumeration, String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:" + enumeration)).getClassifier(name);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestHistoryConfig.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

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
import java.util.Set;

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
import com.top_logic.util.Resources;
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

	private TLObject _real;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject real = mainType(9);
			_real = real;
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
		// 97 samples in RAM and the open slots of two archives of an hour or more; 721 and 366 slots on
		// flash, 6 and 3 segments.
		assertEquals(97 * 8 + 96 + 96, HistoryConfig.ramBytes(_temperature));
		assertEquals(968, HistoryConfig.ramBytes(_temperature));
		assertEquals((6 + 3) * 4096, HistoryConfig.flashBytes(_temperature));
		assertEquals(36864, HistoryConfig.flashBytes(_temperature));
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
		assertEquals(1441 * 8 + 96 + 96, history.ramBytes());
		// 169 slots in 2 segments, 366 in 3.
		assertEquals((2 + 3) * 4096, history.flashBytes());
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
		// merges into the next coarser one. The four left need 5 + 4 + 6 + 3 segments, more than the 16 of
		// the device's flash, but a need whose bucket width is its resolution allows no coarser store: the
		// history stays as it is (the image's check refuses it).
		History five = HistoryConfig.derive(List.of(new HistoryConfig.Need(300, 3 * 86400),
			new HistoryConfig.Need(900, 6 * 86400), new HistoryConfig.Need(1800, 10 * 86400),
			new HistoryConfig.Need(3600, 30 * 86400), new HistoryConfig.Need(86400, 365 * 86400)), false);
		assertEquals(new History(900, 300, List.of(new Archive(900, 6 * 86400), new Archive(1800, 10 * 86400),
			new Archive(3600, 30 * 86400), new Archive(86400, 365 * 86400))), five);
		assertEquals(18 * 4096, five.flashBytes());
		// Within the flash, the five keep four.
		History fiveSmall = HistoryConfig.derive(List.of(new HistoryConfig.Need(300, 3 * 86400),
			new HistoryConfig.Need(900, 4 * 86400), new HistoryConfig.Need(1800, 5 * 86400),
			new HistoryConfig.Need(3600, 7 * 86400), new HistoryConfig.Need(86400, 365 * 86400)), false);
		assertEquals(new History(900, 300, List.of(new Archive(900, 4 * 86400), new Archive(1800, 5 * 86400),
			new Archive(3600, 7 * 86400), new Archive(86400, 365 * 86400))), fiveSmall);
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
	 * samples), 31 days hourly (745 slots) and 2 years daily (731 slots) take 4616 + 96 + 96 = 4808 bytes
	 * of RAM and 6 + 6 segments, 49152 bytes, of flash. Derived histories beyond the device's flash or the
	 * project's budget are made coarser until they fit, and the usage text names them.
	 */
	public void testBudget() throws Exception {
		History fixture = new History(172800, 300, List.of(new Archive(3600, 31 * 86400), new Archive(86400, 730 * 86400)));
		assertEquals(577, fixture.samples());
		assertEquals(745, fixture.archives().get(0).slots());
		assertEquals(731, fixture.archives().get(1).slots());
		assertEquals(4808, fixture.ramBytes());
		assertEquals(49152, fixture.flashBytes());

		// The image's check counts the same.
		String datapoint = """
			{"id": "1/3/1", "type": "real", "history": %s, "knx": {"dpt": "9.001", "listen": ["1/3/1"]}}
			""".formatted(Json.write(fixture.json()));
		assertTrue(ImageValidator.validate(image(datapoint, 4808)).isValid());
		ImageReport refused = ImageValidator.validate(image(datapoint, 4807));
		assertRefused(refused, "history", "the rings and archives need 4808 bytes of RAM, the image's budget is 4807");
		assertRefused(refused, "history", "'1/3/1' (4808 bytes)");
		assertEquals(1, refused.getRefusals().size());

		// The derived histories of a project against its budget and the device's flash: two year charts
		// need 18 segments of the 16, and 2424 bytes of RAM of the budget of 2000. The derivation makes
		// them fit: first the flash (the hourly archive of 1/3/1 at 2 h, 6 segments: 15), then the RAM, the
		// biggest first, on a tie the smaller id: the raw ring of 1/3/1, then of 1/3/2, every 30 min
		// instead of 15 (49 samples, 584 bytes; the day's bucket is 30 min): 1656 bytes.
		chart("day", List.of("day", "week", "month", "year"), "mean", _temperature, _humidity);
		chart("hour", List.of(), null, _window);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_project.tUpdateByName("historyBudget", Integer.valueOf(2000));
			tx.commit();
		}
		assertEquals(new History(86400, 1800, List.of(new Archive(7200, 30 * 86400), new Archive(86400, 365 * 86400))),
			HistoryConfig.derived(_temperature));
		assertEquals(new History(86400, 1800, List.of(new Archive(3600, 30 * 86400), new Archive(86400, 365 * 86400))),
			HistoryConfig.derived(_humidity));
		assertEquals(new History(3600, 60, List.of()), HistoryConfig.derived(_window));
		assertEquals(List.of(_temperature, _humidity), List.copyOf(HistoryConfig.projectHistories(_project).coarsened()));
		assertEquals(List.of(_humidity, _temperature, _window), HistoryConfig.datapoints(_project));
		assertEquals(584 + 584 + 488, HistoryConfig.totalRamBytes(_project));
		assertEquals((6 + 9) * 4096, HistoryConfig.totalFlashBytes(_project));
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		assertTrue(image.validate().toString(), image.validate().isValid());
		assertAnswered();
		Resources resources = Resources.getInstance();
		String temperature = Pages.targetLabel(_temperature);
		String humidity = Pages.targetLabel(_humidity);
		assertEquals(
			resources.getString(I18NConstants.HISTORY_USAGE_RAM__BYTES_BUDGET_CAP.fill(Long.valueOf(1656),
				Long.valueOf(2000), Integer.valueOf(65536)))
				+ " " + resources.getString(I18NConstants.HISTORY_USAGE_FLASH__BYTES_CAP.fill(Long.valueOf(61440),
					Integer.valueOf(65536)))
				+ " " + resources.getString(I18NConstants.HISTORY_USAGE_COARSENED__DATAPOINTS.fill(temperature + ", " + humidity)),
			HistoryConfig.usage(_project));
		assertTrue(HistoryConfig.usage(_project), HistoryConfig.usage(_project).contains("1/3/1 Temperatur"));
		TLObject yearChart = HistoryConfig.charts(_project).get(0);
		assertEquals(584 + 584, HistoryConfig.chartRamBytes(yearChart));
		assertEquals(61440, HistoryConfig.chartFlashBytes(yearChart));

		// Within the budget and the caps: one datapoint with a year chart.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_project.tUpdateByName("historyBudget", null);
			tx.commit();
		}
		chartless(_humidity);
		assertTrue(RuntimeImage.build(_project, 1, NOW).validate().toString(),
			RuntimeImage.build(_project, 1, NOW).validate().isValid());
		assertEquals(Json.write(Json.parse(YEAR_CHART_HISTORY, 10)),
			Json.write(history(RuntimeImage.build(_project, 1, NOW), "1/3/1")));
		assertEquals(Set.of(), HistoryConfig.projectHistories(_project).coarsened());
		assertEquals(
			resources.getString(I18NConstants.HISTORY_USAGE_RAM__BYTES_CAP.fill(Long.valueOf(968 + 488), Integer.valueOf(65536)))
				+ " " + resources.getString(I18NConstants.HISTORY_USAGE_FLASH__BYTES_CAP.fill(Long.valueOf(36864),
					Integer.valueOf(65536))),
			HistoryConfig.usage(_project));
	}

	/**
	 * The derived histories of the image are made to fit the device's flash (16 segments of 4 KB) by
	 * giving up resolution within the bucket widths of the ranges, never coverage. A year chart of a
	 * temperature takes 9 segments: an hourly archive of 30 days (6) answering the week (bucket 3.5 h)
	 * and the month (15 h), and a daily one of a year (3) answering the year (7.6 d). Each step takes the
	 * finest coarser step that needs less flash, the datapoint with the most flash first (on a tie the
	 * smallest id), the finest archive first: the hourly archive at 2 h (361 slots, 3 segments), at 3 h
	 * (241, 2), no further (the week's bucket is 3.5 h); then the daily archive at 2 d (184 slots, 2) and
	 * at 7 d (54, 1). A year chart needs at least 3 segments: five fit, six are refused.
	 */
	public void testImageWide() throws Exception {
		History s0 = yearHistory(3600, 86400);
		History s1 = yearHistory(7200, 86400);
		History s2 = yearHistory(10800, 86400);
		History s3 = yearHistory(10800, 2 * 86400);
		History s4 = yearHistory(10800, 7 * 86400);
		assertEquals(List.of(Long.valueOf(9), Long.valueOf(6), Long.valueOf(5), Long.valueOf(4), Long.valueOf(3)),
			List.of(s0, s1, s2, s3, s4).stream().map(h -> Long.valueOf(h.flashBytes() / 4096)).toList());
		for (History history : List.of(s0, s1, s2, s3, s4)) {
			assertEquals(968, history.ramBytes());
		}

		// Two: 18 segments; 1/3/1 at s1: 6 + 9 = 15.
		TLObject chart = chart("day", List.of("day", "week", "month", "year"), "mean", _temperature, _humidity);
		assertEquals(s1, HistoryConfig.derived(_temperature));
		assertEquals(s0, HistoryConfig.derived(_humidity));
		assertEquals(15 * 4096, HistoryConfig.totalFlashBytes(_project));
		assertValid();
		assertAnswered();

		// Three: 27 segments; 1/3/1 and 1/3/2 at s2, 1/3/3 at s1: 5 + 5 + 6 = 16.
		addSeries(chart, _meter);
		assertEquals(s2, HistoryConfig.derived(_temperature));
		assertEquals(s2, HistoryConfig.derived(_humidity));
		assertEquals(s1, HistoryConfig.derived(_meter));
		assertEquals(16 * 4096, HistoryConfig.totalFlashBytes(_project));
		assertValid();
		assertAnswered();

		// Five: 45 segments; four at s4, 1/3/6 at s3: 4 * 3 + 4 = 16.
		List<TLObject> more = new ArrayList<>();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			List<Object> addresses = new ArrayList<>(RuleFunctions.objects(_project, "groupAddresses"));
			for (int sub = 5; sub <= 7; sub++) {
				TLObject address = address("Temperatur " + sub, 1, 3, sub, _real);
				more.add(address);
				addresses.add(address);
			}
			_project.tUpdateByName("groupAddresses", addresses);
			tx.commit();
		}
		addSeries(chart, more.get(0), more.get(1));
		for (TLObject datapoint : List.of(_temperature, _humidity, _meter, more.get(0))) {
			assertEquals(s4, HistoryConfig.derived(datapoint));
		}
		assertEquals(s3, HistoryConfig.derived(more.get(1)));
		assertEquals(65536, HistoryConfig.totalFlashBytes(_project));
		assertEquals(5 * 968, HistoryConfig.totalRamBytes(_project));
		RuntimeImage image = assertValid();
		assertAnswered();
		assertEquals(Json.write(Json.parse("""
			{"retention": 86400, "spacing": 900, "archives": [
			  {"resolution": 10800, "retention": 2592000}, {"resolution": 604800, "retention": 31536000}]}
			""", 10)), Json.write(history(image, "1/3/1")));
		assertEquals(Json.write(Json.parse("""
			{"retention": 86400, "spacing": 900, "archives": [
			  {"resolution": 10800, "retention": 2592000}, {"resolution": 172800, "retention": 31536000}]}
			""", 10)), Json.write(history(image, "1/3/6")));
		assertEquals(List.of(_temperature, _humidity, _meter, more.get(0), more.get(1)),
			List.copyOf(HistoryConfig.projectHistories(_project).coarsened()));
		// Deterministic: the same project gives the same image.
		assertEquals(Json.write(image.getJson().get("datapoints")),
			Json.write(RuntimeImage.build(_project, 1, NOW).getJson().get("datapoints")));

		// Six: all at s4 are 18 segments, and nothing can be coarser without a range losing its store:
		// refused, and every range is still answered.
		addSeries(chart, more.get(2));
		for (TLObject datapoint : List.of(_temperature, _humidity, _meter, more.get(0), more.get(1), more.get(2))) {
			assertEquals(s4, HistoryConfig.derived(datapoint));
		}
		ImageReport report = RuntimeImage.build(_project, 1, NOW).validate();
		assertEquals(report.toString(), 1, report.getRefusals().size());
		assertRefused(report, "history", "the archives need 73728 bytes of flash, the device allows at most 65536; "
			+ "the biggest: '1/3/1' (12288 bytes), '1/3/2' (12288 bytes), '1/3/3' (12288 bytes)");
		assertAnswered();
		assertTrue(HistoryConfig.usage(_project), HistoryConfig.usage(_project).contains(
			Resources.getInstance().getString(I18NConstants.HISTORY_USAGE_FLASH_EXCEEDED__BYTES_CAP_BIGGEST.fill(
				Long.valueOf(73728), Integer.valueOf(65536), Pages.targetLabel(_temperature) + " (12288 B), "
					+ Pages.targetLabel(_humidity) + " (12288 B), " + Pages.targetLabel(_meter) + " (12288 B)"))));
	}

	/** The history of a year chart (the day in the raw ring) with the given resolutions of its archives. */
	private static History yearHistory(long month, long year) {
		return new History(86400, 900, List.of(new Archive(month, 30 * 86400), new Archive(year, 365 * 86400)));
	}

	/**
	 * Every range of every chart of the project is answered as the device chooses the store (runtime
	 * image 3.9, "Which history answers"): of the stores whose resolution is at most the range's bucket
	 * width, the finest that reaches back over the range, else the one that reaches furthest; the raw
	 * ring where no archive is fine enough. The store chosen must be fine enough and reach back.
	 */
	private void assertAnswered() {
		for (Map.Entry<TLObject, List<Pages.ChartUse>> entry : Pages.chartUses(_project).entrySet()) {
			History history = HistoryConfig.effective(entry.getKey());
			assertNotNull(history);
			for (Pages.ChartUse use : entry.getValue()) {
				assertNotSame("Lines only.", classifier("ChartConsolidation", "consumption"),
					use.series().tValueByName("consolidation"));
				for (Long range : use.ranges()) {
					long bucket = range.longValue() / HistoryConfig.LINE_POINTS;
					long[] ring = { history.spacing(), history.retention() };
					List<long[]> fine = new ArrayList<>();
					boolean fineArchive = false;
					if (ring[0] <= bucket) {
						fine.add(ring);
					}
					for (Archive archive : history.archives()) {
						if (archive.resolution() <= bucket) {
							fine.add(new long[] { archive.resolution(), archive.retention() });
							fineArchive = true;
						}
					}
					long[] chosen;
					if (!fineArchive) {
						chosen = ring;
					} else {
						fine.sort(java.util.Comparator.comparingLong(store -> store[0]));
						chosen = null;
						for (long[] store : fine) {
							if (store[1] >= range.longValue()) {
								chosen = store;
								break;
							}
						}
						if (chosen == null) {
							chosen = fine.stream().max(java.util.Comparator.comparingLong(store -> store[1])).get();
						}
					}
					String what = Pages.targetLabel(entry.getKey()) + " over " + range + " s: " + history;
					assertTrue("Fine enough: " + what, chosen[0] <= bucket);
					assertTrue("Reaches back: " + what, chosen[1] >= range.longValue());
				}
			}
		}
	}

	/**
	 * A history set by hand is never made coarser: when it alone needs more flash than the device allows
	 * (an archive of 30 min for 40 days, 1921 slots in 16 segments, and an hourly one of 60 days, 1441
	 * slots in 12), the derived histories stay as their charts ask, and the image's check refuses the
	 * image at <code>history</code>.
	 */
	public void testManualExceeds() throws Exception {
		chart("day", List.of("day", "week", "month", "year"), "mean", _temperature, _humidity);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_humidity.tUpdateByName("historyManual", Boolean.TRUE);
			_humidity.tUpdateByName("historyRetention", Integer.valueOf(86400));
			_humidity.tUpdateByName("historySpacing", Integer.valueOf(900));
			TLClass type = (TLClass) TLModelUtil.findType("ehome.logical:HistoryArchive");
			List<TLObject> archives = new ArrayList<>();
			for (int[] archive : new int[][] { { 1800, 40 * 86400 }, { 3600, 60 * 86400 } }) {
				TLObject part = ModelService.getInstance().getFactory().createObject(type);
				part.tUpdateByName("resolution", Integer.valueOf(archive[0]));
				part.tUpdateByName("retention", Integer.valueOf(archive[1]));
				archives.add(part);
			}
			_humidity.tUpdateByName("historyArchives", archives);
			tx.commit();
		}
		History manual = HistoryConfig.effective(_humidity);
		assertEquals(28 * 4096, manual.flashBytes());
		History year = new History(86400, 900, List.of(new Archive(3600, 30 * 86400), new Archive(86400, 365 * 86400)));
		assertEquals(year, HistoryConfig.derived(_temperature));
		assertEquals(Set.of(), HistoryConfig.projectHistories(_project).coarsened());
		ImageReport report = RuntimeImage.build(_project, 1, NOW).validate();
		assertEquals(report.toString(), 1, report.getRefusals().size());
		assertRefused(report, "history", "the archives need 151552 bytes of flash, the device allows at most 65536; "
			+ "the biggest: '1/3/2' (114688 bytes), '1/3/1' (36864 bytes)");
		Resources resources = Resources.getInstance();
		assertTrue(HistoryConfig.usage(_project), HistoryConfig.usage(_project).endsWith(
			resources.getString(I18NConstants.HISTORY_USAGE_FLASH_EXCEEDED__BYTES_CAP_BIGGEST.fill(Long.valueOf(151552),
				Integer.valueOf(65536), Pages.targetLabel(_humidity) + " (114688 B), " + Pages.targetLabel(_temperature)
					+ " (36864 B)"))));
	}

	/**
	 * Probe (review of #56): consumption bars (a meter, an application counter) and year lines (two
	 * temperatures) beside a history set by hand (a window, an hourly archive of 31 days: 6 segments).
	 * The hand-set history stays as it is; the derived ones are made coarser until the image fits; and
	 * every range of every derived history is answered as the device chooses its store, with the bucket
	 * widths of the device written out here (a line: range / 48; bars: 1 h of 5 min, 24 h of 1 h, 7 d and
	 * 30 d of 1 d, 1 y of a twelfth).
	 */
	public void testProbeBarsBesideManual() throws Exception {
		chart("day", List.of("day", "week", "month", "year"), "consumption", _meter, _counter);
		chart("day", List.of("day", "week", "month", "year"), "mean", _temperature, _humidity);
		chart("week", List.of(), "mean", _window);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_window.tUpdateByName("historyManual", Boolean.TRUE);
			_window.tUpdateByName("historyRetention", Integer.valueOf(86400));
			_window.tUpdateByName("historySpacing", Integer.valueOf(900));
			TLClass type = (TLClass) TLModelUtil.findType("ehome.logical:HistoryArchive");
			TLObject part = ModelService.getInstance().getFactory().createObject(type);
			part.tUpdateByName("resolution", Integer.valueOf(3600));
			part.tUpdateByName("retention", Integer.valueOf(31 * 86400));
			_window.tUpdateByName("historyArchives", List.of(part));
			tx.commit();
		}
		History manual = new History(86400, 900, List.of(new Archive(3600, 31 * 86400)));
		assertEquals(manual, HistoryConfig.effective(_window));
		assertEquals(6 * 4096, manual.flashBytes());

		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		assertTrue(HistoryConfig.totalFlashBytes(_project) <= ImageValidator.ARCHIVE_CAP);
		assertEquals(manual, HistoryConfig.effective(_window));
		assertFalse(HistoryConfig.projectHistories(_project).coarsened().contains(_window));

		java.util.Map<Long, Long> barWidth = java.util.Map.of(
			Long.valueOf(3600), Long.valueOf(300), Long.valueOf(86400), Long.valueOf(3600),
			Long.valueOf(7 * 86400), Long.valueOf(86400), Long.valueOf(30 * 86400), Long.valueOf(86400),
			Long.valueOf(365 * 86400), Long.valueOf(365 * 86400 / 12));
		Object consumption = classifier("ChartConsolidation", "consumption");
		for (Map.Entry<TLObject, List<Pages.ChartUse>> entry : Pages.chartUses(_project).entrySet()) {
			if (entry.getKey() == _window) {
				continue;
			}
			History history = HistoryConfig.effective(entry.getKey());
			for (Pages.ChartUse use : entry.getValue()) {
				boolean bars = use.series().tValueByName("consolidation") == consumption;
				for (Long range : use.ranges()) {
					long bucket = bars ? barWidth.get(range).longValue() : range.longValue() / 48;
					// The device: of the stores fine enough, the finest reaching back, else the furthest; the raw
					// ring where no archive is fine enough.
					long[] ring = { history.spacing(), history.retention() };
					List<long[]> fine = new ArrayList<>();
					for (Archive archive : history.archives()) {
						if (archive.resolution() <= bucket) {
							fine.add(new long[] { archive.resolution(), archive.retention() });
						}
					}
					long[] answer = ring;
					if (!fine.isEmpty()) {
						if (ring[0] <= bucket) {
							fine.add(ring);
						}
						fine.sort(java.util.Comparator.comparingLong(store -> store[0]));
						answer = fine.stream().filter(store -> store[1] >= range.longValue()).findFirst()
							.orElseGet(() -> fine.stream().max(java.util.Comparator.comparingLong(store -> store[1])).get());
					}
					String what = Pages.targetLabel(entry.getKey()) + (bars ? " bars" : " line") + " over " + range
						+ " s (bucket " + bucket + "): " + history;
					assertTrue("Fine enough: " + what, answer[0] <= bucket);
					assertTrue("Reaches back: " + what, answer[1] >= range.longValue());
				}
			}
		}
	}

	/**
	 * The exact histories of the probe's project (testProbeBarsBesideManual): the hand-set window takes 6
	 * segments, the year lines of 1/3/1 and 1/3/2 go to s4 (3 segments each, as in testImageWide), and the
	 * daily archive of the consumption bars (395 d, 397 slots, 4 segments), which the month bars (bucket
	 * 1 d, 31 d) and the year bars (bucket 30.4 d, 395 d) share, is split: daily for 31 d (32 slots, 1
	 * segment) and weekly for the 395 d (58 slots, 1 segment). 6 + 3 + 3 + 2 + 2 = 16 segments.
	 */
	public void testSplit() throws Exception {
		chart("day", List.of("day", "week", "month", "year"), "consumption", _meter, _counter);
		chart("day", List.of("day", "week", "month", "year"), "mean", _temperature, _humidity);
		History bars = new History(25 * 3600, 3600, List.of(new Archive(86400, 13 * 2628000L)));
		assertEquals(bars, HistoryConfig.derived(_meter));
		assertEquals(397, bars.archives().get(0).slots());
		assertEquals(4 * 4096, bars.flashBytes());
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_window.tUpdateByName("historyManual", Boolean.TRUE);
			_window.tUpdateByName("historyRetention", Integer.valueOf(86400));
			_window.tUpdateByName("historySpacing", Integer.valueOf(900));
			TLObject part = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.logical:HistoryArchive"));
			part.tUpdateByName("resolution", Integer.valueOf(3600));
			part.tUpdateByName("retention", Integer.valueOf(31 * 86400));
			_window.tUpdateByName("historyArchives", List.of(part));
			tx.commit();
		}
		History split =
			new History(25 * 3600, 3600, List.of(new Archive(86400, 31 * 86400), new Archive(7 * 86400, 13 * 2628000L)));
		assertEquals(List.of(Long.valueOf(32), Long.valueOf(58)),
			split.archives().stream().map(a -> Long.valueOf(a.slots())).toList());
		assertEquals(split, HistoryConfig.derived(_meter));
		assertEquals(split, HistoryConfig.derived(_counter));
		History s4 = new History(86400, 900, List.of(new Archive(10800, 30 * 86400), new Archive(7 * 86400, 365 * 86400)));
		assertEquals(s4, HistoryConfig.derived(_temperature));
		assertEquals(s4, HistoryConfig.derived(_humidity));
		assertEquals(65536, HistoryConfig.totalFlashBytes(_project));
		assertValid();
	}

	/**
	 * The example of the runtime image's specification (3.7, homecontrol
	 * <code>accept-history-archives.json</code>): a temperature with a ring of 2 days at 300 s, an hourly
	 * archive of 31 days and a daily one of 2 years, and a boolean with a ring of a day at 900 s and a
	 * daily archive of a year need a budget of 5680 bytes of RAM and 61 440 bytes of flash; the image's
	 * check accepts exactly these budgets and refuses one byte less, a budget above the caps, and two
	 * archives of the same resolution.
	 */
	public void testSpecExample() {
		History temperature =
			new History(172800, 300, List.of(new Archive(3600, 31 * 86400), new Archive(86400, 730 * 86400)));
		History heating = new History(86400, 900, List.of(new Archive(86400, 365 * 86400)));
		assertEquals(577, temperature.samples());
		assertEquals(97, heating.samples());
		assertEquals(List.of(Long.valueOf(745), Long.valueOf(731)),
			temperature.archives().stream().map(a -> Long.valueOf(a.slots())).toList());
		assertEquals(List.of(Long.valueOf(6), Long.valueOf(6)),
			temperature.archives().stream().map(a -> Long.valueOf(a.segments())).toList());
		assertEquals(366, heating.archives().get(0).slots());
		assertEquals(3, heating.archives().get(0).segments());
		assertEquals(577 * 8 + 96 + 96, temperature.ramBytes());
		assertEquals(97 * 8 + 96, heating.ramBytes());
		assertEquals(5680, temperature.ramBytes() + heating.ramBytes());
		assertEquals(61440, temperature.flashBytes() + heating.flashBytes());

		// The RAM of an archive: the open slot and an hour of closed slots.
		assertEquals((60 + 2) * 32, ImageValidator.archiveRamBytes(60));
		assertEquals((12 + 2) * 32, ImageValidator.archiveRamBytes(300));
		assertEquals((4 + 2) * 32, ImageValidator.archiveRamBytes(900));
		assertEquals((2 + 2) * 32, ImageValidator.archiveRamBytes(1800));
		assertEquals(96, ImageValidator.archiveRamBytes(3600));
		assertEquals(96, ImageValidator.archiveRamBytes(7200));
		// The flash: a segment per 127 slots.
		assertEquals(4096, ImageValidator.archiveFlashBytes(126 * 3600, 3600));
		assertEquals(8192, ImageValidator.archiveFlashBytes(127 * 3600, 3600));
		assertEquals(17 * 4096, ImageValidator.archiveFlashBytes(2047 * 60, 60));

		String datapoints = """
			{"id": "t", "type": "real", "history": %s}, {"id": "heating", "type": "boolean", "history": %s}
			""".formatted(Json.write(temperature.json()), Json.write(heating.json()));
		assertTrue(ImageValidator.validate(image(datapoints, "\"budget\": 5680, \"archiveBudget\": 61440")).isValid());
		// Without budgets: what they need.
		assertTrue(ImageValidator.validate(image(datapoints, null)).isValid());
		ImageReport ram = ImageValidator.validate(image(datapoints, "\"budget\": 5679, \"archiveBudget\": 61440"));
		assertEquals(ram.toString(), 1, ram.getRefusals().size());
		assertRefused(ram, "history", "the rings and archives need 5680 bytes of RAM, the image's budget is 5679");
		ImageReport flash = ImageValidator.validate(image(datapoints, "\"archiveBudget\": 61439"));
		assertEquals(flash.toString(), 1, flash.getRefusals().size());
		assertRefused(flash, "history", "the archives need 61440 bytes of flash, the image's archive budget is 61439; "
			+ "the biggest: 't' (49152 bytes), 'heating' (12288 bytes)");
		ImageReport cap = ImageValidator.validate(image(datapoints, "\"archiveBudget\": 65537"));
		assertEquals(cap.toString(), 1, cap.getRefusals().size());
		assertRefused(cap, "history.archiveBudget", "the image asks for 65537 bytes of archive flash, the device allows at most 65536");
		assertTrue(ImageValidator.validate(image(datapoints, "\"archiveBudget\": 65536")).isValid());
		ImageReport ramCap = ImageValidator.validate(image(datapoints, "\"budget\": 65537"));
		assertRefused(ramCap, "history.budget", "the image asks for 65537 bytes of history RAM, the device allows at most 65536");

		// Two archives of one resolution.
		ImageReport twice = ImageValidator.validate(image("""
			{"id": "t", "type": "real", "history": {"retention": 86400, "spacing": 300, "archives": [
			  {"resolution": 3600, "retention": 86400}, {"resolution": 3600, "retention": 172800}]}}
			""", null));
		assertEquals(twice.toString(), 1, twice.getRefusals().size());
		assertRefused(twice, "datapoints[0].history.archives[1].resolution", "two archives of the resolution 3600 s");
	}

	/**
	 * A history whose naive archives need more flash than the device allows (an hourly archive of 45 days,
	 * 1081 slots in 9 segments, and a daily one of 5 years, 1826 slots in 15: 98 304 bytes) is made coarser
	 * within the bucket widths of its ranges (45 d: 22.5 h, 5 years: 38 d), each step giving up the least
	 * resolution, on a tie saving the most: the daily archive at 2 d (914 slots, 8 segments; a factor of
	 * 2 like the hourly one at 2 h, but saving 7 segments instead of 4), then the hourly one at 2 h (541
	 * slots, 5 segments; a factor of 2 against 3.5 for the 2 d one at 7 d): 13 segments, and both ranges
	 * are still answered.
	 */
	public void testFlashCap() {
		long years = 1825L * 86400;
		HistoryConfig.Need month = new HistoryConfig.Need(3600, 45 * 86400, 45 * 86400 / 48);
		HistoryConfig.Need decade = new HistoryConfig.Need(86400, years, years / 48);
		History naive = new History(86400, 3600, List.of(new Archive(3600, 45 * 86400), new Archive(86400, years)));
		assertEquals(24 * 4096, naive.flashBytes());
		assertFalse(naive.fits());
		History derived = HistoryConfig.derive(List.of(month, decade), false);
		assertEquals(new History(7200, 3600, List.of(new Archive(7200, 45 * 86400), new Archive(2 * 86400, years))), derived);
		assertEquals(13 * 4096, derived.flashBytes());
		assertEquals(3 * 8 + 96 + 96, derived.ramBytes());
		assertTrue(derived.fits());
		assertTrue(month.answeredBy(derived));
		assertTrue(decade.answeredBy(derived));

		// With the raw ring of a chart's day: the ring stays, the archives give up resolution only.
		History withRaw = HistoryConfig.derive(List.of(new HistoryConfig.Need(900, 86400, 1800), month, decade), false);
		assertEquals(new History(86400, 900, List.of(new Archive(7200, 45 * 86400), new Archive(2 * 86400, years))), withRaw);
		// Needs whose bucket width is their resolution allow nothing coarser: the history stays beyond the
		// flash (the image's check refuses it).
		History bound = HistoryConfig.derive(
			List.of(new HistoryConfig.Need(3600, 45 * 86400), new HistoryConfig.Need(86400, years)), false);
		assertEquals(new History(3600, 900, List.of(new Archive(3600, 45 * 86400), new Archive(86400, years))), bound);
		assertFalse(bound.fits());
		// Within the cap, nothing is coarsened: 9 + 5 segments.
		assertEquals(List.of(new Archive(3600, 45 * 86400), new Archive(86400, 600L * 86400)),
			HistoryConfig.derive(List.of(month, new HistoryConfig.Need(86400, 600L * 86400, 600L * 86400 / 48)), false)
				.archives());
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
		assertEquals(1441 * 8, HistoryConfig.ramBytes(_window));
		assertEquals(0, HistoryConfig.flashBytes(_window));

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
		return image(datapoints, "\"budget\": " + budget);
	}

	/** An image of the datapoints and the members of <code>history</code>; without it for <code>null</code>. */
	private static String image(String datapoints, String history) {
		return """
			{"image": {"schemaVersion": "1.2", "projectId": "history", "revision": 1},
			 "datapoints": [%s]%s}
			""".formatted(datapoints, history == null ? "" : ",\n \"history\": {" + history + "}");
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

	private RuntimeImage assertValid() {
		RuntimeImage image = RuntimeImage.build(_project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		return image;
	}

	/** Adds a series per datapoint to the chart. */
	private void addSeries(TLObject chart, TLObject... datapoints) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Pages.insert(_project, chart, Pages.SERIES, List.of(datapoints));
			tx.commit();
		}
	}

	/** Deletes the series of the datapoint from every chart. */
	private void chartless(TLObject datapoint) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (TLObject chart : HistoryConfig.charts(_project)) {
				for (TLObject series : RuleFunctions.objects(chart, "series")) {
					if (series.tValueByName("datapoint") == datapoint) {
						series.tDelete();
					}
				}
			}
			tx.commit();
		}
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

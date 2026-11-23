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
import java.util.LinkedHashSet;
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
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.ChartElement;
import de.haumacher.ehome.model.runtime.ChartSeries;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Page;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.HistoryConfig;
import de.haumacher.ehome.runtime.HistoryConfig.Archive;
import de.haumacher.ehome.runtime.HistoryConfig.History;
import de.haumacher.ehome.runtime.HistoryConfig.Level;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RecordingMigration;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.runtime.binary.ImageCompiler;

/**
 * The history of a datapoint from its recording (haumacher/ehome #221, homecontrol #115): the stores
 * of each level, line and meter, the image's members <code>history</code> and <code>meter</code>, the
 * charts that only choose what is recorded, the budget, the format gate for a controller of format 1.2
 * and the migration of the histories set by hand or derived from the charts before.
 *
 * <p>
 * The project is built without an ETS import: group addresses of datapoint main types created for the
 * test (9, a real value; 1, a truth value; 12, a counter) and an application datapoint.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestHistoryConfig extends BasicTestCase {

	private static final java.time.Instant NOW = java.time.Instant.parse("2026-11-23T12:00:00Z");

	private static final long H = 3600;

	private static final long D = 86400;

	private Project _project;

	private final List<TLObject> _types = new ArrayList<>();

	private GroupAddress _temperature;

	private GroupAddress _humidity;

	private GroupAddress _pulses;

	private GroupAddress _window;

	private ApplicationDatapoint _counter;

	private TLObject _section;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject real = mainType(9);
			TLObject bool = mainType(1);
			TLObject counter = mainType(12);
			_project = (Project) create("ehome.project:Project");
			_project.setName("History");
			_project.setExtId("P-TEST-221");
			_temperature = address("Temperatur", 1, 3, 1, real);
			_humidity = address("Feuchte", 1, 3, 2, real);
			_pulses = address("Impulse", 1, 3, 3, counter);
			_window = address("Fenster", 1, 3, 4, bool);
			_project.setGroupAddresses(Set.of(_temperature, _humidity, _pulses, _window));
			_counter = (ApplicationDatapoint) RuleFunctions.create(_project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			_counter.setIdentifier("counter");
			_counter.setType(classifier("DatapointValueType", "unsigned"));
			Page page = (Page) Pages.insert(_project, null, Pages.PAGE, null);
			page.setNameKey(ResKey.text("climate"));
			page.setIdentifier("climate");
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
	 * The stores of each level for a line, band or strip, exactly the table of haumacher/ehome #221: a
	 * ring of 5 min over 6 h (73 values, 584 bytes) or 24 h (289, 2312 bytes), then archives of 3 h over
	 * 7 days (57 slots), 12 h over 30 days (61), 7 days over a year (54) and 31 days over 10 years (119),
	 * each one segment of 4 KB on flash and 96 bytes of RAM for its open slots.
	 */
	public void testLineStores() {
		assertNull(HistoryConfig.stores(Level.NONE, false));
		assertStores(Level.SIX_HOURS, false, new History(6 * H, 300, List.of(), false), 73, 584, 0);
		assertStores(Level.DAY, false, new History(D, 300, List.of(), false), 289, 2312, 0);
		Archive week = new Archive(3 * H, 7 * D);
		Archive month = new Archive(12 * H, 30 * D);
		Archive year = new Archive(7 * D, 365 * D);
		Archive tenYears = new Archive(31 * D, 3650 * D);
		assertEquals(57, week.slots());
		assertEquals(61, month.slots());
		assertEquals(54, year.slots());
		assertEquals(119, tenYears.slots());
		assertStores(Level.WEEK, false, new History(D, 300, List.of(week), false), 346, 2312 + 96, 4096);
		assertStores(Level.MONTH, false, new History(D, 300, List.of(week, month), false), 407, 2312 + 2 * 96, 8192);
		assertStores(Level.YEAR, false, new History(D, 300, List.of(week, month, year), false), 461, 2312 + 3 * 96,
			12288);
		assertStores(Level.TEN_YEARS, false, new History(D, 300, List.of(week, month, year, tenYears), false), 580,
			2312 + 4 * 96, 16384);
		// Each level reaches its range, and a reach rounds up to the next level.
		for (Level level : Level.values()) {
			if (level != Level.NONE) {
				assertEquals(level.name(), level.reach(), HistoryConfig.stores(level, false).reach());
				assertEquals(level, Level.covering(level.reach(), false));
			}
		}
		assertEquals(Level.NONE, Level.covering(0, false));
		assertEquals(Level.SIX_HOURS, Level.covering(H, false));
		assertEquals(Level.WEEK, Level.covering(2 * D, false));
		assertEquals(Level.YEAR, Level.covering(31 * D, false));
		assertEquals(Level.TEN_YEARS, Level.covering(20 * 365 * D, false));
	}

	/**
	 * The stores of a meter: the bars' widths instead of the 48 points (15 min for 6 h, 1 h for 24 h,
	 * 1 d for 7 d and 30 d, a month for 1 y, a year for 10 y), each reaching one bar further for the
	 * first difference, up to the 10 years an archive keeps: the 15-minute ring over 6 h 15 min or 25 h,
	 * day slots over 8 days, 31 days or 13 months, 31-day slots over 10 years.
	 */
	public void testMeterStores() {
		assertStores(Level.SIX_HOURS, true, new History(6 * H + 900, 900, List.of(), true), 26, 208, 0);
		assertStores(Level.DAY, true, new History(25 * H, 900, List.of(), true), 101, 808, 0);
		assertStores(Level.WEEK, true, new History(25 * H, 900, List.of(new Archive(D, 8 * D)), true), 110, 808 + 96,
			4096);
		assertStores(Level.MONTH, true, new History(25 * H, 900, List.of(new Archive(D, 31 * D)), true), 133,
			808 + 96, 4096);
		Archive year = new Archive(D, 13 * (365 * D / 12));
		assertEquals(397, year.slots());
		assertStores(Level.YEAR, true, new History(25 * H, 900, List.of(year), true), 498, 808 + 96, 16384);
		assertStores(Level.TEN_YEARS, true,
			new History(25 * H, 900, List.of(year, new Archive(31 * D, 3650 * D)), true), 617, 808 + 2 * 96, 20480);
		// A meter recorded for 30 days covers its 31 bars: rounded to the month, not the year.
		assertEquals(Level.MONTH, Level.covering(31 * D, true));
		assertEquals(Level.YEAR, Level.covering(31 * D, false));
	}

	/**
	 * The image carries the stores of the recording and <code>meter</code> for a meter reading of a
	 * number: an application datapoint by its flag, a group address by its flag or its datapoint type
	 * (12.x); the image is then written in schema 1.10 and binary format 1.3. A truth value is never a
	 * meter reading: the flag is refused at saving and left out of the image.
	 */
	public void testImage() throws Exception {
		RuntimeImage before = RuntimeImage.build(_project, 1, NOW);
		assertNull(history(before, "1/3/1"));
		assertEquals(RuntimeImage.SCHEMA_VERSION_INITIAL_VALUES, schema(before));

		record(_temperature, Level.MONTH);
		record(_pulses, Level.YEAR);
		record(_window, Level.DAY);
		record(_counter, Level.SIX_HOURS);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_counter.setMeter(true);
			tx.commit();
		}
		assertTrue(HistoryConfig.meterByType(_pulses));
		assertTrue(HistoryConfig.meter(_pulses));
		assertFalse(HistoryConfig.meterByType(_temperature));
		RuntimeImage image = assertValid();
		assertEquals(HistoryConfig.stores(Level.MONTH, false).json(), history(image, "1/3/1"));
		assertEquals(HistoryConfig.stores(Level.YEAR, true).json(), history(image, "1/3/3"));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) history(image, "1/3/3")).get("meter"));
		assertEquals(HistoryConfig.stores(Level.SIX_HOURS, true).json(), history(image, "counter"));
		assertFalse(((Map<?, ?>) history(image, "1/3/4")).containsKey("meter"));
		assertNull(history(image, "1/3/2"));
		assertEquals(RuntimeImage.SCHEMA_VERSION_METER, schema(image));
		assertEquals(3, ImageCompiler.formatMinor(image.getJson()));
		byte[] binary = image.compile(null);
		assertEquals(3, binary[6] | binary[7] << 8);

		// A group address set to be no meter, though its type is one.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_pulses.setMeter(Boolean.FALSE);
			tx.commit();
		}
		assertFalse(((Map<?, ?>) history(RuntimeImage.build(_project, 1, NOW), "1/3/3")).containsKey("meter"));

		// The flag on a truth value: refused by the field's check.
		assertEquals(I18NConstants.METER_NOT_NUMBER__TYPE.fill("boolean"),
			HistoryConfig.meterProblem(Boolean.TRUE, _window));
		assertNull(HistoryConfig.meterProblem(Boolean.TRUE, _temperature));
		assertNull(HistoryConfig.meterProblem(Boolean.FALSE, _window));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_window.setMeter(Boolean.TRUE);
			tx.commit();
			fail("A truth value is no meter reading.");
		} catch (TopLogicException ex) {
			// Expected: the constraint of the field.
		}

		// The recording of a string is refused by the field's check.
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			_counter.setType(classifier("DatapointValueType", "string"));
			assertEquals(I18NConstants.HISTORY_WRONG_TYPE__TYPE.fill("string"),
				HistoryConfig.recordingProblem(HistoryConfig.classifier(Level.DAY), _counter));
			assertNull(HistoryConfig.recordingProblem(HistoryConfig.classifier(Level.NONE), _counter));
			assertNull(HistoryConfig.effective(_counter));
		}
	}

	/**
	 * A controller of format 1.2 gets no meter flag (minor 2 at most) and no page with the ranges 6 h or
	 * 10 y: the deployment is refused before sending, naming the ranges; a controller of 1.3 takes both.
	 */
	public void testFormatGate() throws Exception {
		record(_pulses, Level.DAY);
		RuntimeImage meterOnly = assertValid();
		assertEquals(3, ImageCompiler.formatMinor(meterOnly.getJson()));
		assertNull(Deployer.formatRefusal("1.2", "C", meterOnly.getJson()));
		assertNull(Deployer.formatRefusal("1.3", "C", meterOnly.getJson()));
		byte[] forOld = meterOnly.compile(null, 2);
		assertEquals(2, forOld[6] | forOld[7] << 8);
		assertEquals(meterOnly.compile(null).length, forOld.length);

		chart("sixHours", List.of("sixHours", "day"), null, _pulses);
		RuntimeImage ranges = assertValid();
		ResKey refusal = Deployer.formatRefusal("1.2", "C", ranges.getJson());
		assertEquals(I18NConstants.DEPLOY_REFUSED_CHART_RANGES__CONTROLLER_FORMAT.fill("C", "1.2"), refusal);
		assertNull(Deployer.formatRefusal("1.3", "C", ranges.getJson()));
		// The initial value of the application datapoint asks for 1.2 already.
		assertEquals(I18NConstants.DEPLOY_REFUSED_FORMAT__CONTROLLER_FORMAT_IMAGE.fill("C", "1.1", "1.2"),
			Deployer.formatRefusal("1.1", "C", ranges.getJson()));
		assertEquals(2, Deployer.reportedMinor("1.2"));
		assertEquals(-1, Deployer.reportedMinor("2.0"));
	}

	/**
	 * The charts only choose: a series offers the datapoints with a recording, a chart the ranges every
	 * datapoint of its series reaches (never 1 h); a chart asking for more is not cut but refused by the
	 * image's check, at its range or preset, and a series of a datapoint without recording as before.
	 */
	public void testCharts() throws Exception {
		record(_temperature, Level.DAY);
		record(_humidity, Level.MONTH);
		List<TLObject> series = Pages.options(_section, Pages.SERIES);
		assertTrue(series.contains(_temperature));
		assertTrue(series.contains(_humidity));
		assertFalse(series.contains(_window));
		assertFalse(series.contains(_counter));

		TLObject chart = chart("day", List.of("day", "week", "month"), null, _temperature, _humidity);
		assertEquals(D, HistoryConfig.chartReach(chart));
		assertEquals(List.of(range("sixHours"), range("day")), Pages.options(chart, Pages.RANGE));
		ImageReport report = RuntimeImage.build(_project, 1, NOW).validate();
		String path = "ui.pages[0].sections[0].elements[0].chart";
		assertRefused(report, path + ".presets[1]", "range 7d, but " + Pages.targetLabel(_temperature) + " is recorded only for");
		assertRefused(report, path + ".presets[2]", "range 30d");
		assertEquals(2, report.getRefusals().size());

		// Recorded long enough: valid; the range 1 h is refused.
		record(_temperature, Level.MONTH);
		assertValid();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			((ChartElement) chart).setRange(range("hour"));
			((ChartElement) chart).setPresets(Set.of());
			tx.commit();
		}
		report = RuntimeImage.build(_project, 1, NOW).validate();
		assertRefused(report, path + ".range", "range 1 h");
		assertEquals(1, report.getRefusals().size());

		// A chart without series offers every range but 1 h.
		TLObject empty = chart("day", List.of(), null);
		assertEquals(7 - 1, Pages.options(empty, Pages.RANGE).size());

		// A datapoint without recording: the series is refused.
		chart("day", List.of(), null, _window);
		report = RuntimeImage.build(_project, 1, NOW).validate();
		boolean found = false;
		for (ImageReport.Refusal refused : report.getRefusals()) {
			found |= refused.path().endsWith(".chart.series[0].datapoint") && refused.reason().contains("keeps no history");
		}
		assertTrue(report.toString(), found);
	}

	/**
	 * The budget and the caps stay: five datapoints recorded for 10 years need 80 KB of flash, more than
	 * the controller's 64 KB, and are refused at <code>history</code>, the usage naming the biggest.
	 */
	public void testBudget() throws Exception {
		for (TLObject datapoint : List.of(_temperature, _humidity, _pulses, _window, _counter)) {
			record(datapoint, Level.TEN_YEARS);
		}
		assertEquals(5, HistoryConfig.datapoints(_project).size());
		assertEquals(4 * 16384 + 20480, HistoryConfig.totalFlashBytes(_project));
		ImageReport report = RuntimeImage.build(_project, 1, NOW).validate();
		assertFalse(report.isValid());
		assertRefused(report, "history", "flash");
		assertTrue(HistoryConfig.usage(_project), HistoryConfig.usage(_project).contains(Pages.targetLabel(_pulses)));

		record(_counter, Level.NONE);
		record(_window, Level.NONE);
		assertValid();
	}

	/**
	 * The migration (haumacher/ehome #221): every datapoint gets once its history effective before as
	 * its recording, the reach rounded up to the next level: one set by hand with 2 days "7 days" (the
	 * SQL's reach), one with a chart over a year "1 year", a number drawn as consumption bars over 30 days
	 * a meter of "30 days", a truth value a function shows on hour charts "24 h" (the function asked for
	 * 24 h), a history set by hand without a ring and a datapoint no chart draws "none".
	 *
	 * <p>
	 * The charts follow: a range 1 h becomes 6 h, a preset 1 h becomes 6 h (once), and a datapoint set by
	 * hand to 24 h that a chart draws over 7 days is recorded for 7 days. The migrated project's image
	 * passes the check: no chart is refused.
	 * </p>
	 */
	public void testMigration() throws Exception {
		GroupAddress power;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			power = address("Leistung", 1, 3, 5, _temperature.getDatapointTypes().get(0));
			_project.getGroupAddressesModifiable().add(power);
			tx.commit();
		}
		chart("day", List.of(), null, _temperature);
		chart("week", List.of(), null, _temperature);
		chart("day", List.of(), null, _humidity);
		chart("year", List.of("day", "year"), null, power);
		chart("month", List.of(), "consumption", _counter);
		TLObject hourDay = chart("hour", List.of("hour", "day"), null, _window);
		TLObject hourSix = chart("sixHours", List.of("hour", "sixHours"), null, _window);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Function contact = (Function) create("ehome.runtime:Function");
			contact.setNameKey(ResKey.builder().add(Locale.GERMAN, "Fenster").build());
			contact.setIdentifier("window");
			contact.setKind(FunctionProposal.classifier("sensor"));
			contact.setValue(_window);
			_project.setFunctions(List.of(contact));
			// The reach the SQL keeps of a history set by hand: it overrides the charts.
			((KnowledgeItem) _humidity.tHandle()).setAttributeValue(RecordingMigration.REACH, Long.valueOf(2 * D));
			((KnowledgeItem) _temperature.tHandle()).setAttributeValue(RecordingMigration.REACH, Long.valueOf(D));
			// Set by hand without a ring: no history in the image.
			((KnowledgeItem) _pulses.tHandle()).setAttributeValue(RecordingMigration.REACH, Long.valueOf(0));
			tx.commit();
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			RecordingMigration.migrate(_project, new java.util.HashSet<>(), new int[3]);
			tx.commit();
		}
		assertEquals(Level.WEEK, HistoryConfig.level(_humidity));
		assertEquals(Level.YEAR, HistoryConfig.level(power));
		assertEquals(Level.NONE, HistoryConfig.level(_pulses));
		assertEquals(Level.DAY, HistoryConfig.level(_window));
		assertEquals(Level.MONTH, HistoryConfig.level(_counter));
		assertTrue(_counter.getMeter());
		assertNull(power.getMeter());
		assertNull(((KnowledgeItem) _humidity.tHandle()).getAttributeValue(RecordingMigration.REACH));
		// Set by hand to 24 h, drawn over 7 days: recorded for 7 days.
		assertEquals(Level.WEEK, HistoryConfig.level(_temperature));

		// The range 1 h became 6 h, the preset 1 h, too, once.
		assertEquals(range("sixHours"), ((ChartElement) hourDay).getRange());
		assertEquals(Set.of(range("sixHours"), range("day")), Set.copyOf(((ChartElement) hourDay).getPresets()));
		assertEquals(range("sixHours"), ((ChartElement) hourSix).getRange());
		assertEquals(Set.of(range("sixHours")), Set.copyOf(((ChartElement) hourSix).getPresets()));
		Map<?, ?> chart = (Map<?, ?>) ((Map<?, ?>) elements(assertValid()).get(5)).get("chart");
		assertEquals("6h", chart.get("range"));
		assertEquals(List.of("6h", "24h"), chart.get("presets"));
	}

	/** The elements of the first section of the first page of the image. */
	private static List<?> elements(RuntimeImage image) {
		Map<?, ?> ui = (Map<?, ?>) image.getJson().get("ui");
		Map<?, ?> page = (Map<?, ?>) ((List<?>) ui.get("pages")).get(0);
		Map<?, ?> section = (Map<?, ?>) ((List<?>) page.get("sections")).get(0);
		return (List<?>) section.get("elements");
	}

	/**
	 * The SQL of the migration on tables of the layout of the database: the reach of each history set by
	 * hand in the current state, the longest retention of its ring and of its archives; 0 without a
	 * ring; nothing for a history not set by hand or of an earlier revision.
	 */
	public void testMigrationSql() throws Exception {
		try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:issue221", "sa", "");
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE FLEX_DATA (TYPE VARCHAR(70) NOT NULL, IDENTIFIER BIGINT NOT NULL,"
				+ " REV_MAX BIGINT NOT NULL, ATTR VARCHAR(254) NOT NULL, REV_MIN BIGINT NOT NULL, DATA_TYPE TINYINT,"
				+ " LONG_DATA BIGINT, DOUBLE_DATA DOUBLE PRECISION, VARCHAR_DATA VARCHAR(254), CLOB_DATA CLOB,"
				+ " BLOB_DATA BLOB, PRIMARY KEY (TYPE, IDENTIFIER, REV_MAX, ATTR))");
			statement.execute("CREATE TABLE HAS_STRUCTURE_CHILD (IDENTIFIER BIGINT NOT NULL, REV_MAX BIGINT NOT NULL,"
				+ " REV_MIN BIGINT NOT NULL, SOURCE_TYPE VARCHAR(150), SOURCE_ID BIGINT, DEST_TYPE VARCHAR(150),"
				+ " DEST_ID BIGINT, META_ATTRIBUTE_ID BIGINT)");
			statement.execute("CREATE TABLE META_ATTRIBUTE (IDENTIFIER BIGINT NOT NULL, REV_MAX BIGINT NOT NULL,"
				+ " NAME VARCHAR(150))");
			long max = Long.MAX_VALUE;
			statement.execute("INSERT INTO META_ATTRIBUTE VALUES (500, " + max + ", 'historyArchives'), (501, " + max
				+ ", 'series')");
			// 1: by hand, 2 days, an archive of 30 days (and one of an earlier revision, of 3 years);
			// 2: by hand, a ring of 1 day alone; 3: by hand without a ring; 4: not by hand;
			// 5: an application datapoint by hand with 7 days.
			statement.execute("INSERT INTO FLEX_DATA (TYPE, IDENTIFIER, REV_MAX, ATTR, REV_MIN, DATA_TYPE, LONG_DATA) VALUES"
				+ " ('EHGroupAddress', 1, " + max + ", 'historyManual', 10, 1, NULL),"
				+ " ('EHGroupAddress', 1, " + max + ", 'historyRetention', 10, 11, 172800),"
				+ " ('EHGroupAddress', 2, " + max + ", 'historyManual', 11, 1, NULL),"
				+ " ('EHGroupAddress', 2, " + max + ", 'historyRetention', 11, 11, 86400),"
				+ " ('EHGroupAddress', 3, " + max + ", 'historyManual', 12, 1, NULL),"
				+ " ('EHGroupAddress', 4, " + max + ", 'historyManual', 13, 2, NULL),"
				+ " ('EHGroupAddress', 4, " + max + ", 'historyRetention', 13, 11, 3600),"
				+ " ('GenericObject', 5, " + max + ", 'historyManual', 14, 1, NULL),"
				+ " ('GenericObject', 5, " + max + ", 'historyRetention', 14, 11, 604800),"
				+ " ('GenericObject', 100, " + max + ", 'retention', 10, 11, 2592000),"
				+ " ('GenericObject', 101, 20, 'retention', 10, 11, 94608000),"
				+ " ('GenericObject', 102, " + max + ", 'retention', 10, 11, 999999999)");
			statement.execute("INSERT INTO HAS_STRUCTURE_CHILD VALUES"
				+ " (1000, " + max + ", 10, 'EHGroupAddress', 1, 'GenericObject', 100, 500),"
				+ " (1001, 20, 10, 'EHGroupAddress', 1, 'GenericObject', 101, 500),"
				+ " (1002, " + max + ", 10, 'EHGroupAddress', 2, 'GenericObject', 102, 501)");
			String script = Files.readString(new File(
				"src/main/webapp/WEB-INF/kbase/migration/ehome/Issue_221_History_recording_scripts/stashReach.h2.sql")
				.toPath(), StandardCharsets.UTF_8);
			for (String sql : script.replaceAll("(?m)^--.*$", "").split(";")) {
				if (!sql.isBlank()) {
					statement.execute(sql);
				}
			}
			List<String> rows = new ArrayList<>();
			try (ResultSet result = statement.executeQuery("SELECT TYPE, IDENTIFIER, REV_MIN, DATA_TYPE, LONG_DATA"
				+ " FROM FLEX_DATA WHERE ATTR = '" + RecordingMigration.REACH + "' ORDER BY TYPE, IDENTIFIER")) {
				while (result.next()) {
					rows.add(result.getString(1) + " " + result.getLong(2) + " " + result.getLong(3) + " "
						+ result.getInt(4) + " " + result.getLong(5));
				}
			}
			assertEquals(List.of("EHGroupAddress 1 10 11 2592000", "EHGroupAddress 2 11 11 86400",
				"EHGroupAddress 3 12 11 0", "GenericObject 5 14 11 604800"), rows);
		}
	}

	/**
	 * The SQL of the migration of haumacher/ehome #73 (Issue_73_History_set_by_hand) on a table of the
	 * layout of FLEX_DATA: every revision with a retention, or a spacing alone, is marked as set by hand.
	 */
	public void testMigration73Sql() throws Exception {
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

	// ---- helpers -------------------------------------------------------------------------------

	private static void assertStores(Level level, boolean meter, History expected, long values, long ram, long flash) {
		History history = HistoryConfig.stores(level, meter);
		assertEquals(level.name(), expected, history);
		assertEquals(level.name(), values, history.values());
		assertEquals(level.name(), ram, history.ramBytes());
		assertEquals(level.name(), flash, history.flashBytes());
	}

	/** Sets the recording of the datapoint, in a transaction of its own. */
	private static void record(TLObject datapoint, Level level) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			set(datapoint, level);
			tx.commit();
		}
	}

	private static void set(TLObject datapoint, Level level) {
		TLClassifier value = level == null ? null : HistoryConfig.classifier(level);
		if (datapoint instanceof GroupAddress address) {
			address.setRecording(value);
		} else {
			((ApplicationDatapoint) datapoint).setRecording(value);
		}
	}

	private static String schema(RuntimeImage image) {
		return (String) ((Map<?, ?>) image.getJson().get("image")).get("schemaVersion");
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

	/** A chart of the given range, presets and consolidation with a series per datapoint. */
	private TLObject chart(String range, List<String> presets, String consolidation, TLObject... datapoints) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ChartElement chart = (ChartElement) Pages.insert(_project, _section, Pages.CHART, null);
			chart.setRange(range(range));
			Set<TLClassifier> list = new LinkedHashSet<>();
			for (String preset : presets) {
				list.add(range(preset));
			}
			chart.setPresets(list);
			if (datapoints.length > 0) {
				Pages.insert(_project, chart, Pages.SERIES, List.of(datapoints));
			}
			if (consolidation != null) {
				for (TLObject series : RuleFunctions.objects(chart, "series")) {
					((ChartSeries) series).setConsolidation(classifier("ChartConsolidation", consolidation));
				}
			}
			tx.commit();
			return chart;
		}
	}

	private static TLClassifier range(String name) {
		return classifier("ChartRange", name);
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

	private static GroupAddress address(String name, int main, int middle, int sub, TLObject type) {
		GroupAddress address = (GroupAddress) create("ehome.logical:GroupAddress");
		address.setName(name);
		address.setAddress(new GA((main << 11) | (middle << 8) | sub, GroupAddressStyle.ThreeLevel));
		address.setDatapointTypes(List.of(type));
		return address;
	}

	private static TLClassifier classifier(String enumeration, String name) {
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

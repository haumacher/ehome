/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.job.JobHarness;
import test.de.haumacher.ehome.programming.MockDevice;
import test.de.haumacher.ehome.view.ViewCommandScan;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.channel.ViewScripts;
import com.top_logic.layout.view.command.OpenDialogAction;
import com.top_logic.layout.view.job.JobState;
import com.top_logic.layout.view.job.JobStatus;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.physical.BusDevice;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.scan.CatalogMatch;
import de.haumacher.ehome.scan.DeviceStubs;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.scan.I18NConstants;
import de.haumacher.ehome.scan.InstallationBuilder;
import de.haumacher.ehome.scan.ScanBus;
import de.haumacher.ehome.scan.ScanFunctions;
import de.haumacher.ehome.scan.ScanJob;
import de.haumacher.ehome.scan.ScanRows;
import de.haumacher.ehome.scan.ScanTarget;
import de.haumacher.ehome.scan.TakeOverJob;

/**
 * The scan of the bus in the area "Devices" of a project (haumacher/ehome #180): the line selected
 * in the tree fills the field of the dialog, "New devices only" reads no device the project has, and
 * "Take over" creates or updates the devices of several rows and reads their configuration in one go,
 * a row the catalog does not identify becoming a stub in its line without program (haumacher/ehome
 * #183).
 *
 * <p>
 * The bus is the recorded test line 1.3 of {@link TestBusScan} (actuator 1.3.5, Tastsensor 1.3.50,
 * the gateway 1.3.1 not answering through its own tunnel); the devices are taken over into an empty
 * project, the catalog is that of <code>samples/KNX-Testsystem.knxproj</code>, whose project has the
 * devices of the line.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestScanTakeOver extends BasicTestCase {

	private static final int ROUTER = 0x1301;

	private static final int ACTUATOR = 0x1305;

	private static final int TASTSENSOR = 0x1332;

	/** The view of the scan, relative to the views directory. */
	private static final String VIEW = "projects/scan.view.xml";

	/** The view of the devices of a project, relative to the views directory. */
	private static final String DEVICES = "projects/devices.view.xml";

	private static final String VIEWS = "src/main/webapp/WEB-INF/views/";

	/** The programs the devices of the line run, as the project the devices were programmed from has them. */
	private static final Map<Integer, String> PROGRAMS = Map.of(
		Integer.valueOf(ACTUATOR), "M-0083_A-000C-21-83B5",
		Integer.valueOf(TASTSENSOR), "M-0008_A-10D7-11-A0E5-O000A");

	/** The projects before the test. */
	static List<TLObject> _before;

	/** The test system, whose project has the devices of the line. */
	static TLObject _original;

	/** An empty project the devices are taken over into. */
	static TLObject _scanned;

	/**
	 * "Take over" on several rows: the actuator and the Tastsensor are created with their
	 * configuration read in one go, a device the catalog does not identify becomes a stub in its line
	 * without program, whose row says why, and is not read; "Take over all" updates them and reads the
	 * devices again.
	 */
	public void testTakeOverSeveralRows() throws Exception {
		Map<String, Object> result = scan(_scanned, false, new TestBusScan.FakeBus());
		List<Map<?, ?>> rows = ScanFunctions.rows(result);
		assertEquals(addresses(rows).toString(), List.of("1.3.5", "1.3.50"), addresses(rows));
		result = chooseEditions(result);

		// A device the catalog does not know, as a scan of another line would list it.
		FoundDevice unknown = new FoundDevice(0x1307, FoundDevice.Source.BUS, 0x0705, 0x08, null,
			HexFormat.of().parseHex("000810d712"), null, null, List.of(), List.of());
		Map<String, Object> unknownRow = ScanRows.row(_scanned, unknown, CatalogMatch.match(_scanned, unknown));
		List<Object> withUnknown = new ArrayList<>(ScanFunctions.rows(result));
		withUnknown.add(unknownRow);
		result = scanResult(result, withUnknown);

		Set<Object> selected = new LinkedHashSet<>(ScanFunctions.rows(result));
		assertNull("Several rows are no single row.", ScanFunctions.singleRow(selected));
		assertSame(unknownRow, ScanFunctions.singleRow(unknownRow));
		assertSame(unknownRow, ScanFunctions.singleRow(Set.of(unknownRow)));
		assertNull(ScanFunctions.singleRow(null));
		assertNull("One row can be taken over at least.", ScanFunctions.takeOverVeto(selected));
		assertNull("Taken over as a stub.", ScanFunctions.takeOverVeto(unknownRow));
		assertEquals(text(I18NConstants.STATE_UNIDENTIFIED), unknownRow.get(ScanRows.STATE));

		Map<String, MockDevice> devices = new HashMap<>();
		Map<String, Object> channels = new HashMap<>();
		channels.put("project", _scanned);
		channels.put("selected", selected);
		channels.put("result", result);
		Map<?, ?> taken = takeOver(takeOverCommand(false).jobValues(channels), false, devices);

		assertEquals(text(I18NConstants.TAKE_OVER_DONE__TAKEN_READ_REFUSED.fill(3, 2, 0)), taken.get(TakeOverJob.TAKE_OVER));
		assertEquals("The display of the job shows what the take-over did.", taken.get(TakeOverJob.TAKE_OVER),
			taken.toString());
		assertEquals("The summary of the scan stays.", result.get("summary"), taken.get("summary"));
		List<Map<?, ?>> after = ScanFunctions.rows(taken);
		assertEquals(3, after.size());
		for (int address : new int[] { ACTUATOR, TASTSENSOR }) {
			Map<?, ?> row = row(after, address);
			String state = (String) row.get(ScanRows.STATE);
			assertTrue(state, state.startsWith(prefix(I18NConstants.TAKE_OVER_CREATED_READ__SETTINGS_CONNECTIONS)));
			assertNull("The configuration read equals the device: " + state, row.get(ScanRows.STATE_ROLE));
			TLObject device = InstallationBuilder.device(_scanned, address);
			assertNotNull(device);
			assertEquals(PROGRAMS.get(Integer.valueOf(address)),
				DeviceConfiguration.program(device).getExtId());
			assertNotNull("Recorded as downloaded: the device holds what the project says.", ((Device) device).getLastDownload());
			assertTrue("The memory was read.", devices.get(FoundDevice.formatAddress(address)).calls().size() > 0);
			assertEquals(0, devices.get(FoundDevice.formatAddress(address)).writes());
		}
		Map<?, ?> stubRow = row(after, 0x1307);
		assertEquals(text(I18NConstants.TAKE_OVER_STUB_CREATED__REASON.fill(ScanRows.match(unknownRow).reasonKey())),
			stubRow.get(ScanRows.STATE));
		assertEquals("warning", stubRow.get(ScanRows.STATE_ROLE));
		TLObject stub = InstallationBuilder.device(_scanned, 0x1307);
		assertTrue("A stub in its line: " + stub, DeviceStubs.isStub(stub));
		assertSame(InstallationBuilder.device(_scanned, ACTUATOR).tContainer(), stub.tContainer());
		assertEquals(Integer.valueOf(7), ((BusDevice) stub).getAddress());
		assertNull("No program.", DeviceConfiguration.program(stub));
		assertFalse("Its configuration is not read.", devices.containsKey("1.3.7"));

		// All rows again: the devices are updated and read once more.
		devices.clear();
		channels.put("result", taken);
		Map<?, ?> again = takeOver(takeOverCommand(true).jobValues(channels), true, devices);
		assertEquals(text(I18NConstants.TAKE_OVER_DONE__TAKEN_READ_REFUSED.fill(3, 2, 0)), again.get(TakeOverJob.TAKE_OVER));
		assertEquals(text(I18NConstants.TAKE_OVER_STUB_UPDATED__REASON.fill(ScanRows.match(unknownRow).reasonKey())),
			row(ScanFunctions.rows(again), 0x1307).get(ScanRows.STATE));
		assertSame("The stub is updated.", stub, InstallationBuilder.device(_scanned, 0x1307));
		String state = (String) row(ScanFunctions.rows(again), ACTUATOR).get(ScanRows.STATE);
		assertTrue(state, state.startsWith(prefix(I18NConstants.TAKE_OVER_UPDATED_READ__SETTINGS_CONNECTIONS)));
		assertEquals(Set.of("1.3.5", "1.3.50"), devices.keySet());
	}

	/**
	 * The tooltip of the address joins how the device was read, its mask version and its load
	 * states without a sentence period inside the list.
	 */
	public void testDetails() throws Exception {
		Map<?, ?> actuator = row(ScanFunctions.rows(scan(_scanned, false, new TestBusScan.FakeBus())), ACTUATOR);
		String details = (String) actuator.get(ScanRows.DETAILS);
		assertTrue(details, details.startsWith(text(I18NConstants.DETAILS_READ_BUS) + "; "
			+ text(I18NConstants.DETAILS_MASK__MASK.fill("0705")) + "; "));
		assertFalse(details, details.contains(".;"));
		assertFalse(details, details.endsWith("."));
	}

	/**
	 * A scan whose last phase has nothing to do (no device in programming mode) ends with that phase
	 * complete, not at 0 %.
	 */
	public void testPhaseWithNothingToDoCompletes() throws Exception {
		Map<String, Object> channels = new HashMap<>();
		channels.put("project", _scanned);
		channels.put("target", ScanTarget.PROGRAMMING_MODE);
		channels.put("newOnly", Boolean.FALSE);
		channels.put("busState", Long.valueOf(1));
		JobHarness job = new JobHarness(List.of());
		job.start(new ScanJob(null, null) {
			@Override
			protected ScanBus openBus(BusPath path) {
				return new TestBusScan.FakeBus();
			}
		}, ViewCommandScan.command(VIEW, "ScanJob").jobValues(channels));
		JobState state = job.await();
		assertEquals(String.valueOf(state.error()), JobStatus.COMPLETED, state.status());
		assertEquals(List.of(), ScanFunctions.rows(state.result()));
		assertEquals(Double.valueOf(1), state.fraction());
	}

	/**
	 * A device whose configuration cannot be read is created all the same, and its row says why.
	 */
	public void testCreatedWhereNotReadable() throws Exception {
		TLObject project = emptyProject("Unreadable");
		try {
			Map<String, Object> result = chooseEditions(scan(project, false, new TestBusScan.FakeBus()));
			Map<?, ?> actuator = row(ScanFunctions.rows(result), ACTUATOR);
			Map<String, Object> channels = new HashMap<>();
			channels.put("project", project);
			channels.put("selected", actuator);
			channels.put("result", result);
			JobHarness job = new JobHarness(List.of());
			job.start(new TakeOverJob(null, takeOverConfig(false)) {
				@Override
				protected DeviceReader openReader(BusPath path, int address) throws BusException {
					throw new BusException("No answer.");
				}
			}, takeOverCommand(false).jobValues(channels));
			JobState state = job.await();
			assertEquals(String.valueOf(state.error()), JobStatus.COMPLETED, state.status());
			Map<?, ?> taken = (Map<?, ?>) state.result();
			assertEquals(text(I18NConstants.TAKE_OVER_DONE__TAKEN_READ_REFUSED.fill(1, 0, 0)), taken.get(TakeOverJob.TAKE_OVER));
			Map<?, ?> row = row(ScanFunctions.rows(taken), ACTUATOR);
			assertEquals(text(I18NConstants.TAKE_OVER_CREATED_NOT_READ__REASON.fill(new BusException("No answer.").getErrorKey())),
				row.get(ScanRows.STATE));
			assertEquals("warning", row.get(ScanRows.STATE_ROLE));
			TLObject device = InstallationBuilder.device(project, ACTUATOR);
			assertNotNull("Created all the same.", device);
			assertNull("Not recorded as downloaded.", ((Device) device).getLastDownload());
			assertNull("The Tastsensor was not selected.", InstallationBuilder.device(project, TASTSENSOR));
		} finally {
			delete(project);
		}
	}

	/**
	 * "New devices only": the addresses at which the project has a device are not read - no
	 * descriptor, no property, no memory - only noted; the others are read as always.
	 */
	public void testNewOnlyReadsNoKnownAddress() throws Exception {
		Set<Integer> known = InstallationBuilder.addresses(_original);
		assertTrue(known.toString(), known.containsAll(Set.of(Integer.valueOf(ACTUATOR), Integer.valueOf(TASTSENSOR))));

		CountingBus bus = new CountingBus();
		Map<String, Object> result = scan(_original, true, bus);
		for (Integer address : known) {
			assertFalse("Not connected to the known " + FoundDevice.formatAddress(address.intValue()) + ": " + bus.connected(),
				bus.connected().contains(address));
		}
		assertEquals("Nothing of a known device was read.", 0, bus.reads(known));
		assertEquals(List.of(), ScanFunctions.rows(result));
		List<?> notes = (List<?>) result.get("notes");
		assertTrue(notes.toString(), notes.contains(text(I18NConstants.NOTE_KNOWN_NOT_READ__ADDRESSES.fill(
			knownText(known, List.of(Integer.valueOf(ROUTER), Integer.valueOf(ACTUATOR), Integer.valueOf(TASTSENSOR)))))));

		// The same scan without the option reads them.
		CountingBus all = new CountingBus();
		Map<String, Object> full = scan(_original, false, all);
		assertEquals(List.of("1.3.5", "1.3.50"), addresses(ScanFunctions.rows(full)));
		assertTrue(all.reads(known) > 0);

		// Into an empty project, the option reads every device.
		TLObject project = emptyProject("New only");
		try {
			CountingBus fresh = new CountingBus();
			assertEquals(List.of("1.3.5", "1.3.50"), addresses(ScanFunctions.rows(scan(project, true, fresh))));
		} finally {
			delete(project);
		}
	}

	/**
	 * The command "Scan the bus" of the devices view: a line selected in the tree fills the field
	 * "Line" of the dialog, an area or the project leaves it empty; it takes the same access check as
	 * the scan.
	 */
	public void testLineFromTheTree() throws Exception {
		TLObject line = InstallationBuilder.device(_original, ACTUATOR).tContainer();
		TLObject area = line.tContainer();
		assertEquals("1.3", ScanFunctions.lineText(line));
		assertNull(ScanFunctions.lineText(area));
		assertNull(ScanFunctions.lineText(_original));
		assertNull("A device is no line.", ScanFunctions.lineText(InstallationBuilder.device(_original, ACTUATOR)));
		assertNull(ScanFunctions.lineText(null));

		// The derived channel of the devices view the dialog's line starts with.
		Element channel = element(DEVICES, "derived-channel", "name", "scanLine");
		assertEquals("selected", channel.getAttribute("inputs"));
		QueryExecutor scanLine = ViewScripts.compile(ExprFormat.INSTANCE.getValue("expr", channel.getAttribute("expr")),
			List.of("selected"));
		assertEquals("1.3", scanLine.execute(line));
		assertNull(scanLine.execute(area));
		assertNull(scanLine.execute(_original));
		assertNull(scanLine.execute((Object) null));

		// The command binds it to the dialog, whose field "Line" starts with it.
		ViewCommandScan.Site open = scanDialogCommand();
		OpenDialogAction.Config dialog = (OpenDialogAction.Config) ViewCommandScan.items(open.config()).stream()
			.filter(OpenDialogAction.Config.class::isInstance).findFirst().orElseThrow();
		assertEquals("project", dialog.getBindInputTo());
		assertEquals(1, dialog.getBindings().size());
		assertEquals("startLine", dialog.getBindings().get(0).getChannel());
		assertEquals("scanLine", dialog.getBindings().get(0).getTo().getChannelName());
		Element field = element(VIEW, "channel", "name", "line");
		assertEquals("startLine", field.getAttribute("inputs"));
		QueryExecutor init = ViewScripts.compile(ExprFormat.INSTANCE.getValue("init", field.getAttribute("init")),
			List.of("startLine"));
		assertEquals("1.3", init.execute("1.3"));
		assertNull(init.execute((Object) null));
		assertNotNull("The access of the command is declared.", open.access());
	}

	/**
	 * Scans the recorded line 1.3 for the given project.
	 */
	private static Map<String, Object> scan(TLObject project, boolean newOnly, ScanBus bus) throws Exception {
		Map<String, Object> channels = new HashMap<>();
		channels.put("project", project);
		channels.put("target", ScanTarget.LINE);
		channels.put("line", "1.3");
		channels.put("newOnly", Boolean.valueOf(newOnly));
		channels.put("busState", Long.valueOf(1));
		JobHarness job = new JobHarness(List.of());
		job.start(new ScanJob(null, null) {
			@Override
			protected ScanBus openBus(BusPath path) {
				return bus;
			}
		}, ViewCommandScan.command(VIEW, "ScanJob").jobValues(channels));
		JobState state = job.await();
		assertEquals(String.valueOf(state.error()), JobStatus.COMPLETED, state.status());
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) state.result();
		return result;
	}

	/**
	 * Runs a take-over reading the devices from their recorded memory.
	 *
	 * @param devices
	 *        Takes the devices read, by address.
	 */
	static Map<?, ?> takeOver(List<Object> arguments, boolean all, Map<String, MockDevice> devices)
			throws Exception {
		JobHarness job = new JobHarness(List.of());
		// Configured as the command of the view configures it.
		job.start(new TakeOverJob(null, takeOverConfig(all)) {
			@Override
			protected DeviceReader openReader(BusPath path, int address) throws BusException {
				MockDevice device = new TestBusScan.FakeBus().device(address);
				devices.put(FoundDevice.formatAddress(address), device);
				return device;
			}
		}, arguments);
		JobState state = job.await();
		assertEquals(String.valueOf(state.error()), JobStatus.COMPLETED, state.status());
		return (Map<?, ?>) state.result();
	}

	/**
	 * The result with the edition of each program the project the devices were programmed from has,
	 * where the catalog holds several (the ETS 4 and ETS 6 exports of the test system).
	 */
	private static Map<String, Object> chooseEditions(Map<String, Object> result) {
		for (Map<?, ?> row : ScanFunctions.rows(result)) {
			if (ScanRows.match(row).kind() != CatalogMatch.Kind.CHOICE) {
				continue;
			}
			assertNull(ScanFunctions.chooseVeto(row));
			String program = PROGRAMS.get(Integer.valueOf(ScanRows.found(row).address()));
			Object candidate = ((List<?>) row.get("candidates")).stream()
				.filter(c -> program.equals(((Map<?, ?>) c).get("program"))).findFirst().orElseThrow();
			result = ScanFunctions.choose(result, row, candidate);
		}
		for (Map<?, ?> row : ScanFunctions.rows(result)) {
			assertEquals(CatalogMatch.Kind.UNIQUE, ScanRows.match(row).kind());
			assertEquals(I18NConstants.ERROR_NO_CHOICE, ScanFunctions.chooseVeto(row));
		}
		return result;
	}

	private static Map<String, Object> scanResult(Map<String, Object> result, List<Object> rows) {
		Map<String, Object> copy = new HashMap<>(result);
		copy.put("rows", rows);
		return copy;
	}

	/** The command of the scan view starting the take-over: of the selected rows, or of all. */
	static ViewCommandScan.Site takeOverCommand(boolean all) {
		for (ViewCommandScan.Site site : ViewCommandScan.commands(VIEW)) {
			for (ConfigurationItem item : ViewCommandScan.items(site.config())) {
				if (item instanceof TakeOverJob.Config job && job.getAll() == all) {
					return site;
				}
			}
		}
		throw new AssertionError("No take-over command (all: " + all + ") in " + VIEW);
	}

	/** The configuration of the take-over of the command of the scan view. */
	private static TakeOverJob.Config takeOverConfig(boolean all) {
		return ViewCommandScan.items(takeOverCommand(all).config()).stream()
			.filter(item -> item instanceof TakeOverJob.Config job && job.getAll() == all)
			.map(TakeOverJob.Config.class::cast).findFirst().orElseThrow();
	}

	/** The command of the devices view opening the scan. */
	static ViewCommandScan.Site scanDialogCommand() {
		for (ViewCommandScan.Site site : ViewCommandScan.commands(DEVICES)) {
			for (ConfigurationItem item : ViewCommandScan.items(site.config())) {
				if (item instanceof OpenDialogAction.Config dialog && VIEW.equals(dialog.getDialogView().getPath())) {
					return site;
				}
			}
		}
		List<String> found = new ArrayList<>();
		for (ViewCommandScan.Site site : ViewCommandScan.commands(DEVICES)) {
			for (ConfigurationItem item : ViewCommandScan.items(site.config())) {
				if (item instanceof OpenDialogAction.Config dialog) {
					found.add(dialog.getDialogView().getPath());
				}
			}
		}
		throw new AssertionError("No command opening " + VIEW + " in " + DEVICES + ": " + found);
	}

	static Map<?, ?> row(List<Map<?, ?>> rows, int address) {
		return rows.stream().filter(r -> ScanRows.found(r).address() == address).findFirst().orElseThrow();
	}

	private static List<String> addresses(List<Map<?, ?>> rows) {
		return rows.stream().map(r -> (String) r.get("address")).toList();
	}

	private static String knownText(Set<Integer> known, List<Integer> line) {
		return String.join(", ", line.stream().filter(known::contains).map(a -> FoundDevice.formatAddress(a.intValue())).toList());
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	/** The text of the given message before its first argument. */
	private static String prefix(ResKey2 key) {
		String text = text(key.fill("\0", "\0"));
		return text.substring(0, text.indexOf('\0'));
	}

	private static Element element(String view, String tag, String attribute, String value) throws Exception {
		NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(VIEWS + view))
			.getElementsByTagName(tag);
		for (int n = 0; n < nodes.getLength(); n++) {
			Element element = (Element) nodes.item(n);
			if (value.equals(element.getAttribute(attribute))) {
				return element;
			}
		}
		throw new AssertionError("No " + tag + " " + attribute + "=" + value + " in " + view);
	}

	/**
	 * The line 1.3 of {@link TestBusScan.FakeBus}, counting the connections to its devices and what
	 * is read from them.
	 */
	static class CountingBus extends TestBusScan.FakeBus {

		private final List<Integer> _connected = new ArrayList<>();

		private final Map<Integer, MockDevice> _devices = new HashMap<>();

		List<Integer> connected() {
			return _connected;
		}

		/** The number of reads of the devices at the given addresses. */
		int reads(Set<Integer> addresses) {
			return _devices.entrySet().stream().filter(e -> addresses.contains(e.getKey()))
				.mapToInt(e -> e.getValue().calls().size()).sum();
		}

		@Override
		public MockDevice device(int address) throws BusException {
			_connected.add(Integer.valueOf(address));
			MockDevice device = super.device(address);
			_devices.put(Integer.valueOf(address), device);
			return device;
		}
	}

	static TLObject emptyProject(String name) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Project project = (Project) ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.setName(name);
			project.setGroupAddressStyle((TLClassifier) TLModelUtil.findPart("ehome.logical:GroupAddressStyle#ThreeLevel"));
			project.setGateway("localhost");
			ExtIds.assign(project);
			tx.commit();
			return project;
		}
	}

	static void delete(TLObject project) {
		ThreadContext.inSystemContext(TestScanTakeOver.class, () -> {
			SharedData.deleteProjects(List.of(project));
			return null;
		});
	}

	/**
	 * Imports the test system and creates the empty project for the tests of this class, and deletes
	 * them again.
	 */
	private static Test withProjects(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestScanTakeOver.class, () -> {
					_before = instances("ehome.project:Project");
					importSample("samples/KNX-Testsystem.knxproj");
					List<TLObject> projects = SharedData.newProjects(_before);
					assertEquals(1, projects.size());
					_original = projects.get(0);
					try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
						((Project) _original).setGateway("localhost");
						tx.commit();
					}
					_scanned = emptyProject("Scan");
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestScanTakeOver.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					return null;
				});
				_original = null;
				_scanned = null;
				_before = null;
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(withProjects(new TestSuite(TestScanTakeOver.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SchedulerService.Module.INSTANCE));
	}

}

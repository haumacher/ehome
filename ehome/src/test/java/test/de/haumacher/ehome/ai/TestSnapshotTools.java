/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import static test.de.haumacher.ehome.ai.AssistantFixture.as;
import static test.de.haumacher.ehome.ai.AssistantFixture.inTransaction;
import static test.de.haumacher.ehome.ai.AssistantFixture.tid;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.programming.MockDevice;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.ai.core.agent.AgentApplicationService;
import com.top_logic.ai.core.agent.AgentPolicyService;
import com.top_logic.ai.core.agent.AgentRuntimeService;
import com.top_logic.ai.core.agent.ToolContext;
import com.top_logic.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.ai.core.security.AiToolAccessService;
import com.top_logic.ai.tool.api.AnnotatedTools;
import com.top_logic.ai.tool.api.Tool;
import com.top_logic.ai.tool.api.ToolMode;
import com.top_logic.ai.tool.api.ToolResult;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.ai.SnapshotTools;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ProgrammingReport;
import de.haumacher.ehome.programming.download.SnapshotFunctions;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.programming.download.Snapshots;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.scan.SnapshotTakeOver;

/**
 * The snapshot tools of the assistant (haumacher/ehome #193, {@link SnapshotTools}) on the device of
 * <code>samples/Test.knxproj</code> behind a gateway, read through a device in memory
 * ({@link MockDevice}) into which the project's plan was downloaded: the read-out stores a snapshot
 * equal to the plan, a removed connection shows as differing table bytes with the device's and the
 * plan's values, the list names the snapshots newest first, and the take-over gives the project the
 * connection back, after which the snapshot compares equal. A viewer reads, compares and lists but is
 * refused the read-out and the take-over. The take-over runs in the turn's transaction as a
 * <code>DATA_WRITE</code> call does and records the revision the turn commits as the device's download
 * state, nothing when the turn is rolled back, and the revision of its own commit as the view's command
 * (TL #30531).
 */
@SuppressWarnings("javadoc")
public class TestSnapshotTools extends BasicTestCase {

	private static List<TLObject> _before;

	private static TLObject _device;

	private static Person _installer;

	private static Person _viewer;

	public void testToolsAreBuiltWithTheirModes() {
		List<Tool> tools = AnnotatedTools.from(new SnapshotTools());
		assertEquals(5, tools.size());
		for (Tool tool : tools) {
			ToolMode expected = tool.name().endsWith("readOutDevice") || tool.name().endsWith("takeOverSnapshot")
				? ToolMode.DATA_WRITE : ToolMode.READ;
			assertEquals(tool.name(), expected, tool.mode());
		}
	}

	public void testReadOutCompareListTakeOver() throws Exception {
		MockDevice mock = downloaded();
		SnapshotTools tools = reading(mock);
		int snapshotsBefore = Snapshots.of(_device).size();

		// The read-out of the device just downloaded: a snapshot equal to the plan.
		ToolResult readOut = as(_installer, () -> tools.readOutDevice(AssistantFixture.toolContext(), _device));
		assertFalse(readOut.text(), readOut.failed());
		Map<?, ?> answer = json(readOut);
		Map<?, ?> snapshot = (Map<?, ?>) answer.get("snapshot");
		List<TLObject> stored = Snapshots.of(_device);
		assertEquals(snapshotsBefore + 1, stored.size());
		TLObject first = byId(stored, (String) snapshot.get("id"));
		assertEquals(Boolean.TRUE, snapshot.get("complete"));
		assertEquals(as(_installer, () -> SnapshotFunctions.summary(Snapshots.load(first))), snapshot.get("summary"));
		assertNotNull(snapshot.get("label"));
		Map<?, ?> comparison = (Map<?, ?>) answer.get("comparison");
		assertEquals(readOut.text(), Boolean.TRUE, comparison.get("complete"));
		assertEquals(readOut.text(), List.of(), comparison.get("differences"));
		assertFalse(((List<?>) comparison.get("messages")).isEmpty());

		// The memory as read (#228): every byte of the snapshot, 16 per line, as the view stores it.
		Snapshot read = as(_installer, () -> Snapshots.load(first));
		ToolResult memory = as(_viewer, () -> tools.snapshotMemory(first, null, null));
		assertFalse(memory.text(), memory.failed());
		Map<?, ?> dump = json(memory);
		List<?> ranges = (List<?>) dump.get("memory");
		assertEquals(read.ranges().size(), ranges.size());
		int bytes = 0;
		for (int i = 0; i < ranges.size(); i++) {
			Map<?, ?> range = (Map<?, ?>) ranges.get(i);
			Snapshot.Range expected = read.ranges().get(i);
			assertEquals(String.format("%04X", expected.address()), range.get("from"));
			assertEquals(String.format("%04X", expected.end() - 1), range.get("to"));
			StringBuilder hex = new StringBuilder();
			for (Object line : (List<?>) range.get("lines")) {
				String text = (String) line;
				assertTrue(text, text.matches("[0-9A-F]{4}:( ([0-9A-F]{2}|\\?\\?)){1,16}"));
				hex.append(text.substring(5));
			}
			assertEquals(expected.hex(), hex.toString().replace(" ", ""));
			bytes += expected.data().length;
		}
		assertTrue(bytes > 0);
		assertEquals(read.getProgram(), dump.get("program"));
		// A range limits the answer to its bytes; a line starts at the range's first address.
		Snapshot.Range firstRange = read.ranges().get(0);
		String from = String.format("%04X", firstRange.address() + 1);
		String to = String.format("%04X", firstRange.address() + 3);
		Map<?, ?> part = json(as(_viewer, () -> tools.snapshotMemory(first, from, to)));
		List<?> partRanges = (List<?>) part.get("memory");
		assertEquals(1, partRanges.size());
		List<?> partLines = (List<?>) ((Map<?, ?>) partRanges.get(0)).get("lines");
		String partText = partLines.stream().map(String.class::cast).reduce("", (a, b) -> a + b.substring(5));
		assertEquals(firstRange.hex().substring(2, 8), partText.replace(" ", ""));
		assertTrue(((String) partLines.get(0)).startsWith(from + ":"));
		assertTrue(as(_viewer, () -> tools.snapshotMemory(first, "zz", null)).failed());
		assertTrue(as(_viewer, () -> tools.snapshotMemory(first, "0200", "0100")).failed());

		// The project loses a connection: the snapshot differs in the tables, with both bytes.
		int connections = connections().size();
		TLObject removed = connections().get(0);
		inTransaction(() -> DeviceSettings.disconnect(removed));
		assertEquals(connections - 1, connections().size());
		ToolResult compared = as(_viewer, () -> tools.compareSnapshot(first));
		assertFalse(compared.text(), compared.failed());
		Map<?, ?> report = (Map<?, ?>) json(compared).get("comparison");
		List<?> differences = (List<?>) report.get("differences");
		assertFalse(compared.text(), differences.isEmpty());
		assertEquals(differences.size(), ((Number) report.get("differingRuns")).intValue());
		// Every run, nothing truncated: as many as the view's report has.
		List<?> viewRows = (List<?>) SnapshotFunctions.compare(first).get(ProgrammingReport.DIFFERENCES);
		assertEquals(viewRows.size(), differences.size());
		boolean table = false;
		for (Object element : differences) {
			Map<?, ?> difference = (Map<?, ?>) element;
			String device = (String) difference.get("device");
			String plan = (String) difference.get("plan");
			assertNotNull(difference.get("block"));
			assertTrue(compared.text(), ((String) difference.get("addresses")).matches("[0-9A-F]{4}(-[0-9A-F]{4})?"));
			assertEquals(device.split(" ").length, plan.split(" ").length);
			assertFalse(device.equals(plan));
			table |= ((String) difference.get("block")).toLowerCase().contains("tab");
		}
		assertTrue("A table differs: " + compared.text(), table);

		// A second read-out, as the turn of an agent runs it: in the turn's transaction, stored with its
		// commit. The list names both, newest first, as the panel does.
		ToolResult second = as(_installer, () -> inTurn(context -> tools.readOutDevice(context, _device), true, null));
		assertFalse(second.text(), second.failed());
		assertTrue(second.text(),
			((Number) ((Map<?, ?>) json(second).get("comparison")).get("differingRuns")).intValue() > 0);
		ToolResult listed = as(_viewer, () -> tools.listSnapshots(_device));
		List<String> ids = new ArrayList<>();
		for (Object entry : (List<?>) json(listed).get("snapshots")) {
			ids.add((String) ((Map<?, ?>) entry).get("id"));
		}
		assertEquals(Snapshots.of(_device).stream().map(AssistantFixture::tid).toList(), ids);
		assertEquals(snapshotsBefore + 2, ids.size());
		assertTrue(ids.contains(tid(first)));

		// A viewer may not read out (it stores a snapshot) nor take over.
		ToolResult viewerReadOut = as(_viewer, () -> tools.readOutDevice(AssistantFixture.toolContext(), _device));
		assertTrue(viewerReadOut.failed());
		assertTrue(viewerReadOut.text(), viewerReadOut.text().contains("Permission denied"));
		ToolResult viewerTakeOver = as(_viewer, () -> tools.takeOverSnapshot(AssistantFixture.toolContext(), first));
		assertTrue(viewerTakeOver.failed());
		assertTrue(viewerTakeOver.text(), viewerTakeOver.text().contains("Permission denied"));
		assertEquals(snapshotsBefore + 2, Snapshots.of(_device).size());
		assertEquals(connections - 1, connections().size());

		// A turn rolled back after the take-over: nothing taken over, nothing recorded as downloaded.
		Object downloadedBefore = ((Device) _device).getDownloadRevision();
		assertTrue("The removed connection is a change since the download.",
			DeviceChanges.sinceDownload(_device).getCount() > 0);
		ToolResult rolledBack = as(_installer, () -> inTurn(context -> tools.takeOverSnapshot(context, first), false, null));
		assertFalse(rolledBack.text(), rolledBack.failed());
		assertEquals(connections - 1, connections().size());
		assertEquals(downloadedBefore, ((Device) _device).getDownloadRevision());

		// The take-over in the turn's transaction (DATA_WRITE, TL #30531) gives the project the connection
		// back; the snapshot then compares equal, and the device holds what the project says in the
		// revision the turn committed: nothing changed since its download.
		long[] turnRevision = { 0 };
		ToolResult takenOver =
			as(_installer, () -> inTurn(context -> tools.takeOverSnapshot(context, first), true, turnRevision));
		assertFalse(takenOver.text(), takenOver.failed());
		Map<?, ?> after = json(takenOver);
		assertFalse(((String) after.get("takenOver")).isBlank());
		Map<?, ?> afterComparison = (Map<?, ?>) after.get("comparison");
		assertEquals(takenOver.text(), 0, ((Number) afterComparison.get("differingRuns")).intValue());
		assertEquals(connections, connections().size());
		assertEquals(Long.valueOf(turnRevision[0]), ((Device) _device).getDownloadRevision());
		assertEquals(0, DeviceChanges.sinceDownload(_device).getCount());
		assertFalse(DeviceChanges.sinceDownload(_device).isChanged(_device));

		// The view's command "Take over" in its own transaction: the revision it committed.
		TLObject removedAgain = connections().get(0);
		inTransaction(() -> DeviceSettings.disconnect(removedAgain));
		assertTrue(DeviceChanges.sinceDownload(_device).getCount() > 0);
		long beforeCommand = lastCommit();
		as(_installer, () -> SnapshotTakeOver.takeOver(first));
		assertEquals(connections, connections().size());
		// Two commits: the take-over's, then the record of the revision it created.
		assertEquals(beforeCommand + 2, lastCommit());
		assertEquals(Long.valueOf(beforeCommand + 1), ((Device) _device).getDownloadRevision());
		assertEquals(0, DeviceChanges.sinceDownload(_device).getCount());
	}

	/**
	 * Runs a tool call as a turn of an agent runs a <code>DATA_WRITE</code> call: in the turn's
	 * transaction, begun before the call and committed or rolled back after it.
	 *
	 * @param committed
	 *        Where to put the commit number of the turn's commit, <code>null</code> for none.
	 */
	private static ToolResult inTurn(Function<ToolContext, ToolResult> call, boolean commit, long[] committed) {
		ToolContext context = AssistantFixture.toolContext();
		context.transaction().begin();
		try {
			ToolResult result = call.apply(context);
			if (commit) {
				context.transaction().commit();
				if (committed != null) {
					committed[0] = context.transaction().commitRevision().getCommitNumber();
				}
			} else {
				context.transaction().rollback();
			}
			return result;
		} finally {
			context.transaction().close();
		}
	}

	private static long lastCommit() {
		return HistoryUtils.getLastRevision().getCommitNumber();
	}

	public void testNoDeviceNoSnapshot() {
		SnapshotTools tools = new SnapshotTools();
		TLObject project = _device.tContainer();
		while (!(project instanceof Project)) {
			project = project.tContainer();
		}
		TLObject notADevice = project;
		assertTrue(as(_installer, () -> tools.readOutDevice(AssistantFixture.toolContext(), notADevice)).failed());
		assertTrue(as(_installer, () -> tools.listSnapshots(notADevice)).failed());
		assertTrue(as(_installer, () -> tools.compareSnapshot(_device)).failed());
		assertTrue(as(_installer, () -> tools.snapshotMemory(_device, null, null)).failed());
		assertTrue(as(_installer, () -> tools.takeOverSnapshot(AssistantFixture.toolContext(), _device)).failed());
	}

	private static SnapshotTools reading(DeviceReader reader) {
		return new SnapshotTools() {
			@Override
			protected DeviceReader openReader(BusPath path, int address) {
				return reader;
			}
		};
	}

	private static Map<?, ?> json(ToolResult result) throws Exception {
		String text = result.text();
		return (Map<?, ?>) Json.parse(text.substring(text.indexOf('\n') + 1), 20);
	}

	private static TLObject byId(List<TLObject> objects, String id) {
		for (TLObject object : objects) {
			if (tid(object).equals(id)) {
				return object;
			}
		}
		fail("No " + id + " in " + objects);
		return null;
	}

	/**
	 * The connections of the device, in a fixed order.
	 */
	private static List<TLObject> connections() {
		List<TLObject> result = new ArrayList<>();
		for (TLObject connector : ExtIds.list(_device, "connectors")) {
			result.addAll(ExtIds.list(connector, "connections"));
		}
		assertFalse("The device has connections.", result.isEmpty());
		result.sort(Comparator.comparing(AssistantFixture::tid));
		return result;
	}

	/**
	 * A device in memory into which the current plan of {@link #_device} was downloaded.
	 */
	private static MockDevice downloaded() throws InterruptedException {
		DownloadPlan plan = DownloadPlanner.plan(_device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		MockDevice mock = new MockDevice().setDescriptor("0705").manufacturerOf(plan);
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.CompareProperty compare) {
				mock.setPropertyElement(compare.objectIndex(), compare.propertyId(), compare.start(), compare.expected());
			}
		}
		Block addressTable = plan.getImage().getAddressTable();
		Block associationTable = plan.getImage().getAssociationTable();
		mock.setProperty(1, 7, String.format("%04X", addressTable.address()));
		mock.setProperty(2, 7, String.format("%04X", associationTable.address()));
		DownloadExecutor.Result result = DownloadService.download(_device, plan, mock,
			new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
		assertTrue(result.failure(), result.success());
		return mock;
	}

	private static Test withData(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestSnapshotTools.class, () -> {
					_before = instances("ehome.project:Project");
					importSample("samples/Test.knxproj");
					List<TLObject> projects = SharedData.newProjects(_before);
					assertEquals(1, projects.size());
					Project project = (Project) projects.get(0);
					_device = SharedData.within("ehome.physical:Device", projects).get(0);
					inTransaction(() -> {
						project.setGateway("localhost");
						_installer = AssistantFixture.person("ehomeSnapshotInstaller", "ehome.installers");
						_viewer = AssistantFixture.person("ehomeSnapshotViewer", null);
						ProjectMembers.add(project, _installer, Role.editor);
						ProjectMembers.add(project, _viewer, Role.viewer);
					});
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestSnapshotTools.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					inTransaction(() -> {
						for (Person person : List.of(_installer, _viewer)) {
							if (person.tValid()) {
								person.tDelete();
							}
						}
					});
					return null;
				});
				_device = null;
				_before = null;
				_installer = null;
				_viewer = null;
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(
			withData(new TestSuite(TestSnapshotTools.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, AiToolAccessService.Module.INSTANCE,
			TopLogicAgentDefinitions.Module.INSTANCE, AgentApplicationService.Module.INSTANCE,
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.intent;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.intent.IntentFixture.add;
import static test.de.haumacher.ehome.intent.IntentFixture.control;
import static test.de.haumacher.ehome.intent.IntentFixture.create;
import static test.de.haumacher.ehome.intent.IntentFixture.device;
import static test.de.haumacher.ehome.intent.IntentFixture.function;
import static test.de.haumacher.ehome.intent.IntentFixture.inTransaction;
import static test.de.haumacher.ehome.intent.IntentFixture.named;
import static test.de.haumacher.ehome.intent.IntentFixture.operation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.intent.Controls;
import de.haumacher.ehome.intent.Derivation;
import de.haumacher.ehome.intent.I18NConstants;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.projectfile.ProjectFileExport;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.ObjectTypes;
import de.haumacher.ehome.template.PresetDrafts;
import de.haumacher.ehome.template.Presets;

/**
 * Configuring an installation by intent (haumacher/ehome #147): functions with an actuator preset,
 * their controls and the derivation of the group addresses, connections and parameter values, on
 * planned devices of the programs of the KNX test system and of <code>samples/EHomeDemo.knxproj</code>
 * (the same programs: the MDT AKK-04 1.3.5 and the Gira Tastsensor 3 1.3.50).
 *
 * <ul>
 * <li>From the ground up: a new project without ETS data, an AKK-04 and a Tastsensor planned, the
 * function proposal's unused function of channel A named "Licht" with the actuator preset
 * "Schalten", the controls "Taste 1, toggle" and "Status-LED 1, show status": the preview lists the
 * two ranges, the two addresses (1/0/0 and 1/0/1 with their identifiers and datapoint types), the
 * five connections, the presets and the values; after applying, the download image of each device
 * holds the addresses and the comparison with the defaults the values. A second derivation is
 * empty; the function proposal adds nothing.</li>
 * <li>A second key adds only its connections, values and preset; removing it removes them, not the
 * addresses. A derived address renamed by hand and a connection removed by hand are reported and not
 * overwritten. Deleting the function removes what it derived, its renamed address excepted.</li>
 * <li>The refusals with their reasons, the other controls derived: dim on a switching function, show
 * status on a function without a status object, a preset that cannot do the operation, a preset
 * conflict on the device naming the other control, no free address.</li>
 * <li>The examples of the issue: Taste 3 "on" sends on the command address of channel B in
 * staircase mode (object 13), Wippe 3 "on / off" on the command address of channel C in switch
 * impulse mode, Taste 2 "dim" refused.</li>
 * </ul>
 *
 * <p>
 * The presets are saved in the test (as haumacher/ehome #146's tests do) and deleted afterwards with
 * the templates the test created.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDerivation extends BasicTestCase {

	private IntentFixture _f;

	public void testDerivation() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		try {
			List<TLObject> imported = SharedData.newProjects(before);
			_f = IntentFixture.create(device(imported, 1, 3, 5), device(imported, 1, 3, 50));

			checkFromTheGroundUp();
			checkRefusals();
			checkExamples();
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
			_f.cleanUp();
		}
	}

	// ---------------------------------------------------------------------------------------------

	private void checkFromTheGroundUp() {
		TLObject project = _f.project("Intent");
		TLObject[] devices = _f.planDevices(project, "Aktor", "Taster");
		TLObject actuator = devices[0];
		TLObject keys = devices[1];
		inTransaction(() -> FunctionProposal.apply(project));
		TLObject licht = function(project, actuator, _f.channelA);
		inTransaction(() -> {
			licht.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Licht").add(Locale.ENGLISH, "Licht").build());
			licht.tUpdateByName("preset", _f.schalten);
		});
		assertEquals(List.of(_f.schalten), Controls.functionPresets(licht));
		assertTrue(Controls.sourcePresets(keys, _f.key1).contains(_f.toggle));
		TLObject taste1 = control(licht, keys, _f.key1, _f.toggle, "toggle");
		TLObject led1 = control(licht, keys, _f.led1, _f.status, "showStatus");

		// The preview.
		Derivation plan = Derivation.plan(project);
		assertEquals(fingerprint(plan), List.of(), plan.getRefusals());
		Interface switching = Presets.objects(actuator, _f.channelA, _f.schalten).get("command");
		Interface state = Presets.objects(actuator, _f.channelA, _f.schalten).get("state");
		Interface keyObject = Presets.objects(keys, _f.key1, _f.toggle).get("sendCommand");
		Interface ledObject = Presets.objects(keys, _f.led1, _f.status).get("receiveStatus");
		assertEquals(0, switching.getPort().getIndex());
		assertEquals(7, state.getPort().getIndex());
		// A communication object is named as the app lists the objects of a device: number, text and function
		// text (all of channel A's objects are named "Channel A").
		assertEquals("0 Channel A: Switch On/Off", ChannelShape.objectLabel(actuator, switching));
		assertEquals("7 Channel A: State", ChannelShape.objectLabel(actuator, state));
		List<String> presetObjects = new ArrayList<>();
		for (Object object : (Collection<?>) _f.schalten.tValueByName("objects")) {
			presetObjects.add((String) ((TLObject) object).tValueByName("label"));
		}
		assertEquals(List.of("0 Channel A: Switch On/Off", "7 Channel A: State"), presetObjects);
		// So does the dialog saving a preset, in the device it saves from.
		Map<String, Object> request = PresetDrafts.request(actuator, _f.channelA);
		List<String> draftObjects = new ArrayList<>();
		for (Object object : (Collection<?>) request.get("objects")) {
			draftObjects.add(PresetDrafts.objectLabel((TLObject) object));
		}
		assertTrue(draftObjects.toString(), draftObjects.containsAll(presetObjects));
		String command = Resources.getInstance().getString(I18NConstants.ADDRESS_NAME_COMMAND__FUNCTION.fill("Licht"));
		String status = Resources.getInstance().getString(I18NConstants.ADDRESS_NAME_STATE__FUNCTION.fill("Licht"));
		List<Derivation.Item> ranges = items(plan, Derivation.Action.CREATE, Derivation.What.RANGE);
		assertEquals(fingerprint(plan), 2, ranges.size());
		assertTrue(ranges.get(0).object(), ranges.get(0).object().startsWith("1 "));
		assertTrue(ranges.get(1).object(), ranges.get(1).object().startsWith("1/0 "));
		List<Derivation.Item> addresses = items(plan, Derivation.Action.CREATE, Derivation.What.ADDRESS);
		assertEquals(fingerprint(plan), 2, addresses.size());
		assertEquals(command + " (licht)", addresses.get(0).object());
		assertEquals(status + " (licht.state)", addresses.get(1).object());
		assertEquals(detail("1/0/0", "licht", ObjectTypes.effective(actuator, switching)), addresses.get(0).detail());
		assertEquals(detail("1/0/1", "licht.state", ObjectTypes.effective(actuator, state)), addresses.get(1).detail());
		List<Derivation.Item> connections = items(plan, Derivation.Action.CREATE, Derivation.What.CONNECTION);
		assertEquals(fingerprint(plan), List.of(
			connection(actuator, switching, false, command + " (licht)"),
			connection(actuator, state, true, status + " (licht.state)"),
			connection(keys, keyObject, true, command + " (licht)"),
			connection(keys, keyObject, false, status + " (licht.state)"),
			connection(keys, ledObject, false, status + " (licht.state)")), lines(connections));
		assertEquals(3, items(plan, Derivation.Action.CREATE, Derivation.What.PRESET).size());
		List<Derivation.Item> values = items(plan, Derivation.Action.CHANGE, Derivation.What.VALUE);
		assertFalse(values.isEmpty());
		// The values of the presets that differ from the planned devices' defaults: of the source's operation
		// concept, of the LED, and those "Schalten" holds of the configured actuator it was saved from.
		assertTrue(fingerprint(plan), values.stream().anyMatch(v -> v.object().startsWith("Taster ")));
		assertTrue(fingerprint(plan), values.stream().anyMatch(v -> v.object().startsWith("Aktor ")));
		assertEquals(2, items(plan, Derivation.Action.CHANGE, Derivation.What.FUNCTION).size());
		assertEquals(List.of(), items(plan, Derivation.Action.KEEP, null));
		assertEquals(List.of(), items(plan, Derivation.Action.REMOVE, null));
		ParameterView concept = concept(keys);
		assertEquals("0", encoded(keys, concept));

		// Applying.
		inTransaction(() -> Derivation.apply(project, plan.fingerprint()));
		TLObject commandAddress = (TLObject) licht.tValueByName("command");
		TLObject stateAddress = (TLObject) licht.tValueByName("state");
		assertNotNull(commandAddress);
		assertNotNull(stateAddress);
		assertEquals(1 << 11, ((GA) commandAddress.tValueByName("address")).getValue());
		assertEquals((1 << 11) + 1, ((GA) stateAddress.tValueByName("address")).getValue());
		assertEquals(command, commandAddress.tValueByName("name"));
		assertEquals("licht.state", stateAddress.tValueByName("identifier"));
		assertEquals(List.of(ObjectTypes.effective(actuator, switching)), commandAddress.tValueByName("datapointTypes"));
		assertEquals("1", encoded(keys, concept));
		assertEquals(_f.toggle, Presets.use(keys, _f.key1).tValueByName("preset"));
		assertConnected(actuator, switching, commandAddress, "receive");
		assertConnected(actuator, state, stateAddress, "send");
		assertConnected(keys, keyObject, commandAddress, "send");
		assertConnected(keys, keyObject, stateAddress, "receive");
		assertConnected(keys, ledObject, stateAddress, "receive");
		assertNull(taste1.tValueByName("problem"));
		assertNull(led1.tValueByName("problem"));

		// The comparison for programming: the download image of each device holds both addresses, the
		// comparison with the defaults the values of the presets.
		for (TLObject device : List.of(actuator, keys)) {
			DownloadImage image = DownloadImage.compute(device);
			assertEquals(image.dump(), List.of(), image.getProblems());
			String table = HexFormat.of().formatHex(image.getAddressTable().data());
			assertTrue(table, table.contains("08000801"));
			DownloadPlan download = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertEquals(download.dump(), List.of(), download.getProblems());
		}
		DeviceChanges changes = DeviceChanges.of(keys, DeviceChanges.Reference.DEFAULT);
		assertNotNull(changes.getEntry(concept));

		// A second derivation without a change: nothing.
		Derivation again = Derivation.plan(project);
		assertEquals(fingerprint(again), List.of(), again.getItems());
		assertEquals(List.of(), again.getRefusals());
		assertTrue(again.isEmpty());

		// The function proposal adds nothing and changes nothing of the derived function.
		FunctionProposal.Run[] run = { null };
		inTransaction(() -> run[0] = FunctionProposal.run(project));
		assertEquals(List.of(), run[0].added());
		assertFalse(run[0].updated().contains(licht));
		assertTrue(Derivation.plan(project).isEmpty());

		// A second key: only its connections, values and preset.
		TLObject taste3 = control(licht, keys, _f.key3, _f.toggle3, "toggle");
		Derivation second = Derivation.plan(project);
		assertEquals(fingerprint(second), List.of(), items(second, null, Derivation.What.ADDRESS));
		assertEquals(fingerprint(second), List.of(), items(second, null, Derivation.What.RANGE));
		assertEquals(fingerprint(second), List.of(), items(second, null, Derivation.What.FUNCTION));
		Interface key3Object = Presets.objects(keys, _f.key3, _f.toggle3).get("sendCommand");
		assertEquals(List.of(connection(keys, key3Object, true, command + " (licht)"),
			connection(keys, key3Object, false, status + " (licht.state)")),
			lines(items(second, Derivation.Action.CREATE, Derivation.What.CONNECTION)));
		assertEquals(1, items(second, Derivation.Action.CREATE, Derivation.What.PRESET).size());
		assertTrue(fingerprint(second), second.getItems().stream().allMatch(i -> i.origin() == taste3));
		inTransaction(() -> Derivation.apply(project, second.fingerprint()));
		assertConnected(keys, key3Object, commandAddress, "send");

		// Removing it: its connections, preset and values go, the addresses stay.
		inTransaction(taste3::tDelete);
		Derivation removal = Derivation.plan(project);
		assertEquals(fingerprint(removal), 2, items(removal, Derivation.Action.REMOVE, Derivation.What.CONNECTION).size());
		assertEquals(1, items(removal, Derivation.Action.REMOVE, Derivation.What.PRESET).size());
		assertEquals(List.of(), items(removal, null, Derivation.What.ADDRESS));
		assertEquals(List.of(), items(removal, Derivation.Action.CREATE, null));
		inTransaction(() -> Derivation.apply(project, removal.fingerprint()));
		assertNull(actual(keys, key3Object, commandAddress, "send"));
		assertNull(Presets.use(keys, _f.key3));
		assertTrue(commandAddress.tValid());
		assertTrue(Derivation.plan(project).isEmpty());

		// A renamed function renames its addresses (the identifiers stay, rules know them).
		named(licht, "Deckenlicht", null);
		Derivation renamed = Derivation.plan(project);
		assertEquals(fingerprint(renamed), 2, items(renamed, Derivation.Action.CHANGE, Derivation.What.ADDRESS).size());
		assertEquals(fingerprint(renamed), 2, renamed.getItems().size());
		inTransaction(() -> Derivation.apply(project, renamed.fingerprint()));
		assertEquals(text(I18NConstants.ADDRESS_NAME_STATE__FUNCTION.fill("Deckenlicht")), stateAddress.tValueByName("name"));
		assertEquals("licht.state", stateAddress.tValueByName("identifier"));
		named(licht, "Licht", null);
		inTransaction(() -> Derivation.apply(project, null));
		assertEquals(command, commandAddress.tValueByName("name"));
		assertTrue(Derivation.plan(project).isEmpty());

		// Changed by hand: reported, not overwritten.
		inTransaction(() -> {
			commandAddress.tUpdateByName("name", "Mein Licht");
			DeviceSettings.disconnect(actual(keys, ledObject, stateAddress, "receive"));
		});
		Derivation hand = Derivation.plan(project);
		List<Derivation.Item> kept = items(hand, Derivation.Action.KEEP, null);
		assertEquals(fingerprint(hand), 2, kept.size());
		assertEquals(Derivation.What.ADDRESS, kept.get(0).what());
		assertEquals(text(I18NConstants.KEEP_CHANGED__DERIVED.fill(command + " (licht)")), kept.get(0).detail());
		assertEquals(Derivation.What.CONNECTION, kept.get(1).what());
		assertEquals(text(I18NConstants.KEEP_CONNECTION_REMOVED), kept.get(1).detail());
		assertEquals(fingerprint(hand), kept, hand.getItems());
		inTransaction(() -> Derivation.apply(project, hand.fingerprint()));
		assertEquals("Mein Licht", commandAddress.tValueByName("name"));
		assertNull(actual(keys, ledObject, stateAddress, "receive"));

		// Deleting the function removes what it derived, the address renamed by hand excepted.
		inTransaction(licht::tDelete);
		Derivation deleted = Derivation.plan(project);
		List<Derivation.Item> removed = items(deleted, Derivation.Action.REMOVE, Derivation.What.ADDRESS);
		assertEquals(fingerprint(deleted), 1, removed.size());
		assertEquals(1, items(deleted, Derivation.Action.KEEP, Derivation.What.ADDRESS).size());
		inTransaction(() -> Derivation.apply(project, deleted.fingerprint()));
		assertFalse(stateAddress.tValid());
		assertTrue(commandAddress.tValid());
		assertTrue(GroupAddressConnections.connections(commandAddress).isEmpty());
		assertEquals("0", encoded(keys, concept));
		assertEquals(List.of(), Presets.uses(keys));
		assertEquals(List.of(), ExtIds.list(project, "derivedParts"));
		assertTrue(Derivation.plan(project).isEmpty());

		// A function whose role is bound to an address already (imported, or a project file imported
		// without the marks of the derivation) takes it: only the state address is new.
		inTransaction(() -> FunctionProposal.apply(project));
		TLObject again2 = named(function(project, actuator, _f.channelA), "Licht", _f.schalten);
		inTransaction(() -> again2.tUpdateByName("command", commandAddress));
		Derivation adopted = Derivation.plan(project);
		List<Derivation.Item> created = items(adopted, Derivation.Action.CREATE, Derivation.What.ADDRESS);
		assertEquals(fingerprint(adopted), 1, created.size());
		assertEquals(status + " (licht.state)", created.get(0).object());
		inTransaction(() -> Derivation.apply(project, adopted.fingerprint()));
		assertSame(commandAddress, again2.tValueByName("command"));
		assertConnected(actuator, switching, commandAddress, "receive");
		assertTrue(Derivation.plan(project).isEmpty());
		// The project file leaves the presets and the marks out (master data and bookkeeping of this
		// installation).
		String document = de.haumacher.ehome.runtime.Json.write(ProjectFileExport.document(project, Instant.EPOCH));
		assertFalse(document, document.contains("derivedParts"));
	}

	private void checkRefusals() {
		TLObject project = _f.project("Refusals");
		TLObject[] devices = _f.planDevices(project, "Aktor", "Taster");
		TLObject actuator = devices[0];
		TLObject keys = devices[1];
		inTransaction(() -> FunctionProposal.apply(project));
		TLObject light = named(function(project, actuator, _f.channelA), "Licht", _f.schalten);
		TLObject impulse = named(function(project, actuator, _f.channelC), "Impuls", _f.schaltimpuls);
		TLObject full = named(function(project, actuator, _f.channelD), "Voll", _f.schaltenD);
		TLObject toggle = control(light, keys, _f.key1, _f.toggle, "toggle");
		TLObject dim = control(light, keys, _f.key2, _f.toggle, "dim");
		TLObject cannot = control(light, keys, _f.key3, _f.toggle3, "on");
		TLObject noStatus = control(impulse, keys, _f.led1, _f.status, "showStatus");
		TLObject conflict = control(impulse, keys, _f.rocker1, _f.rockerToggle, "toggle");
		TLObject rocker = control(impulse, keys, _f.rocker3, _f.rockerOnOff, "onOff");

		// A switch function in a room of the second floor, whose middle group 2/1 has no free address: the
		// project's range 2/1 holds 2/1/0 only, which is taken. "Impuls" (no room) gets 2/0, a middle range
		// of its own in the main range.
		int middleStart = 2 << 11 | 1 << 8;
		inTransaction(() -> {
			TLObject building = create("ehome.building:Building");
			building.tUpdateByName("name", "Haus");
			add(project, "buildings", building);
			TLObject ground = create("ehome.building:Floor");
			ground.tUpdateByName("name", "EG");
			add(building, "parts", ground);
			TLObject upper = create("ehome.building:Floor");
			upper.tUpdateByName("name", "OG");
			add(building, "parts", upper);
			TLObject bath = create("ehome.building:Room");
			bath.tUpdateByName("name", "Bad");
			add(upper, "parts", bath);
			full.tUpdateByName("room", bath);
			TLObject main = range(project, "Schalten", 2 << 11, (3 << 11) - 1);
			TLObject middle = range(main, "OG", middleStart, middleStart);
			TLObject taken = create("ehome.logical:GroupAddress");
			taken.tUpdateByName("address", new GA(middleStart, null));
			taken.tUpdateByName("name", "Belegt");
			add(middle, "parts", taken);
			assertEquals(1, Derivation.floorIndex(bath));
		});
		TLObject voll = control(full, keys, _f.key2, _f.toggle, "toggle");

		Derivation plan = Derivation.plan(project);
		Map<TLObject, ResKey> refusals = new LinkedHashMap<>();
		for (Derivation.Refusal refusal : plan.getRefusals()) {
			refusals.put(refusal.origin(), refusal.reason());
		}
		String lightLabel = "Licht";
		assertEquals(fingerprint(plan), Set.of(dim, cannot, noStatus, conflict, full, voll), refusals.keySet());
		assertEquals(I18NConstants.ERROR_CONTROL_KIND_NO_ROLE__FUNCTION_OPERATION_KIND.fill(lightLabel,
			operation("dim"), _f.schalten.tValueByName("kind")), refusals.get(dim));
		assertEquals(I18NConstants.ERROR_PRESET_CANNOT__PRESET_OPERATION.fill("Taste 3: Umschalten", operation("on")),
			refusals.get(cannot));
		assertEquals(I18NConstants.ERROR_CONTROL_NO_ROLE__FUNCTION_OPERATION_ROLE.fill("Impuls", operation("showStatus"),
			TLModelUtil.findPart("ehome.template:PresetRole#state")), refusals.get(noStatus));
		String conflictText = text(refusals.get(conflict));
		String toggleLabel = controlLabel(plan, toggle);
		assertTrue(conflictText, conflictText.startsWith(text(I18NConstants.ERROR_CONFLICT__OTHER_REASON.fill(toggleLabel, ""))));
		assertTrue(conflictText, conflictText.contains("Wippe: Umschalten") && conflictText.contains("Taste: Umschalten"));
		String noFree = text(I18NConstants.ERROR_NO_FREE_ADDRESS__RANGE.fill("2/1"));
		assertEquals(noFree, text(refusals.get(full)));
		assertEquals(text(I18NConstants.ERROR_CONTROL_FUNCTION__FUNCTION_REASON.fill("Voll", I18NConstants.ERROR_NO_FREE_ADDRESS__RANGE.fill("2/1"))),
			text(refusals.get(voll)));
		// At the control.
		assertEquals(text(refusals.get(dim)), dim.tValueByName("problem"));
		assertEquals(noFree, full.tValueByName("derivationProblem"));

		// The others are derived: the toggle of Licht, the rocker of Impuls.
		Interface rockerObject = Presets.objects(keys, _f.rocker3, _f.rockerOnOff).get("sendCommand");
		assertTrue(fingerprint(plan), plan.getItems().stream().anyMatch(i -> i.origin() == toggle));
		assertTrue(fingerprint(plan), plan.getItems().stream().anyMatch(i -> i.origin() == rocker
			&& i.what() == Derivation.What.CONNECTION && i.object().equals(objectText(keys, rockerObject))));
		assertTrue(plan.getItems().stream().noneMatch(i -> refusals.containsKey(i.origin())));
		inTransaction(() -> Derivation.apply(project, plan.fingerprint()));
		assertNotNull(light.tValueByName("command"));
		assertNotNull(impulse.tValueByName("command"));
		assertNull(full.tValueByName("command"));
		assertConnected(keys, rockerObject, (TLObject) impulse.tValueByName("command"), "send");
		assertEquals(2 << 11, ((GA) ((TLObject) impulse.tValueByName("command")).tValueByName("address")).getValue());
	}

	private void checkExamples() {
		TLObject project = _f.project("Demo");
		TLObject[] devices = _f.planDevices(project, "AKK-04", "Tastsensor");
		TLObject actuator = devices[0];
		TLObject keys = devices[1];
		inTransaction(() -> FunctionProposal.apply(project));
		TLObject socket1 = named(function(project, actuator, _f.channelA), "Steckdose 1", _f.schalten);
		TLObject socket2 = named(function(project, actuator, _f.channelB), "Steckdose 2", null);
		TLObject socket3 = named(function(project, actuator, _f.channelC), "Steckdose 3", null);
		// The presets of channels B and C set the mode the channel is in (staircase light, switch impulse).
		assertTrue(Controls.functionPresets(socket2).contains(_f.treppenlicht));
		assertTrue(Controls.functionPresets(socket3).contains(_f.schaltimpuls));
		inTransaction(() -> {
			socket2.tUpdateByName("preset", _f.treppenlicht);
			socket3.tUpdateByName("preset", _f.schaltimpuls);
		});
		control(socket1, keys, _f.key1, _f.toggle, "toggle");
		control(socket1, keys, _f.led1, _f.status, "showStatus");
		control(socket2, keys, _f.key3, _f.on3, "on");
		control(socket3, keys, _f.rocker3, _f.rockerOnOff, "onOff");
		TLObject dim = control(socket1, keys, _f.key2, _f.toggle, "dim");

		Derivation plan = Derivation.plan(project);
		assertEquals(fingerprint(plan), List.of(dim), plan.getRefusals().stream().map(Derivation.Refusal::origin).toList());
		inTransaction(() -> Derivation.apply(project, plan.fingerprint()));

		// Taste 1 toggles Steckdose 1 and listens to its status; the LED receives the status.
		TLObject command1 = (TLObject) socket1.tValueByName("command");
		TLObject state1 = (TLObject) socket1.tValueByName("state");
		Interface key1 = Presets.objects(keys, _f.key1, _f.toggle).get("sendCommand");
		assertEquals(0, key1.getPort().getIndex());
		assertConnected(keys, key1, command1, "send");
		assertConnected(keys, key1, state1, "receive");
		assertConnected(actuator, object(actuator, 0), command1, "receive");
		assertConnected(actuator, object(actuator, 7), state1, "send");
		assertConnected(keys, Presets.objects(keys, _f.led1, _f.status).get("receiveStatus"), state1, "receive");

		// Taste 3 switches Steckdose 2 (staircase light) on: it sends on the command address of object 13.
		TLObject command2 = (TLObject) socket2.tValueByName("command");
		assertConnected(actuator, object(actuator, 13), command2, "receive");
		assertConnected(keys, Presets.objects(keys, _f.key3, _f.on3).get("sendCommand"), command2, "send");

		// Wippe 3 switches Steckdose 3 (switch impulse) on and off on its command address.
		TLObject command3 = (TLObject) socket3.tValueByName("command");
		assertConnected(keys, Presets.objects(keys, _f.rocker3, _f.rockerOnOff).get("sendCommand"), command3, "send");
		assertConnected(actuator, Presets.objects(actuator, _f.channelC, _f.schaltimpuls).get("command"), command3,
			"receive");
		assertTrue(Derivation.plan(project).isEmpty());
	}

	// ---------------------------------------------------------------------------------------------
	// Presets.







	// ---------------------------------------------------------------------------------------------
	// Helpers.







	private static TLObject range(TLObject parent, String name, int start, int end) {
		TLObject range = create("ehome.logical:GroupRange");
		range.tUpdateByName("name", name);
		range.tUpdateByName("rangeStart", new GA(start, null));
		range.tUpdateByName("rangeEnd", new GA(end, null));
		add(parent, ExtIds.isA(parent, "ehome.project:Project") ? "groupAddresses" : "parts", range);
		return range;
	}









	private static List<Derivation.Item> items(Derivation plan, Derivation.Action action, Derivation.What what) {
		return plan.getItems().stream()
			.filter(i -> (action == null || i.action() == action) && (what == null || i.what() == what)).toList();
	}

	private static List<String> lines(List<Derivation.Item> items) {
		return items.stream().map(i -> i.object() + " | " + i.detail()).toList();
	}

	private static String fingerprint(Derivation plan) {
		return String.join("\n", plan.fingerprint());
	}

	private static String detail(String address, String identifier, TLObject type) {
		return text(de.haumacher.ehome.intent.I18NConstants.DETAIL_ADDRESS__ADDRESS_IDENTIFIER_TYPE.fill(address,
			identifier, com.top_logic.layout.provider.MetaLabelProvider.INSTANCE.getLabel(type)));
	}

	private static String connection(TLObject device, Interface placed, boolean sends, String address) {
		return objectText(device, placed) + " | "
			+ text(sends ? I18NConstants.DETAIL_SENDS__ADDRESS.fill(address) : I18NConstants.DETAIL_RECEIVES__ADDRESS.fill(address));
	}

	private static String objectText(TLObject device, Interface placed) {
		return text(I18NConstants.OBJECT_IN_DEVICE__DEVICE_OBJECT.fill(GroupAddressConnections.deviceLabel(device),
			ChannelShape.objectLabel(device, placed)));
	}

	private static String controlLabel(Derivation plan, TLObject control) {
		for (Derivation.Item item : plan.getItems()) {
			if (item.origin() == control) {
				return item.originLabel();
			}
		}
		fail("No item of the control.");
		return null;
	}

	private static void assertConnected(TLObject device, Interface placed, TLObject address, String kind) {
		assertNotNull(placed + " " + kind + " " + address, actual(device, placed, address, kind));
	}

	private static TLObject actual(TLObject device, Interface placed, TLObject address, String kind) {
		for (TLObject connection : DeviceSettings.connections(device, placed)) {
			if (connection.tValueByName("target") == address
				&& kind.equals(((TLClassifier) connection.tValueByName("kind")).getName())) {
				return connection;
			}
		}
		return null;
	}

	private static Interface object(TLObject device, int number) {
		for (Interface placed : DeviceSettings.interfaces(device)) {
			if (placed.getPort().getIndex() == number) {
				return placed;
			}
		}
		fail("No object " + number);
		return null;
	}





	private ParameterView concept(TLObject device) {
		for (ParameterView view : ChannelShape.of(device).get(_f.key1).getExternalInputs()) {
			if ("Funktion_T1T2".equals(view.getParameter().getName())) {
				return view;
			}
		}
		fail("No operation concept.");
		return null;
	}

	private static String encoded(TLObject device, ParameterView view) {
		return ParameterValues.encode(DeviceConfiguration.valueHolder(device, view));
	}







	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}









	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDerivation.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE));
	}

}

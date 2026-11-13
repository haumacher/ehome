/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.intent;

import static junit.framework.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.intent.Controls;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.ObjectTypes;
import de.haumacher.ehome.template.Presets;

/**
 * The presets and projects of the tests of the configuration by intent (haumacher/ehome #147), on the
 * programs of the KNX test system (<code>samples/KNX-Testsystem.knxproj</code>; the same as of
 * <code>samples/EHomeDemo.knxproj</code>): the MDT AKK-04 1.3.5 and the Gira Tastsensor 3 1.3.50.
 *
 * <p>
 * {@link #create(TLObject, TLObject)} saves the presets from the imported devices of the test system
 * and from planned devices configured for them; {@link #cleanUp()} deletes them, the templates
 * created and the projects of {@link #project(String)}. The presets:
 * </p>
 * <ul>
 * <li>actuator: "Schalten" (channel A, light: command object 0, state object 7), "Treppenlicht"
 * (channel B in staircase mode, switch), "Schaltimpuls" (channel C in switch impulse mode, switch),
 * "Schalten D" (channel D switching, switch);</li>
 * <li>source: "Taste: Umschalten" (key 1, toggle), "Taste 3: Umschalten" (key 3, toggle), "LED:
 * Status" (status LED 1 with its own object, show status), "Taste: Ein" (key 3 switching on, on),
 * "Wippe: Ein/Aus" (rocker 3, on / off), "Wippe: Umschalten" (rocker 1, toggle; it wants the rocker
 * concept of keys 1 and 2).</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public final class IntentFixture {

	private final List<TLObject> _presets = new ArrayList<>();

	private final List<TLObject> _templates = new ArrayList<>();

	private final List<TLObject> _projects = new ArrayList<>();

	public TLObject actuatorType;

	public TLObject actuatorProgram;

	public TLObject keysType;

	public TLObject keysProgram;

	public TLObject schalten;

	public TLObject treppenlicht;

	public TLObject schaltimpuls;

	public TLObject toggle;

	public TLObject toggle3;

	public TLObject on3;

	public TLObject rockerOnOff;

	public TLObject rockerToggle;

	public TLObject status;

	/** A switching preset of channel D, which is not declared alike to channel A. */
	public TLObject schaltenD;

	public String channelA;

	public String channelB;

	public String channelC;

	public String channelD;

	public String key1;

	public String key2;

	public String key3;

	public String led1;

	public String rocker1;

	public String rocker3;

	private IntentFixture() {
		// Created by create().
	}

	/**
	 * Saves the presets from the configured devices of the imported test system (and from planned
	 * copies configured for them).
	 */
	public static IntentFixture create(TLObject actuator, TLObject keys) {
		IntentFixture result = new IntentFixture();
		result.actuatorType = (TLObject) actuator.tValueByName("type");
		result.actuatorProgram = (TLObject) actuator.tValueByName("program");
		result.keysType = (TLObject) keys.tValueByName("type");
		result.keysProgram = (TLObject) keys.tValueByName("program");
		result.channelA = key(actuator, "Channel A", "Kanal A");
		result.channelB = key(actuator, "Channel B", "Kanal B");
		result.channelC = key(actuator, "Channel C", "Kanal C");
		result.channelD = key(actuator, "Channel D", "Kanal D");
		result.key1 = key(keys, "Taste 1", "Key 1");
		result.key2 = key(keys, "Taste 2", "Key 2");
		result.key3 = key(keys, "Taste 3", "Key 3");
		result.led1 = key(keys, "Status-LED 1", "Status LED 1");
		result.rocker1 = key(keys, "Wippe 1", "Rocker 1");
		result.rocker3 = key(keys, "Wippe 3", "Rocker 3");
		result.savePresets(actuator, keys);
		return result;
	}

	/**
	 * Plans an AKK-04 (1.1.1) and a Tastsensor 3 (1.1.2) in the line of the project.
	 *
	 * @return The actuator and the push-button.
	 */
	public TLObject[] planDevices(TLObject project, String actuatorName, String keysName) {
		TLObject line = line(project);
		TLObject[] devices = new TLObject[2];
		inTransaction(() -> {
			devices[0] = DevicePlanning.create(line, actuatorType, assignment(actuatorType, actuatorProgram), 1,
				actuatorName, null);
			devices[1] = DevicePlanning.create(line, keysType, assignment(keysType, keysProgram), 2, keysName, null);
		});
		return devices;
	}

	private void savePresets(TLObject actuator, TLObject keys) {
		TLObject planned = plan(actuator);
		inTransaction(() -> {
			DeviceSettings.setValue(planned, mode(planned, channelB), Long.valueOf(2));
			DeviceSettings.setValue(planned, mode(planned, channelC), Long.valueOf(3));
		});
		schalten = save(actuator, channelA, "Schalten", "actuator", "light", List.of(),
			Map.of("command", 0, "state", 7));
		treppenlicht = save(planned, channelB, "Treppenlicht", "actuator", "switch", List.of(),
			roles(planned, channelB));
		schaltimpuls = save(planned, channelC, "Schaltimpuls", "actuator", "switch", List.of(),
			roles(planned, channelC));
		schaltenD = save(planned, channelD, "Schalten D", "actuator", "switch", List.of(),
			roles(planned, channelD));
		toggle = save(keys, key1, "Taste: Umschalten", "source", null, List.of("toggle"),
			Map.of("sendCommand", Presets.channelObjects(keys, key1).get(0).getPort().getIndex()));
		toggle3 = save(keys, key3, "Taste 3: Umschalten", "source", null, List.of("toggle"),
			Map.of("sendCommand", Presets.channelObjects(keys, key3).get(0).getPort().getIndex()));
		status = save(keys, led1, "LED: Status", "source", null, List.of("showStatus"),
			Map.of("receiveStatus", Presets.channelObjects(keys, led1).get(0).getPort().getIndex()));
		TLObject plannedKeys = plan(keys);
		rockerToggle = save(plannedKeys, rocker1, "Wippe: Umschalten", "source", null, List.of("toggle"),
			Map.of("sendCommand", Presets.channelObjects(plannedKeys, rocker1).get(0).getPort().getIndex()));
		rockerOnOff = save(plannedKeys, rocker3, "Wippe: Ein/Aus", "source", null, List.of("onOff"),
			Map.of("sendCommand", Presets.channelObjects(plannedKeys, rocker3).get(0).getPort().getIndex()));
		// Taste 3 switching on: the operation concept of keys 3 and 4 and "on" when pressed.
		inTransaction(() -> {
			for (ParameterView view : ChannelShape.of(keys).get(key3).getExternalInputs()) {
				if ("Funktion_T3T4".equals(view.getParameter().getName())) {
					DeviceSettings.setValue(plannedKeys, view, Long.valueOf(1));
				}
			}
		});
		inTransaction(() -> {
			for (ParameterView view : ChannelShape.of(plannedKeys).get(key3).getParameters()) {
				if ("Befehl_drücken_T3".equals(view.getParameter().getName())) {
					DeviceSettings.setValue(plannedKeys, view, Long.valueOf(1));
				}
			}
		});
		on3 = save(plannedKeys, key3, "Taste: Ein", "source", null, List.of("on"),
			Map.of("sendCommand", Presets.channelObjects(plannedKeys, key3).get(0).getPort().getIndex()));
	}

	/** The roles of a channel in effect: its switching object the command, its status object the state. */
	private static Map<String, Integer> roles(TLObject device, String channel) {
		Map<String, Integer> result = new LinkedHashMap<>();
		for (Interface placed : Presets.channelObjects(device, channel)) {
			TLObject type = ObjectTypes.effective(device, placed);
			String name = type == null ? "" : String.valueOf(type.tValueByName("name"));
			if ("DPT_Switch".equals(name)) {
				result.putIfAbsent("command", placed.getPort().getIndex());
			} else if ("DPT_State".equals(name)) {
				result.putIfAbsent("state", placed.getPort().getIndex());
			}
		}
		return result;
	}

	private TLObject save(TLObject device, String key, String name, String purpose, String kind, List<String> operations,
			Map<String, Integer> roleNumbers) {
		TLObject existing = DeviceTemplates.template(DeviceConfiguration.program(device));
		List<ParameterView> values = new ArrayList<>();
		for (Presets.Candidate candidate : Presets.candidates(device, key)) {
			if (candidate.proposed()) {
				values.add(candidate.view());
			}
		}
		Map<Interface, TLObject> roles = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : roleNumbers.entrySet()) {
			roles.put(object(Presets.channelObjects(device, key), entry.getValue()),
				(TLObject) TLModelUtil.findPart("ehome.template:PresetRole#" + entry.getKey()));
		}
		List<TLObject> ops = new ArrayList<>();
		for (String operation : operations) {
			ops.add(operation(operation));
		}
		TLObject[] result = { null };
		inTransaction(() -> result[0] = Presets.save(device, key, name,
			(TLObject) TLModelUtil.findPart("ehome.template:PresetPurpose#" + purpose),
			kind == null ? null : (TLObject) TLModelUtil.findPart("ehome.runtime:FunctionKind#" + kind), ops, values,
			roles));
		_presets.add(result[0]);
		if (existing == null) {
			_templates.add(result[0].tContainer());
		}
		return result[0];
	}

	public TLObject project(String name) {
		TLObject[] result = { null };
		inTransaction(() -> {
			result[0] = create("ehome.project:Project");
			result[0].tUpdateByName("name", name);
			result[0].tUpdateByName("groupAddressStyle", TLModelUtil.findPart("ehome.logical:GroupAddressStyle#ThreeLevel"));
			TLObject area = create("ehome.physical:Area");
			area.tUpdateByName("address", 1);
			add(result[0], "areas", area);
			TLObject line = create("ehome.physical:Line");
			line.tUpdateByName("address", 1);
			add(area, "lines", line);
		});
		_projects.add(result[0]);
		return result[0];
	}

	public static TLObject line(TLObject project) {
		return ExtIds.members(project, ExtIds.Kind.LINE).iterator().next();
	}

	public static TLObject assignment(TLObject type, TLObject program) {
		for (TLObject assignment : DevicePlanning.programs(type)) {
			if (assignment == program || assignment.tValueByName("program") == program) {
				return assignment;
			}
		}
		return program;
	}

	public static TLObject function(TLObject project, TLObject device, String channel) {
		for (TLObject function : FunctionProposal.functions(project)) {
			if (function.tValueByName("device") == device && channel.equals(function.tValueByName("channel"))) {
				return function;
			}
		}
		fail("No function of " + channel);
		return null;
	}

	public static TLObject named(TLObject function, String name, TLObject preset) {
		inTransaction(() -> {
			function.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, name).add(Locale.ENGLISH, name).build());
			if (preset != null) {
				function.tUpdateByName("preset", preset);
			}
		});
		return function;
	}

	public static TLObject control(TLObject function, TLObject device, String channel, TLObject preset,
			String operation) {
		TLObject[] result = { null };
		inTransaction(() -> result[0] = Controls.create(function, device, channel, preset, operation(operation)));
		return result[0];
	}

	public static TLObject operation(String name) {
		return (TLObject) TLModelUtil.findPart("ehome.template:SourceOperation#" + name);
	}

	public static Interface object(List<Interface> objects, int number) {
		for (Interface placed : objects) {
			if (placed.getPort().getIndex() == number) {
				return placed;
			}
		}
		fail("No object " + number + " in " + objects);
		return null;
	}

	public static ParameterView mode(TLObject device, String key) {
		return ChannelShape.of(device).get(key).getExternalInputs().get(0);
	}

	public static String key(TLObject device, String... names) {
		List<String> wanted = List.of(names);
		for (String key : Presets.channels(device)) {
			for (Interface placed : ChannelShape.of(device).get(key).getObjects()) {
				for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
					if (wanted.contains(GroupAddressConnections.objectName(placed, Resources.getInstance(locale)))) {
						return key;
					}
				}
				break;
			}
		}
		fail("No channel " + wanted);
		return null;
	}

	public static TLObject plan(TLObject like) {
		TLObject[] result = { null };
		inTransaction(() -> {
			TLObject line = DevicePlanning.line(like);
			result[0] = DevicePlanning.create(line, (TLObject) like.tValueByName("type"),
				(TLObject) like.tValueByName("program"), DevicePlanning.nextFree(line), "Planned", null);
		});
		return result[0];
	}

	public static TLObject device(List<TLObject> projects, int area, int line, int number) {
		int address = area << 12 | line << 8 | number;
		for (TLObject device : SharedData.within("ehome.physical:Device", projects)) {
			Integer individual = DownloadImage.individualAddress(device);
			if (individual != null && individual.intValue() == address) {
				return device;
			}
		}
		fail("No device " + area + "." + line + "." + number);
		return null;
	}

	public static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	public static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

	public void cleanUp() {
		inTransaction(() -> {
			for (TLObject project : _projects) {
				if (project.tValid()) {
					project.tDelete();
				}
			}
			for (TLObject preset : _presets) {
				if (preset.tValid()) {
					preset.tDelete();
				}
			}
			for (TLObject template : _templates) {
				if (template.tValid()) {
					template.tDelete();
				}
			}
		});
		_projects.clear();
		_presets.clear();
		_templates.clear();
	}

	public static void inTransaction(Runnable modification) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			modification.run();
			tx.commit();
		}
	}

}

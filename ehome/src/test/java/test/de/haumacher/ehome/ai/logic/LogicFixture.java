/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import test.de.haumacher.ehome.ai.AssistantFixture;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.security.AccountFixture;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.ai.core.agent.ToolContext;
import com.top_logic.ai.core.agent.TransactionScope;
import com.top_logic.ai.core.agent.approval.ApprovalCallback;
import com.top_logic.ai.core.security.RunEntryPoint;
import com.top_logic.ai.core.security.RunPrincipal;
import com.top_logic.ai.core.security.RunSecurity;
import com.top_logic.ai.tool.api.CancellationToken;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.building.BuildingDefinition;
import de.haumacher.ehome.model.datapoint.DatapointMainType;
import de.haumacher.ehome.model.datapoint.DatapointType;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;

/**
 * The project the tests of the assistant's logic tools work on, built by hand (no import, so a test
 * class takes about a second) and deleted again: a building with a kitchen, group addresses of the
 * frost protection and the kitchen's heating (with datapoint main types of their own, deleted with
 * the project), the application datapoints the examples of the skill <code>EHome_rules</code> name,
 * and an installer and a viewer.
 */
public class LogicFixture {

	/** The application datapoints of the examples of the skill EHome_rules, with their value types. */
	public static final Map<String, String> SKILL_DATAPOINTS = new LinkedHashMap<>();

	/** Which of {@link #SKILL_DATAPOINTS} the user may command (rules write all of them). */
	public static final List<String> WRITABLE = List.of("hall.light", "stairs.light", "heating.frost",
		"heating.windowOpen", "blind.shade", "blind.windAlarm", "living.light", "living.dimmer", "kitchen.light", "siren",
		"yard.light", "entrance.light");

	static {
		for (String id : List.of("hall.presence", "hall.light", "stairs.button", "stairs.light", "heating.frost",
			"window.open", "heating.windowOpen", "blind.shade", "blind.windAlarm", "living.light", "kitchen.light",
			"scene.evening", "house.allOff", "smoke.alarm", "siren", "outdoor.motion", "door.open", "door.locked",
			"yard.light", "entrance.light")) {
			SKILL_DATAPOINTS.put(id, "boolean");
		}
		for (String id : List.of("hall.lux", "outdoor.temp", "outdoor.wind", "living.dimmer", "outdoor.lux")) {
			SKILL_DATAPOINTS.put(id, "real");
		}
	}

	private final String _prefix;

	private final List<TLObject> _types = new ArrayList<>();

	private final List<Person> _persons = new ArrayList<>();

	private final Map<String, TLObject> _addresses = new LinkedHashMap<>();

	private final Map<String, TLObject> _datapoints = new LinkedHashMap<>();

	private TLObject _project;

	private TLObject _room;

	/**
	 * Creates a {@link LogicFixture}.
	 *
	 * @param prefix
	 *        Prefix of the accounts' names, unique per test class.
	 */
	public LogicFixture(String prefix) {
		_prefix = prefix;
	}

	/**
	 * Creates the accounts and the project.
	 */
	public void setUp() {
		AssistantFixture.inTransaction(() -> {
			_persons.add(person(_prefix + "Installer", "ehome.installers"));
			_persons.add(person(_prefix + "Viewer", null));
			TLObject bool = mainType(1);
			TLObject real = mainType(9);
			TLObject percent = mainType(5);
			Project project = (Project) create("ehome.project:Project");
			_project = project;
			project.setName("Logic test " + _prefix);
			project.setExtId("P-LOGIC-" + _prefix);
			address("Außentemperatur", "1/0/0", real, false);
			address("Frostschutz Heizung", "1/0/1", bool, true);
			address("Licht Küche", "1/1/0", bool, true);
			address("Raumtemperatur Küche", "1/2/0", real, false);
			address("Ventil Küche", "1/2/1", percent, true);
			for (TLObject address : _addresses.values()) {
				project.addGroupAddresse((GroupAddress) address);
			}
			BuildingDefinition building = part("ehome.building:Building", "Haus", "P-LOGIC-" + _prefix + "-B");
			BuildingDefinition room = part("ehome.building:Room", "Küche", "P-LOGIC-" + _prefix + "-K");
			_room = room;
			building.addPart(room);
			project.addBuilding(building);
			for (Map.Entry<String, String> datapoint : SKILL_DATAPOINTS.entrySet()) {
				ApplicationDatapoint created = (ApplicationDatapoint) create("ehome.runtime:ApplicationDatapoint");
				created.setIdentifier(datapoint.getKey());
				created.setType(
					((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(datapoint.getValue()));
				created.setWritable(WRITABLE.contains(datapoint.getKey()));
				_datapoints.put(datapoint.getKey(), created);
			}
			for (TLObject datapoint : _datapoints.values()) {
				project.addApplicationDatapoint((ApplicationDatapoint) datapoint);
			}
			// Access per project (haumacher/ehome #152): the installer edits the project, the viewer reads it.
			ProjectMembers.add(_project, installer(), Role.editor);
			ProjectMembers.add(_project, viewer(), Role.viewer);
		});
	}

	/**
	 * Deletes the project and what {@link #setUp()} created besides.
	 */
	public void tearDown() throws Exception {
		kb().refetch();
		if (_project != null && _project.tValid()) {
			SharedData.deleteProjects(List.of(_project));
		}
		AssistantFixture.inTransaction(() -> {
			Collections.reverse(_types);
			for (TLObject type : _types) {
				if (type.tValid()) {
					type.tDelete();
				}
			}
			for (Person person : _persons) {
				if (person.tValid()) {
					person.tDelete();
				}
			}
		});
		_types.clear();
		_persons.clear();
	}

	/** The project. */
	public TLObject project() {
		return _project;
	}

	/** The kitchen. */
	public TLObject room() {
		return _room;
	}

	/** The group address of the given three-level address. */
	public TLObject address(String address) {
		return _addresses.get(address);
	}

	/** The application datapoint of the given id. */
	public TLObject datapoint(String id) {
		return _datapoints.get(id);
	}

	/** The account of the group <code>ehome.installers</code>, editor of the project. */
	public Person installer() {
		return _persons.get(0);
	}

	/** The account in no group of E-Home, viewer of the project. */
	public Person viewer() {
		return _persons.get(1);
	}

	/**
	 * Runs the call of a tool as the given account, as a chat run's turn runs it: with the access checks
	 * on, in a transaction that is committed afterwards (the turn's), with a {@link ToolContext} for the
	 * account.
	 */
	public static <T> T turn(Person person, java.util.function.Function<ToolContext, T> call) {
		return AssistantFixture.as(person, () -> {
			ToolContext context = context();
			try (Transaction tx = kb().beginTransaction()) {
				T result = call.apply(context);
				tx.commit();
				return result;
			}
		});
	}

	/**
	 * The context of a chat run of the current account.
	 */
	public static ToolContext context() {
		return new ToolContext(
			RunSecurity.contextFor(new RunPrincipal(TLContext.currentUser(), RunEntryPoint.CHAT, false)),
			ApprovalCallback.AUTO_APPROVE, new TransactionScope(new CancellationToken()), Collections.emptySet());
	}

	/**
	 * Runs a reading call as the given account.
	 */
	public static <T> T read(Person person, Supplier<T> call) {
		return AssistantFixture.as(person, call);
	}

	private void address(String name, String address, TLObject type, boolean writable) {
		GroupAddress result = (GroupAddress) create("ehome.logical:GroupAddress");
		result.setName(name);
		result.setAddress(GA.parse(address, GroupAddressStyle.ThreeLevel));
		result.setDatapointTypes(List.of((DatapointType) type));
		if (writable) {
			result.setRuntimeWritable(Boolean.TRUE);
		}
		_addresses.put(address, result);
	}

	private TLObject mainType(int number) {
		DatapointMainType type = (DatapointMainType) create("ehome.datapoint:DatapointMainType");
		type.setNumber(number);
		type.setExtId("TEST-LOGIC-" + _prefix + "-" + number);
		_types.add(type);
		return type;
	}

	private static BuildingDefinition part(String type, String name, String extId) {
		BuildingDefinition part = (BuildingDefinition) create(type);
		part.setName(name);
		part.setExtId(extId);
		return part;
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static Person person(String name, String group) {
		return AccountFixture.person(name, group);
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

}

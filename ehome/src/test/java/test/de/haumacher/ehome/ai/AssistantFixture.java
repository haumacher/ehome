/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.RefetchTimeout;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.wrap.Group;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

/**
 * The data the assistant's tests work on, built without an import and deleted again: an installer
 * and a viewer, a project with two devices on a line, and a controller of the project with a bearer
 * token.
 */
public class AssistantFixture {

	/** The name of the project. */
	public static final String PROJECT_NAME = "Assistant test";

	/** The title of the first device. */
	public static final String ACTUATOR = "Schaltaktor Küche";

	/** The title of the second device. */
	public static final String SENSOR = "Tastsensor Flur";

	/** The bearer token of the controller, which nobody but an administrator reads. */
	public static final String CONTROLLER_TOKEN = "hct_00112233445566778899aabbccddeeff";

	private final List<TLObject> _created = new ArrayList<>();

	private final String _prefix;

	private Person _installer;

	private Person _viewer;

	private TLObject _project;

	private TLObject _actuator;

	private TLObject _controller;

	/**
	 * Creates a {@link AssistantFixture}.
	 *
	 * @param prefix
	 *        Prefix of the accounts' names, unique per test class.
	 */
	public AssistantFixture(String prefix) {
		_prefix = prefix;
	}

	/**
	 * Creates the accounts and the objects.
	 */
	public void setUp() {
		inTransaction(() -> {
			_installer = person(_prefix + "Installer", "ehome.installers");
			_viewer = person(_prefix + "Viewer", "ehome.viewers");

			// A device needs a product variant and an application program.
			TLObject manufacturer = create("ehome.catalog:Manufacturer");
			TLObject program = create("ehome.operational:Program");
			manufacturer.tUpdateByName("programs", List.of(program));
			TLObject product = create("ehome.catalog:Product");
			TLObject variant = create("ehome.catalog:Variant");
			product.tUpdateByName("variants", List.of(variant));
			TLObject assignment = create("ehome.catalog:ProgramAssignment");
			assignment.tUpdateByName("program", program);
			product.tUpdateByName("programs", List.of(assignment));
			manufacturer.tUpdateByName("products", List.of(product));

			_controller = create("ehome.runtime:Controller");
			_controller.tUpdateByName("name", PROJECT_NAME);
			_controller.tUpdateByName("host", "localhost");
			_controller.tUpdateByName("token", CONTROLLER_TOKEN);

			_project = create("ehome.project:Project");
			_project.tUpdateByName("name", PROJECT_NAME);
			_project.tUpdateByName("controller", _controller);
			_actuator = device(ACTUATOR, variant, assignment);
			TLObject sensor = device(SENSOR, variant, assignment);
			TLObject line = create("ehome.physical:Line");
			line.tUpdateByName("devices", List.of(_actuator, sensor));
			TLObject area = create("ehome.physical:Area");
			area.tUpdateByName("lines", List.of(line));
			_project.tUpdateByName("areas", List.of(area));
		});
	}

	/**
	 * Deletes what {@link #setUp()} created.
	 */
	public void tearDown() throws RefetchTimeout {
		// A request on another thread may have changed the accounts (the last use of a token).
		kb().refetch();
		inTransaction(() -> {
			Collections.reverse(_created);
			for (TLObject object : _created) {
				if (object.tValid()) {
					object.tDelete();
				}
			}
			for (Person person : List.of(_installer, _viewer)) {
				if (person != null && person.tValid()) {
					person.tDelete();
				}
			}
		});
		_created.clear();
	}

	/** The account of the group <code>ehome.installers</code>. */
	public Person installer() {
		return _installer;
	}

	/** The account of the group <code>ehome.viewers</code>. */
	public Person viewer() {
		return _viewer;
	}

	/** The project. */
	public TLObject project() {
		return _project;
	}

	/** The first device of the project. */
	public TLObject actuator() {
		return _actuator;
	}

	/** The controller of the project. */
	public TLObject controller() {
		return _controller;
	}

	/**
	 * A TL-Script expression listing the titles of the project's devices.
	 */
	public static String deviceTitles() {
		return "all(`ehome.project:Project`)"
			+ ".filter(p -> $p.get(`ehome.project:Project#name`) == \"" + PROJECT_NAME + "\")"
			+ ".get(`ehome.project:Project#areas`).get(`ehome.physical:Area#lines`)"
			+ ".get(`ehome.physical:Line#devices`).map(d -> $d.get(`ehome.physical:Device#name`))";
	}

	/**
	 * The identifier of an object as the agents' tools and the MCP resources read it: the external
	 * form of its key, <code>Type:id</code>.
	 */
	public static String tid(TLObject object) {
		return object.tId().asString();
	}

	/**
	 * Runs a computation as the given account, with the access checks on: a test runs in the system
	 * context, which bypasses them (TL #29798).
	 */
	public static <T> T as(Person person, Supplier<T> computation) {
		Object[] result = { null };
		RuntimeException[] problem = { null };
		ThreadContext.withSecurityChecks(true, () -> {
			try {
				result[0] = TLContextManager.inPersonContext(person, computation::get);
			} catch (RuntimeException ex) {
				problem[0] = ex;
			}
		});
		if (problem[0] != null) {
			throw problem[0];
		}
		@SuppressWarnings("unchecked")
		T typed = (T) result[0];
		return typed;
	}

	/**
	 * Runs a modification in a transaction of its own, without access checks.
	 */
	public static void inTransaction(Runnable modification) {
		ThreadContext.withoutSecurityChecks(() -> {
			try (Transaction tx = kb().beginTransaction()) {
				modification.run();
				tx.commit();
			}
		});
	}

	private TLObject device(String title, TLObject variant, TLObject assignment) {
		TLObject result = create("ehome.physical:Device");
		result.tUpdateByName("title", title);
		result.tUpdateByName("type", variant);
		result.tUpdateByName("program", assignment);
		return result;
	}

	private TLObject create(String typeName) {
		TLObject result =
			ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
		_created.add(result);
		return result;
	}

	private static Person person(String name, String group) {
		Person existing = Person.byName(name);
		if (existing != null) {
			existing.tDelete();
		}
		Person result =
			Person.create(kb(), name, TLSecurityDeviceManager.getInstance().getAuthenticationDevice("dbSecurity"));
		Group.getGroupByName(kb(), group).addMember(result);
		return result;
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import test.de.haumacher.ehome.runtime.ControllerFixture;
import test.de.haumacher.ehome.security.AccountFixture;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.RefetchTimeout;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tl.ai.core.agent.AgentFactory;
import com.top_logic.tl.ai.core.agent.AgentRuntimeService;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.agent.TransactionScope;
import com.top_logic.tl.ai.core.agent.approval.ApprovalCallback;
import com.top_logic.tl.ai.core.execution.AgentRunner;
import com.top_logic.tl.ai.core.message.ContentPart;
import com.top_logic.tl.ai.core.message.Conversation;
import com.top_logic.tl.ai.core.message.TextContent;
import com.top_logic.tl.ai.core.message.UserMessage;
import com.top_logic.tl.ai.core.provider.ProviderRequest;
import com.top_logic.tl.ai.core.security.RunEntryPoint;
import com.top_logic.tl.ai.core.security.RunPrincipal;
import com.top_logic.tl.ai.core.security.RunSecurity;
import com.top_logic.tl.ai.tool.api.CancellationToken;
import com.top_logic.util.TLContext;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;

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
			_viewer = person(_prefix + "Viewer", null);

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
			ControllerFixture.own(_project, _controller);
			// Access per project (haumacher/ehome #152): the installer edits the project, the viewer reads it.
			ProjectMembers.add(_project, _installer, Role.editor);
			ProjectMembers.add(_project, _viewer, Role.viewer);
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

	/** The account of the group <code>ehome.installers</code>, editor of the project. */
	public Person installer() {
		return _installer;
	}

	/** The account in no group of E-Home, viewer of the project. */
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
	 * Runs the chat's agent as the chat runs it, for the account of the calling thread
	 * (<code>ChatProcessor.buildToolContext</code>): the account is the principal, the run is attended,
	 * approvals are given automatically.
	 *
	 * @param model
	 *        The model of the run, <code>null</code> for the configured default.
	 */
	public static AgentRunner.RecordedRun run(Conversation conversation, String model) {
		CancellationToken cancellation = new CancellationToken();
		ToolContext context = new ToolContext(
			RunSecurity.contextFor(new RunPrincipal(TLContext.currentUser(), RunEntryPoint.CHAT, false)),
			ApprovalCallback.AUTO_APPROVE, new TransactionScope(cancellation), Collections.emptySet());
		try {
			return AgentRunner.run(AgentRuntimeService.chatEntryPoint(), conversation, null, context, event -> {
				// No window to show the run in.
			}, null, null, null, null, new AgentFactory.RunOverrides(model, null, null));
		} finally {
			context.transaction().close();
		}
	}

	/**
	 * A tool context as the chat builds it for the account of the calling thread, for calling a tool
	 * directly, without the agent loop and its permission filter.
	 */
	public static ToolContext toolContext() {
		return new ToolContext(
			RunSecurity.contextFor(new RunPrincipal(TLContext.currentUser(), RunEntryPoint.CHAT, false)),
			ApprovalCallback.AUTO_APPROVE, new TransactionScope(new CancellationToken()), Collections.emptySet());
	}

	/**
	 * A conversation of one question of the user.
	 */
	public static Conversation conversation(String question) {
		Conversation result = new Conversation();
		result.add(new UserMessage(UUID.randomUUID().toString(), Instant.now(),
			List.<ContentPart> of(new TextContent(question))));
		return result;
	}

	/**
	 * The names of the tools a request offered the model.
	 */
	public static List<String> toolNames(ProviderRequest request) {
		return request.tools().stream().map(tool -> tool.name()).toList();
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

	/**
	 * A new account of the given name in the given group, <code>null</code> for none of E-Home (an
	 * existing one of that name is replaced). Must be called in a transaction.
	 */
	static Person person(String name, String group) {
		return AccountFixture.person(name, group);
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

}

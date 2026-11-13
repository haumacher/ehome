/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai.logic;

import static test.de.haumacher.ehome.ai.AssistantFixture.as;
import static test.de.haumacher.ehome.ai.AssistantFixture.tid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.DispatcherType;

import junit.framework.Test;

import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import test.com.top_logic.basic.BasicTestCase;

import com.top_logic.ai.mcp.server.MCPServerService;
import com.top_logic.ai.mcp.server.MCPTransportServlet;
import com.top_logic.ai.mcp.server.authentication.McpAuthenticationFilter;
import com.top_logic.basic.module.ModuleUtil;
import com.top_logic.basic.module.services.ServletContextService;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.core.access.AccessTokens;
import com.top_logic.tl.ai.core.agent.AgentFactory;
import com.top_logic.tl.ai.core.agent.AgentRuntimeService;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.core.agent.TransactionScope;
import com.top_logic.tl.ai.core.agent.approval.ApprovalCallback;
import com.top_logic.tl.ai.core.execution.AgentRunner;
import com.top_logic.tl.ai.core.message.ContentPart;
import com.top_logic.tl.ai.core.message.Conversation;
import com.top_logic.tl.ai.core.message.TextContent;
import com.top_logic.tl.ai.core.message.ToolCall;
import com.top_logic.tl.ai.core.message.UserMessage;
import com.top_logic.tl.ai.core.provider.ProviderRequest;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Reply;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Script;
import com.top_logic.tl.ai.core.provider.ScriptedProvider;
import com.top_logic.tl.ai.core.security.RunEntryPoint;
import com.top_logic.tl.ai.core.security.RunPrincipal;
import com.top_logic.tl.ai.core.security.RunSecurity;
import com.top_logic.tl.ai.tool.api.CancellationToken;
import com.top_logic.util.TLContext;

import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleExport;

/**
 * The assistant with E-Home's logic tools (haumacher/ehome #128), as the chat runs it on a scripted
 * model (<code>ai/testing-agents</code>) and as an external client calls the MCP endpoint:
 *
 * <ul>
 * <li>"Switch on the frost protection of the heating when the outside temperature falls below 3 °C": the
 * chat's agent loads the skill <code>EHome_rules</code>, checks the rule, tries it in the simulation
 * (the heating's address written at 1.5 °C) and saves it; the rule names the outside temperature and
 * the heating.</li>
 * <li>A viewer reads the logic and tries a rule in the simulation, and is refused saving, naming the
 * capability it lacks.</li>
 * <li>Over the MCP endpoint, an installer's client saves a rule; a viewer's client is refused.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestLogicAssistant extends BasicTestCase {

	private static final String SCRIPTED_MODEL = "scripted-model";

	private static final String CONTEXT = "/ehome";

	private final LogicFixture _fixture = new LogicFixture("ehomeLogicAssistant");

	private final HttpClient _http = HttpClient.newHttpClient();

	private Server _server;

	private ServerConnector _connector;

	private int _id;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_fixture.setUp();
	}

	@Override
	protected void tearDown() throws Exception {
		if (_server != null) {
			ModuleUtil.INSTANCE.shutDown(ServletContextService.Module.INSTANCE);
			_server.stop();
			_server = null;
		}
		_fixture.tearDown();
		super.tearDown();
	}

	public void testFrostProtectionFromASentence() throws Exception {
		String project = tid(_fixture.project());
		String frost = TestLogicTools.FROST;
		ScriptedProvider.install(Script.of(
			Reply.of(new ToolCall("c1", "loadSkill", "{\"skillId\": \"EHome_rules\"}")),
			Reply.of(new ToolCall("c2", "automation", "{\"project\": \"" + project + "\"}")),
			Reply.of(new ToolCall("c3", "checkAutomation",
				"{\"project\": \"" + project + "\", \"definitions\": " + frost + "}")),
			Reply.of(new ToolCall("c4", "simulate", "{\"project\": \"" + project + "\", \"start\": \"2026-11-10T06:00\","
				+ " \"steps\": [{\"set\": \"1/0/0\", \"value\": \"8.0\"}, {\"set\": \"1/0/0\", \"value\": \"1.5\"}],"
				+ " \"definitions\": " + frost + "}")),
			Reply.of(new ToolCall("c5", "saveAutomation",
				"{\"project\": \"" + project + "\", \"definitions\": " + frost + "}")),
			Reply.text("The frost protection is saved; deploy it in the runtime image view.")));

		AgentRunner.RecordedRun run = as(_fixture.installer(), () -> run("Switch on the frost protection of the"
			+ " heating when the outside temperature falls below 3 °C."));

		assertEquals("The frost protection is saved; deploy it in the runtime image view.", run.response());
		List<ProviderRequest> sent = ScriptedProvider.requests();
		assertEquals(6, sent.size());
		String overview = sent.get(2).messages().toString();
		assertTrue("automation names the datapoints: " + overview, overview.contains("Außentemperatur"));
		String check = sent.get(3).messages().toString();
		assertTrue("The check passes: " + check, check.contains("The definitions are valid"));
		String trial = sent.get(4).messages().toString();
		assertTrue("The simulation shows the heating written: " + trial,
			trial.contains("write Frostschutz Heizung (1/0/1) = true"));

		List<TLObject> rules = RuleExport.orderedRules(_fixture.project());
		assertEquals(1, rules.size());
		TLObject reaction = (TLObject) ((Collection<?>) rules.get(0).tValueByName("reactions")).iterator().next();
		TLObject trigger = (TLObject) ((Collection<?>) reaction.tValueByName("triggers")).iterator().next();
		TLObject write = (TLObject) ((Collection<?>) reaction.tValueByName("actions")).iterator().next();
		assertEquals(_fixture.address("1/0/0"), trigger.tValueByName("datapoint"));
		assertEquals(_fixture.address("1/0/1"), write.tValueByName("datapoint"));
	}

	public void testViewerReadsAndIsRefusedSaving() throws Exception {
		String project = tid(_fixture.project());
		ScriptedProvider.install(Script.of(
			Reply.of(new ToolCall("c1", "loadSkill", "{\"skillId\": \"EHome_rules\"}")),
			Reply.of(new ToolCall("c2", "simulate", "{\"project\": \"" + project + "\", \"start\": \"2026-11-10T06:00\","
				+ " \"steps\": [{\"set\": \"1/0/0\", \"value\": \"1.5\"}], \"definitions\": " + TestLogicTools.FROST + "}")),
			Reply.of(new ToolCall("c3", "saveAutomation",
				"{\"project\": \"" + project + "\", \"definitions\": " + TestLogicTools.FROST + "}")),
			Reply.text("I may not save it.")));

		as(_fixture.viewer(), () -> run("Add a frost protection."));

		List<ProviderRequest> sent = ScriptedProvider.requests();
		String trial = sent.get(2).messages().toString();
		assertTrue("A viewer tries the rule: " + trial, trial.contains("write Frostschutz Heizung (1/0/1) = true"));
		String refusal = sent.get(sent.size() - 1).messages().toString();
		assertTrue("Refused by the role in the project (haumacher/ehome #152): " + refusal, refusal.contains("may not change"));
		assertTrue("Nothing saved.", RuleExport.orderedRules(_fixture.project()).isEmpty());
	}

	public void testMcpSaveAsInstallerRefusedForViewer() throws Exception {
		startMcp();
		Map<String, Object> arguments = Map.of("project", tid(_fixture.project()), "definitions",
			TestLogicTools.definitions(TestLogicTools.FROST));

		String viewer = token(_fixture.viewer());
		String refused = Json.write(call(viewer, open(viewer), "tools/call",
			Map.of("name", "ehome-rules__saveAutomation", "arguments", arguments)));
		assertTrue("Refused by the role in the project (haumacher/ehome #152): " + refused, refused.contains("may not change"));
		assertTrue("Nothing saved.", RuleExport.orderedRules(_fixture.project()).isEmpty());

		String installer = token(_fixture.installer());
		String saved = Json.write(call(installer, open(installer), "tools/call",
			Map.of("name", "ehome-rules__saveAutomation", "arguments", arguments)));
		assertTrue("Saved: " + saved, saved.contains("Saved."));
		// The call committed on a thread of the server.
		PersistencyLayer.getKnowledgeBase().refetch();
		assertEquals(1, RuleExport.orderedRules(_fixture.project()).size());
	}

	// ---- the chat ----------------------------------------------------------------------------

	private static AgentRunner.RecordedRun run(String question) {
		Conversation conversation = new Conversation();
		conversation.add(new UserMessage(UUID.randomUUID().toString(), Instant.now(),
			List.<ContentPart> of(new TextContent(question))));
		CancellationToken cancellation = new CancellationToken();
		ToolContext context = new ToolContext(
			RunSecurity.contextFor(new RunPrincipal(TLContext.currentUser(), RunEntryPoint.CHAT, false)),
			ApprovalCallback.AUTO_APPROVE, new TransactionScope(cancellation), Collections.emptySet());
		try {
			return AgentRunner.run(AgentRuntimeService.chatEntryPoint(), conversation, null, context, event -> {
				// No window to show the run in.
			}, null, null, null, null, new AgentFactory.RunOverrides(SCRIPTED_MODEL, null, null));
		} finally {
			context.transaction().close();
		}
	}

	// ---- the MCP endpoint (as TestMcpEndpoint) -----------------------------------------------

	private void startMcp() throws Exception {
		_server = new Server();
		_connector = new ServerConnector(_server);
		_connector.setHost("127.0.0.1");
		_connector.setPort(0);
		_server.addConnector(_connector);
		ServletContextHandler context = new ServletContextHandler(CONTEXT);
		context.setBaseResourceAsPath(Path.of("src/main/webapp").toAbsolutePath());
		FilterHolder filter = context.addFilter(McpAuthenticationFilter.class, "/mcp/*", EnumSet.of(DispatcherType.REQUEST));
		filter.setAsyncSupported(true);
		ServletHolder servlet = context.addServlet(MCPTransportServlet.class, "/mcp/*");
		servlet.setAsyncSupported(true);
		_server.setHandler(context);
		_server.start();
		ServletContextService.startUpServletContextService(context.getServletContext());
		ModuleUtil.INSTANCE.startUp(MCPServerService.Module.INSTANCE);
	}

	private String token(Person owner) {
		return AccessTokens.create(owner, "test", null).plain();
	}

	private String open(String token) throws Exception {
		HttpResponse<String> answer = post(token, null, Json.write(Map.of("jsonrpc", "2.0", "id", Integer.valueOf(++_id),
			"method", "initialize", "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
				"clientInfo", Map.of("name", "TestLogicAssistant", "version", "1")))));
		assertEquals(answer.body(), 200, answer.statusCode());
		String session = answer.headers().firstValue("mcp-session-id").orElse(null);
		post(token, session, Json.write(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
		return session;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> call(String token, String session, String method, Map<String, Object> params)
			throws Exception {
		HttpResponse<String> answer = post(token, session,
			Json.write(Map.of("jsonrpc", "2.0", "id", Integer.valueOf(++_id), "method", method, "params", params)));
		assertEquals(answer.body(), 200, answer.statusCode());
		String json = answer.body().trim();
		if (!json.startsWith("{")) {
			StringBuilder data = new StringBuilder();
			for (String line : json.split("\n")) {
				if (line.startsWith("data:")) {
					data.append(line.substring("data:".length()).trim());
				}
			}
			json = data.toString();
		}
		Map<String, Object> message = (Map<String, Object>) Json.parse(json, 64);
		assertNull("No JSON-RPC error: " + json, message.get("error"));
		return (Map<String, Object>) message.get("result");
	}

	private HttpResponse<String> post(String token, String session, String body) throws Exception {
		HttpRequest.Builder request =
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + _connector.getLocalPort() + CONTEXT + "/mcp"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.header("Authorization", "Bearer " + token)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (session != null) {
			request.header("mcp-session-id", session);
		}
		return _http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	public static Test suite() {
		return TestLogicTools.suite(TestLogicAssistant.class);
	}

}

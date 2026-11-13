/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import static test.de.haumacher.ehome.ai.AssistantFixture.ACTUATOR;
import static test.de.haumacher.ehome.ai.AssistantFixture.CONTROLLER_TOKEN;
import static test.de.haumacher.ehome.ai.AssistantFixture.SENSOR;
import static test.de.haumacher.ehome.ai.AssistantFixture.deviceTitles;
import static test.de.haumacher.ehome.ai.AssistantFixture.tid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import jakarta.servlet.DispatcherType;

import junit.framework.Test;

import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.ai.mcp.server.MCPServerService;
import com.top_logic.ai.mcp.server.MCPTransportServlet;
import com.top_logic.ai.mcp.server.authentication.McpAuthenticationFilter;
import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.module.ModuleUtil;
import com.top_logic.basic.module.services.ServletContextService;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.tl.ai.core.access.AccessTokens;
import com.top_logic.tl.ai.core.agent.AgentApplicationService;
import com.top_logic.tl.ai.core.agent.AgentPolicyService;
import com.top_logic.tl.ai.core.agent.AgentRuntimeService;
import com.top_logic.tl.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.tl.ai.core.security.AiToolAccessService;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.Json;

/**
 * The MCP endpoint of E-Home (haumacher/ehome #129, <code>WEB-INF/autoconf/ehome.mcp.config.xml</code>)
 * over HTTP, as Claude Code calls it: the authentication filter and the transport servlet of
 * <code>tl-ai-mcp-server</code> in a Jetty of their own, with personal access tokens of an installer and
 * a viewer.
 *
 * <ul>
 * <li>Without a token, or with a token that does not exist, a request is refused with 401.</li>
 * <li>An installer's client initializes a session, lists the published tools - reading and writing -
 * and reads the devices of a project with the query tool.</li>
 * <li>A resource read of a controller by an installer leaves out its bearer token, which nobody but an
 * administrator reads.</li>
 * <li>A viewer's client reads, and is refused a writing tool, naming the capability it lacks.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestMcpEndpoint extends BasicTestCase {

	private static final String CONTEXT = "/ehome";

	private static final String QUERY = "model-query__query";

	private static final String UPDATE = "object-edit__updateObject";

	private static final String CREATE_PARTS = "ehome-building__createBuildingParts";

	private final AssistantFixture _fixture = new AssistantFixture("ehomeMcp");

	private final HttpClient _http = HttpClient.newHttpClient();

	private Server _server;

	private ServerConnector _connector;

	private int _id;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_fixture.setUp();

		_server = new Server();
		_connector = new ServerConnector(_server);
		_connector.setHost("127.0.0.1");
		_connector.setPort(0);
		_server.addConnector(_connector);
		ServletContextHandler context = new ServletContextHandler(CONTEXT);
		context.setBaseResourceAsPath(Path.of("src/main/webapp").toAbsolutePath());
		FilterHolder filter = context.addFilter(McpAuthenticationFilter.class, "/mcp/*",
			EnumSet.of(DispatcherType.REQUEST));
		filter.setAsyncSupported(true);
		ServletHolder servlet = context.addServlet(MCPTransportServlet.class, "/mcp/*");
		servlet.setAsyncSupported(true);
		_server.setHandler(context);
		_server.start();

		// The MCP service initializes its transport with the servlet context of the application.
		ServletContextService.startUpServletContextService(context.getServletContext());
		ModuleUtil.INSTANCE.startUp(MCPServerService.Module.INSTANCE);
	}

	@Override
	protected void tearDown() throws Exception {
		ModuleUtil.INSTANCE.shutDown(ServletContextService.Module.INSTANCE);
		_server.stop();
		_fixture.tearDown();
		super.tearDown();
	}

	public void testWithoutTokenRefused() throws Exception {
		assertEquals(401, post(null, null, initialize()).statusCode());
		assertEquals(401,
			post("tlpat_0000000000000000000000000000000000000000000", null, initialize()).statusCode());
	}

	public void testInstallerListsToolsAndReadsAProject() throws Exception {
		String token = token(_fixture.installer());
		String session = open(token);

		List<String> tools = toolNames(call(token, session, "tools/list", Map.of()));
		assertTrue("Reading tools are published: " + tools, tools.contains(QUERY));
		assertTrue("Writing tools are published: " + tools, tools.contains(UPDATE));
		assertFalse("No privileged tool is published: " + tools,
			tools.stream().anyMatch(name -> name.contains("changeData")));

		String answer = Json.write(call(token, session, "tools/call",
			Map.of("name", QUERY, "arguments", Map.of("expression", deviceTitles()))));
		assertTrue(answer, answer.contains(ACTUATOR));
		assertTrue(answer, answer.contains(SENSOR));
	}

	public void testControllerTokenNotDisclosed() throws Exception {
		String token = token(_fixture.installer());
		String session = open(token);

		String uri = "toplogic://instance/" + tid(_fixture.controller());
		String resource = Json.write(call(token, session, "resources/read", Map.of("uri", uri)));
		assertTrue("The installer reads the controller: " + resource, resource.contains("localhost"));
		assertFalse("The bearer token stays hidden: " + resource, resource.contains(CONTROLLER_TOKEN));
	}

	public void testViewerRefusedAWritingTool() throws Exception {
		String token = token(_fixture.viewer());
		String session = open(token);

		String read = Json.write(call(token, session, "tools/call",
			Map.of("name", QUERY, "arguments", Map.of("expression", deviceTitles()))));
		assertTrue("A viewer reads: " + read, read.contains(ACTUATOR));

		String id = tid(_fixture.actuator());
		String write = Json.write(call(token, session, "tools/call",
			Map.of("name", UPDATE, "arguments", Map.of("id", id, "values", Map.of("title", "Renamed")))));
		// Refused by the role in the project, not by the tool access (haumacher/ehome #152).
		assertTrue("The refusal names the object: " + write, write.contains("may not change"));
		assertEquals(ACTUATOR, _fixture.actuator().tValueByName("title"));
	}

	/**
	 * E-Home's own tools (haumacher/ehome #127) over MCP: an installer's client lays out a building
	 * with a floor in one call; a viewer's client is refused the change by its role in the project, and
	 * nothing is created.
	 */
	public void testEhomeToolWritesForInstallerOnly() throws Exception {
		String viewer = token(_fixture.viewer());
		String viewerSession = open(viewer);
		String refused = Json.write(call(viewer, viewerSession, "tools/call", Map.of("name", CREATE_PARTS,
			"arguments", Map.of("parent", tid(_fixture.project()), "parts", List.of(Map.of("kind", "Building",
				"name", "Viewerhaus"))))));
		assertTrue("Refused by the role in the project (haumacher/ehome #152): " + refused, refused.contains("may not change"));
		assertTrue(buildings().isEmpty());

		String installer = token(_fixture.installer());
		String session = open(installer);
		List<String> tools = toolNames(call(installer, session, "tools/list", Map.of()));
		assertTrue("E-Home's tools are published: " + tools, tools.contains(CREATE_PARTS));
		String created = Json.write(call(installer, session, "tools/call", Map.of("name", CREATE_PARTS,
			"arguments", Map.of("parent", tid(_fixture.project()), "parts", List.of(Map.of("kind", "Building",
				"name", "MCP-Haus", "parts", List.of(Map.of("kind", "Floor", "name", "Erdgeschoss"))))))));
		assertTrue(created, created.contains("Created."));
		PersistencyLayer.getKnowledgeBase().refetch();
		List<TLObject> buildings = buildings();
		assertEquals(1, buildings.size());
		assertEquals("MCP-Haus", buildings.get(0).tValueByName("name"));
		List<?> floors = (List<?>) buildings.get(0).tValueByName("parts");
		assertEquals(1, floors.size());
		assertEquals("Erdgeschoss", ((TLObject) floors.get(0)).tValueByName("name"));
	}

	private List<TLObject> buildings() {
		List<TLObject> result = new ArrayList<>();
		for (Object building : (Collection<?>) _fixture.project().tValueByName("buildings")) {
			result.add((TLObject) building);
		}
		return result;
	}

	private String token(Person owner) {
		return AccessTokens.create(owner, "test", null).plain();
	}

	private String open(String token) throws Exception {
		HttpResponse<String> answer = post(token, null, initialize());
		assertEquals(answer.body(), 200, answer.statusCode());
		String session = answer.headers().firstValue("mcp-session-id").orElse(null);
		assertNotNull("The server opens a session.", session);
		String instructions = Json.write(result(answer.body()));
		assertTrue("The instructions name E-Home: " + instructions, instructions.contains("E-Home"));
		post(token, session, Json.write(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
		return session;
	}

	private String initialize() {
		return Json.write(Map.of("jsonrpc", "2.0", "id", Integer.valueOf(++_id), "method", "initialize",
			"params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
				"clientInfo", Map.of("name", "TestMcpEndpoint", "version", "1"))));
	}

	private Map<String, Object> call(String token, String session, String method, Map<String, Object> params)
			throws Exception {
		HttpResponse<String> answer = post(token, session,
			Json.write(Map.of("jsonrpc", "2.0", "id", Integer.valueOf(++_id), "method", method, "params", params)));
		assertEquals(answer.body(), 200, answer.statusCode());
		return result(answer.body());
	}

	private HttpResponse<String> post(String token, String session, String body) throws Exception {
		HttpRequest.Builder request =
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + _connector.getLocalPort() + CONTEXT + "/mcp"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (session != null) {
			request.header("mcp-session-id", session);
		}
		return _http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/**
	 * The result of a JSON-RPC answer, sent as JSON or as the data of a server-sent event.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> result(String body) throws Exception {
		String json = body.trim();
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

	@SuppressWarnings("unchecked")
	private static List<String> toolNames(Map<String, Object> listing) {
		List<String> result = new ArrayList<>();
		for (Object tool : (List<Object>) listing.get("tools")) {
			result.add((String) ((Map<String, Object>) tool).get("name"));
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestMcpEndpoint.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, AiToolAccessService.Module.INSTANCE,
			TopLogicAgentDefinitions.Module.INSTANCE, AgentApplicationService.Module.INSTANCE,
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE));
	}

}

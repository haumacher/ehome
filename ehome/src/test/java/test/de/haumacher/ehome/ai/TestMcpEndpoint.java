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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
import test.de.haumacher.ehome.security.AccountFixture;

import com.top_logic.ai.mcp.server.MCPServerService;
import com.top_logic.ai.mcp.server.MCPTransportServlet;
import com.top_logic.ai.mcp.server.authentication.McpAuthenticationFilter;
import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.module.ModuleUtil;
import com.top_logic.basic.module.services.ServletContextService;
import com.top_logic.basic.util.ResKey;
import com.top_logic.element.accesstoken.AccessTokenPermissions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.WrapperHistoryUtils;
import com.top_logic.util.Resources;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.oauth.server.OAuthAuthorizationServer;
import com.top_logic.oauth.server.OAuthServerServlet;
import com.top_logic.oauth.server.ProtectedResources;
import com.top_logic.ai.core.access.AccessTokenAuthenticator;
import com.top_logic.element.accesstoken.AccessTokens;
import com.top_logic.ai.core.agent.AgentApplicationService;
import com.top_logic.ai.core.agent.AgentPolicyService;
import com.top_logic.ai.core.agent.AgentRuntimeService;
import com.top_logic.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.ai.core.security.AiToolAccessService;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.building.BuildingDefinition;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.runtime.Json;

/**
 * The MCP endpoint of E-Home (haumacher/ehome #129, <code>WEB-INF/autoconf/ehome.mcp.config.xml</code>)
 * over HTTP, as Claude Code calls it: the authentication filter and the transport servlet of
 * <code>tl-ai-mcp-server</code> and the servlet of the authorization server of <code>tl-oauth-server</code>
 * in a Jetty of their own, with personal access tokens of an installer and a viewer and with the OAuth
 * sign-in (TL #30274).
 *
 * <ul>
 * <li>Without a token, or with a token that does not exist, a request is refused with 401 and a Bearer
 * challenge naming the protected resource metadata.</li>
 * <li>A client knowing only the address finds the authorization server through the protected resource
 * metadata and its metadata, reads the server card without a credential, registers itself, is allowed
 * by an installer (the consent page's approval) and calls the endpoint with its access token.</li>
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
		// The mapping of the web fragment of tl-oauth-server.
		ServletHolder oauth = new ServletHolder(OAuthServerServlet.class);
		for (String path : List.of(OAuthAuthorizationServer.METADATA_PATH,
			OAuthAuthorizationServer.OPENID_CONFIGURATION_PATH, ProtectedResources.METADATA_PATH + "/*",
			OAuthAuthorizationServer.TOKEN_PATH, OAuthAuthorizationServer.AUTHORIZATION_PATH,
			OAuthAuthorizationServer.REGISTRATION_PATH)) {
			context.addServlet(oauth, path);
		}
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
		HttpResponse<String> anonymous = post(null, null, initialize());
		assertEquals(401, anonymous.statusCode());
		String challenge = anonymous.headers().allValues("WWW-Authenticate").stream().collect(Collectors.joining(", "));
		// The OAuth sign-in comes first: a client looks for resource_metadata only after a Bearer challenge.
		assertTrue(challenge, challenge.startsWith("Bearer "));
		assertTrue(challenge, challenge.contains("resource_metadata=\"" + base() + "/.well-known/oauth-protected-resource/mcp\""));
		assertEquals(401,
			post("tlpat_0000000000000000000000000000000000000000000", null, initialize()).statusCode());
		assertEquals(401,
			post(OAuthAuthorizationServer.ACCESS_TOKEN_PREFIX + "0000000000000000000000000000000000000000000", null,
				initialize()).statusCode());
	}

	/**
	 * What a client set up with the address alone finds (TL #30274): the protected resource metadata
	 * named in the challenge, the metadata of the authorization server it names, and the server card,
	 * all without a credential.
	 */
	@SuppressWarnings("unchecked")
	public void testDiscovery() throws Exception {
		Map<String, Object> resource = getJson("/.well-known/oauth-protected-resource/mcp");
		assertEquals(base() + "/mcp", resource.get("resource"));
		assertEquals(List.of(base()), resource.get("authorization_servers"));

		for (String path : List.of("/.well-known/oauth-authorization-server", "/.well-known/openid-configuration")) {
			Map<String, Object> server = getJson(path);
			assertEquals(path, base(), server.get("issuer"));
			assertEquals(path, base() + "/oauth/authorize", server.get("authorization_endpoint"));
			assertEquals(path, base() + "/oauth/token", server.get("token_endpoint"));
			assertEquals(path, base() + "/oauth/register", server.get("registration_endpoint"));
			assertEquals(path, List.of("S256"), server.get("code_challenge_methods_supported"));
		}

		HttpResponse<String> card = get("/mcp/server-card");
		assertEquals(card.body(), 200, card.statusCode());
		Map<String, Object> json = (Map<String, Object>) Json.parse(card.body(), 16);
		assertEquals("E-Home", json.get("title"));
		assertTrue(card.body(), String.valueOf(json.get("description")).contains("KNX"));
		Map<String, Object> remote = ((List<Map<String, Object>>) json.get("remotes")).get(0);
		assertEquals(base() + "/mcp", remote.get("url"));
		// The sign-in needs no header; the header of the personal access token is optional.
		for (Map<String, Object> header : (List<Map<String, Object>>) remote.getOrDefault("headers", List.of())) {
			assertFalse(card.body(), Boolean.TRUE.equals(header.get("isRequired")));
		}

		assertEquals(404, get("/.well-known/oauth-protected-resource/other").statusCode());
	}

	/**
	 * The sign-in of a client as the MCP authorization profile describes it (TL #30274): it registers
	 * itself, the installer allows it on the consent page (here the approval the page's command
	 * calls), the client exchanges the code with its PKCE verifier and calls the endpoint as the
	 * installer; it renews the access token with the refresh token. A personal access token keeps
	 * working beside it.
	 */
	@SuppressWarnings("unchecked")
	public void testOAuthSignIn() throws Exception {
		String redirect = "http://127.0.0.1:33418/callback";
		HttpResponse<String> registration = postJson("/oauth/register", Json.write(Map.of(
			"client_name", "TestMcpEndpoint", "redirect_uris", List.of(redirect),
			"token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"),
			"response_types", List.of("code"))));
		assertEquals(registration.body(), 201, registration.statusCode());
		String clientId = (String) ((Map<String, Object>) Json.parse(registration.body(), 16)).get("client_id");

		byte[] secret = new byte[32];
		new SecureRandom().nextBytes(secret);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
			MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		String resource = base() + "/mcp";
		HttpResponse<String> authorize = get("/oauth/authorize?" + form(Map.of("response_type", "code",
			"client_id", clientId, "redirect_uri", redirect, "code_challenge", challenge,
			"code_challenge_method", "S256", "resource", resource, "state", "s1")));
		assertEquals(authorize.body(), 302, authorize.statusCode());
		String consent = authorize.headers().firstValue("Location").orElseThrow();
		assertTrue(consent, consent.contains(CONTEXT + "/view/oauth-consent.view.xml?pending="));
		String pending = consent.substring(consent.indexOf("pending=") + "pending=".length());

		// The request stored the pending authorization in the thread of the servlet.
		PersistencyLayer.getKnowledgeBase().refetch();
		URI answer = OAuthAuthorizationServer.getInstance().approve(pending, _fixture.installer());
		Map<String, String> query = parameters(answer.getRawQuery());
		assertTrue(answer.toString(), answer.toString().startsWith(redirect));
		assertEquals("s1", query.get("state"));

		HttpResponse<String> tokens = postForm("/oauth/token", Map.of("grant_type", "authorization_code",
			"code", query.get("code"), "redirect_uri", redirect, "client_id", clientId, "code_verifier", verifier,
			"resource", resource));
		assertEquals(tokens.body(), 200, tokens.statusCode());
		Map<String, Object> pair = (Map<String, Object>) Json.parse(tokens.body(), 16);
		String access = (String) pair.get("access_token");
		assertTrue(access, access.startsWith(OAuthAuthorizationServer.ACCESS_TOKEN_PREFIX));

		String session = open(access);
		List<String> tools = toolNames(call(access, session, "tools/list", Map.of()));
		assertTrue("The installer's tools: " + tools, tools.contains(CREATE_PARTS));
		String read = Json.write(call(access, session, "tools/call",
			Map.of("name", QUERY, "arguments", Map.of("expression", deviceTitles()))));
		assertTrue("The client reads as the installer: " + read, read.contains(ACTUATOR));

		HttpResponse<String> refreshed = postForm("/oauth/token", Map.of("grant_type", "refresh_token",
			"refresh_token", (String) pair.get("refresh_token"), "client_id", clientId, "resource", resource));
		assertEquals(refreshed.body(), 200, refreshed.statusCode());
		String renewed = (String) ((Map<String, Object>) Json.parse(refreshed.body(), 16)).get("access_token");
		open(renewed);

		// The token of a script, beside the sign-in.
		open(token(_fixture.installer()));
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
		assertEquals(ACTUATOR, _fixture.actuator().getTitle());
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
		assertEquals("MCP-Haus", ((BuildingDefinition) buildings.get(0)).getName());
		List<?> floors = ((BuildingDefinition) buildings.get(0)).getParts();
		assertEquals(1, floors.size());
		assertEquals("Erdgeschoss", ((BuildingDefinition) floors.get(0)).getName());
	}

	/**
	 * The tools of the device templates (haumacher/ehome #174) over MCP: listed, the writing ones not
	 * read-only (the client asks before a call), and a reading one answers an installer.
	 */
	@SuppressWarnings("unchecked")
	public void testTemplateToolsPublished() throws Exception {
		String installer = token(_fixture.installer());
		String session = open(installer);
		Map<String, Object> listing = call(installer, session, "tools/list", Map.of());
		List<String> tools = toolNames(listing);
		for (String name : List.of("programTemplates", "objectKinds", "setObjectKindType", "presets", "preset",
			"presetCandidates", "savePreset", "deletePreset")) {
			assertTrue(name + " is published: " + tools, tools.contains("ehome-templates__" + name));
		}
		for (Object tool : (List<Object>) listing.get("tools")) {
			Map<String, Object> entry = (Map<String, Object>) tool;
			String name = (String) entry.get("name");
			if (List.of("ehome-templates__setObjectKindType", "ehome-templates__savePreset",
				"ehome-templates__deletePreset").contains(name)) {
				Map<String, Object> annotations = (Map<String, Object>) entry.get("annotations");
				assertNotNull(name + ": " + entry, annotations);
				assertFalse(name + " changes data: " + annotations, Boolean.TRUE.equals(annotations.get("readOnlyHint")));
			}
		}
		String answer = Json.write(call(installer, session, "tools/call", Map.of("name",
			"ehome-templates__programTemplates", "arguments", Map.of("text", ""))));
		assertTrue(answer, answer.contains("\\\"programs\\\"") || answer.contains("\"programs\""));
		assertFalse(answer, answer.contains("\"isError\": true"));
	}

	/**
	 * A writing tool called over the endpoint commits with the message the tool describes its call
	 * with (TL #30276), not with the generic one of the engine.
	 */
	public void testCommitMessageOfAToolCall() throws Exception {
		String installer = token(_fixture.installer());
		String session = open(installer);
		String created = Json.write(call(installer, session, "tools/call", Map.of("name", CREATE_PARTS,
			"arguments", Map.of("parent", tid(_fixture.project()), "parts", List.of(Map.of("kind", "Building",
				"name", "Protokollhaus"))))));
		assertTrue(created, created.contains("Created."));
		// The endpoint commits each call when it returns: the answer says so, not the chat's "when this
		// turn ends" (production report of 2026-11-15).
		assertTrue(created, created.contains("committed when this call returned"));
		assertFalse(created, created.contains("when this turn ends"));
		PersistencyLayer.getKnowledgeBase().refetch();
		TLObject building = buildings().stream()
			.filter(b -> "Protokollhaus".equals(((BuildingDefinition) b).getName())).findFirst().orElseThrow();
		ResKey log = WrapperHistoryUtils.getCreateRevision(building).getLog();
		String message = Resources.getInstance(Locale.ENGLISH).getString(log);
		assertTrue("The revision of the call names the part created: " + message, message.contains("Protokollhaus"));
		assertFalse("The revision of the call has not the generic message: " + message, message.contains("Agent edit"));
	}

	/**
	 * An E-Home administrator (haumacher/ehome #165) who is no member of a project reads it but changes
	 * nothing of it through the endpoint - neither with E-Home's own tools nor with the object tools of
	 * the engine: the administrator manages a project's members, the content belongs to its members.
	 */
	public void testAdministratorChangesNoForeignProject() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Person administrator;
		try (Transaction tx = kb.beginTransaction()) {
			administrator = AccountFixture.person("ehomeMcpAdministrator", "ehome.administrators");
			tx.commit();
		}
		try {
			String token = token(administrator);
			String session = open(token);
			String read = Json.write(call(token, session, "tools/call",
				Map.of("name", QUERY, "arguments", Map.of("expression", deviceTitles()))));
			assertTrue("The administrator reads every project: " + read, read.contains(ACTUATOR));

			String created = Json.write(call(token, session, "tools/call", Map.of("name", CREATE_PARTS,
				"arguments", Map.of("parent", tid(_fixture.project()), "parts", List.of(Map.of("kind", "Building",
					"name", "Adminhaus"))))));
			assertTrue("E-Home's tool refuses: " + created, created.contains("may not change"));
			kb.refetch();
			assertTrue(buildings().isEmpty());

			String updated = Json.write(call(token, session, "tools/call", Map.of("name", UPDATE,
				"arguments", Map.of("id", tid(_fixture.actuator()), "values", Map.of("title", "Renamed")))));
			assertTrue("The object tool refuses: " + updated, updated.contains("may not change"));
			assertEquals(ACTUATOR, _fixture.actuator().getTitle());
		} finally {
			kb.refetch();
			try (Transaction tx = kb.beginTransaction()) {
				administrator.tDelete();
				tx.commit();
			}
		}
	}

	private List<TLObject> buildings() {
		List<TLObject> result = new ArrayList<>();
		for (Object building : ((Project) _fixture.project()).getBuildings()) {
			result.add((TLObject) building);
		}
		return result;
	}

	private String token(Person owner) {
		return AccessTokens.create(owner, "test", null,
			List.of(AccessTokenAuthenticator.AGENT_ENDPOINTS_PERMISSION), null).plain();
	}

	private String base() {
		return "http://127.0.0.1:" + _connector.getLocalPort() + CONTEXT;
	}

	private HttpResponse<String> get(String path) throws Exception {
		return _http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getJson(String path) throws Exception {
		HttpResponse<String> answer = get(path);
		assertEquals(path + ": " + answer.body(), 200, answer.statusCode());
		return (Map<String, Object>) Json.parse(answer.body(), 16);
	}

	private HttpResponse<String> postJson(String path, String body) throws Exception {
		return send(path, "application/json", body);
	}

	private HttpResponse<String> postForm(String path, Map<String, String> values) throws Exception {
		return send(path, "application/x-www-form-urlencoded", form(values));
	}

	private HttpResponse<String> send(String path, String contentType, String body) throws Exception {
		return _http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", contentType)
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static String form(Map<String, String> values) {
		return values.entrySet().stream()
			.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
				+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
	}

	private static Map<String, String> parameters(String query) {
		return Arrays.stream(query.split("&")).map(p -> p.split("=", 2)).collect(Collectors.toMap(
			p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
			p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
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
			HttpRequest.newBuilder(URI.create(base() + "/mcp"))
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
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE,
			AccessTokenPermissions.Module.INSTANCE, OAuthAuthorizationServer.Module.INSTANCE));
	}

}

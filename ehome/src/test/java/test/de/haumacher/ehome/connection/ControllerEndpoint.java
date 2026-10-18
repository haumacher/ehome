/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.ControllerServlet;
import de.haumacher.ehome.connection.ControllerSocketDeployment;
import de.haumacher.ehome.connection.ControllerSocketRegistration;
import de.haumacher.ehome.runtime.Json;

/**
 * The endpoints of the E-Home connection ({@link ControllerServlet}, the socket of
 * {@link ControllerSocketRegistration} over Jetty's Jakarta WebSocket) in an in-process Jetty on
 * <code>127.0.0.1</code>, under the context path <code>/ehome</code> as in the application: the
 * enrollment route and the controllers' WebSocket, over the knowledge base of the test.
 */
public class ControllerEndpoint {

	/** The context path. */
	public static final String CONTEXT = "/ehome";

	private final Server _server;

	private final ServerConnector _connector;

	private final HttpClient _http = HttpClient.newHttpClient();

	/**
	 * Creates and starts a {@link ControllerEndpoint} on a free port.
	 */
	public ControllerEndpoint() throws Exception {
		_server = new Server();
		_connector = new ServerConnector(_server);
		_connector.setHost("127.0.0.1");
		_connector.setPort(0);
		_server.addConnector(_connector);
		ServletContextHandler context = new ServletContextHandler(CONTEXT);
		context.addServlet(ControllerServlet.class, ControllerServlet.PATH + "/*");
		JakartaWebSocketServletContainerInitializer.configure(context,
			(servletContext, container) -> ControllerSocketDeployment.register(container));
		_server.setHandler(context);
		_server.start();
	}

	/**
	 * The address of E-Home as a controller calls it: <code>http://127.0.0.1:port/ehome</code>.
	 */
	public String base() {
		return "http://127.0.0.1:" + _connector.getLocalPort() + CONTEXT;
	}

	/**
	 * <code>POST /api/controllers/enroll</code> with the given body and headers (name, value, ...).
	 *
	 * @return The status and the JSON body of the answer, the status under the key
	 *         <code>"status"</code>.
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> enroll(String body, String... headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + ControllerServlet.PATH + "/enroll"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		for (int n = 0; n + 1 < headers.length; n += 2) {
			request.header(headers[n], headers[n + 1]);
		}
		HttpResponse<String> response = _http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		Map<String, Object> result = (Map<String, Object>) Json.parse(response.body(), 8);
		result.put("status", Integer.valueOf(response.statusCode()));
		ControllerConnections.refetch();
		return result;
	}

	/**
	 * A plain <code>GET</code> of the given path below the context: the status.
	 */
	public int get(String path) throws Exception {
		return _http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
			HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	/**
	 * Stops it.
	 */
	public void stop() throws Exception {
		_server.stop();
	}

	/**
	 * A new project of the given name, without anything in it.
	 */
	public static TLObject project(String name) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", name);
			tx.commit();
			return project;
		}
	}

}

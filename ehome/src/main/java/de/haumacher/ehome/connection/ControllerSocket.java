/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import com.top_logic.basic.Logger;

/**
 * The WebSocket of a controller's connection: an endpoint of the container's Jakarta WebSocket
 * implementation at {@value #PATH}, which hands the messages to its {@link ControllerConnection} and
 * sends the connection's messages.
 *
 * <p>
 * The engine's <code>com.top_logic.util.WebSocketEndpointListener</code> registers it with the
 * container (the context parameter <code>websocket.endpoints</code> in <code>WEB-INF/web.xml</code>):
 * in Tomcat as in TopLogic's development start, whose Jetty provides Jakarta WebSocket but scans no
 * classes for endpoints.
 * </p>
 *
 * <p>
 * The container does the framing, answers the controller's pings and closes a socket that received
 * nothing within {@link ControllerConnections#IDLE_TIMEOUT}. It calls the message handlers of one session one at a
 * time; sending is serialized by the connection.
 * </p>
 */
@ServerEndpoint(ControllerSocket.PATH)
public class ControllerSocket implements ControllerConnection.Peer {

	/** The name of the path parameter of the controller's id. */
	public static final String ID = "id";

	/** The path of the socket below the context path. */
	public static final String PATH = ControllerServlet.PATH + "/{" + ID + "}/socket";

	/** The longest message E-Home takes from a controller (a status, a snapshot chunk, a response). */
	public static final int MAX_MESSAGE = 1024 * 1024;

	/**
	 * The session's user property under which the containers (Tomcat, Jetty) put the remote address.
	 */
	private static final String REMOTE_ADDRESS = "jakarta.websocket.endpoint.remoteAddress";

	private volatile Session _session;

	private ControllerConnection _connection;

	private String _remote = "?";

	/**
	 * Creates a {@link ControllerSocket}; called by the container for each connection.
	 */
	public ControllerSocket() {
		// Set up in onOpen(...).
	}

	/**
	 * Called by the container when the controller has connected.
	 */
	@OnOpen
	public void onOpen(Session session) {
		_session = session;
		Object remote = session.getUserProperties().get(REMOTE_ADDRESS);
		if (remote != null) {
			_remote = remote.toString();
		}
		session.setMaxTextMessageBufferSize(MAX_MESSAGE);
		session.setMaxBinaryMessageBufferSize(MAX_MESSAGE);
		session.setMaxIdleTimeout(ControllerConnections.IDLE_TIMEOUT.toMillis());
		String id = session.getPathParameters().get(ID);
		_connection = new ControllerConnection(id == null ? "" : id, this);
		ControllerConnections.opening(_connection);
		_connection.open();
	}

	/**
	 * Called by the container for a text message of the controller.
	 */
	@OnMessage(maxMessageSize = MAX_MESSAGE)
	public void onText(String message) {
		_connection.received(message);
	}

	/**
	 * Called by the container for a binary message of the controller.
	 */
	@OnMessage(maxMessageSize = MAX_MESSAGE)
	public void onBinary(ByteBuffer message) {
		byte[] data = new byte[message.remaining()];
		message.get(data);
		_connection.receivedBinary(data);
	}

	/**
	 * Called by the container when the socket has closed.
	 */
	@OnClose
	public void onClose(Session session, CloseReason reason) {
		if (_connection != null) {
			String phrase = reason.getReasonPhrase();
			_connection.closed("closed (" + reason.getCloseCode().getCode()
				+ (phrase == null || phrase.isBlank() ? "" : " " + phrase) + ")");
		}
	}

	/**
	 * Called by the container when the socket failed.
	 */
	@OnError
	public void onError(Session session, Throwable error) {
		if (_connection != null) {
			Logger.info("The socket of " + _connection + " failed: " + error, ControllerSocket.class);
			_connection.closed(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
		}
	}

	// ControllerConnection.Peer

	@Override
	public void sendText(String text) throws IOException {
		Session session = _session;
		if (session == null || !session.isOpen()) {
			throw new IOException("The socket is closed.");
		}
		session.getBasicRemote().sendText(text);
	}

	@Override
	public void sendBinary(byte[] data) throws IOException {
		Session session = _session;
		if (session == null || !session.isOpen()) {
			throw new IOException("The socket is closed.");
		}
		session.getBasicRemote().sendBinary(ByteBuffer.wrap(data));
	}

	@Override
	public void close(int code, String reason) {
		Session session = _session;
		if (session == null || !session.isOpen()) {
			return;
		}
		try {
			session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), phrase(reason)));
		} catch (IOException | IllegalStateException ex) {
			// Closed anyway.
		}
	}

	/**
	 * The reason cut to the 123 bytes a close frame holds.
	 */
	private static String phrase(String reason) {
		if (reason == null) {
			return "";
		}
		byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
		if (bytes.length <= 123) {
			return reason;
		}
		String cut = new String(Arrays.copyOf(bytes, 123), StandardCharsets.UTF_8);
		// A character cut in half decodes to the replacement character: drop it.
		return cut.endsWith("�") ? cut.substring(0, cut.length() - 1) : cut;
	}

	@Override
	public String remote() {
		return _remote;
	}

}

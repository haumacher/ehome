/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * The client side of the deployment protocol of a HomeControl controller (homecontrol
 * <code>spec/api.md</code>, "Runtime image" and "Status"): <code>PUT</code>, <code>GET</code> and
 * <code>DELETE /api/image</code> and <code>GET /api/status</code>, authenticated by a bearer token.
 */
public final class ControllerClient {

	/**
	 * An answer of the controller.
	 *
	 * @param status
	 *        The HTTP status.
	 * @param body
	 *        The JSON object of the body; empty if the body is no JSON object.
	 * @param text
	 *        The body as text.
	 */
	public record Answer(int status, Map<String, Object> body, String text) {

		/**
		 * Whether the status is 2xx.
		 */
		public boolean ok() {
			return status >= 200 && status < 300;
		}

		/**
		 * A text member of the body, <code>null</code> if there is none.
		 */
		public String string(String member) {
			Object value = body.get(member);
			return value == null ? null : value.toString();
		}
	}

	private static final int MAX_NESTING = 16;

	private final String _host;

	private final int _port;

	private final String _token;

	private final HttpClient _http;

	private final Duration _timeout;

	/**
	 * Creates a {@link ControllerClient}.
	 *
	 * @param host
	 *        The host name or address of the controller.
	 * @param port
	 *        The port of its web API.
	 * @param token
	 *        The bearer token, <code>null</code> to send none.
	 * @param timeout
	 *        How long to wait for the connection and for an answer (a load takes up to a few hundred
	 *        milliseconds on the device).
	 */
	public ControllerClient(String host, int port, String token, Duration timeout) {
		_host = host;
		_port = port;
		_token = token;
		_timeout = timeout;
		_http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	/**
	 * The address of the controller as written for the user, <code>host:port</code>.
	 */
	public String address() {
		return (_host.contains(":") ? "[" + _host + "]" : _host) + ":" + _port;
	}

	/**
	 * <code>GET /api/status</code>.
	 */
	public Answer status() throws IOException {
		return send(request("/api/status").GET());
	}

	/**
	 * <code>PUT /api/image</code> with the given image.
	 */
	public Answer putImage(byte[] image) throws IOException {
		return send(request("/api/image")
			.header("Content-Type", RuntimeImage.CONTENT_TYPE)
			.PUT(HttpRequest.BodyPublishers.ofByteArray(image)));
	}

	/**
	 * <code>GET /api/image</code>: the stored image, byte for byte as it was deployed (404
	 * <code>image.none</code> when none is stored).
	 */
	public Answer getImage() throws IOException {
		return send(request("/api/image").GET());
	}

	/**
	 * <code>DELETE /api/image</code>: unloads the image and removes it with its persisted values and
	 * history.
	 */
	public Answer deleteImage() throws IOException {
		return send(request("/api/image").DELETE());
	}

	private HttpRequest.Builder request(String path) throws IOException {
		URI uri;
		try {
			uri = new URI("http", null, _host, _port, path, null, null);
		} catch (URISyntaxException ex) {
			throw new IOException("Invalid controller address " + address() + ": " + ex.getMessage(), ex);
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(_timeout).header("Accept", "application/json");
		if (_token != null && !_token.isBlank()) {
			builder.header("Authorization", "Bearer " + _token.trim());
		}
		return builder;
	}

	private Answer send(HttpRequest.Builder request) throws IOException {
		HttpResponse<byte[]> response;
		try {
			response = _http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the controller.", ex);
		}
		String text = new String(response.body(), StandardCharsets.UTF_8);
		Map<String, Object> body = Collections.emptyMap();
		try {
			if (!text.isBlank() && Json.parse(text, MAX_NESTING) instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> object = (Map<String, Object>) map;
				body = object;
			}
		} catch (JsonException ex) {
			// Not JSON: the text is kept.
		}
		return new Answer(response.statusCode(), body, text);
	}

}

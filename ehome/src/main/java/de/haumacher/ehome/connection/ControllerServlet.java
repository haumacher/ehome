/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.top_logic.basic.Logger;
import com.top_logic.basic.thread.ThreadContextManager;

import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * The endpoints of the E-Home connection (haumacher/ehome #54, homecontrol <code>spec/api.md</code>,
 * "E-Home connection"), mapped to <code>/api/controllers/*</code> in <code>WEB-INF/web.xml</code>; open,
 * outside TopLogic's login:
 *
 * <ul>
 * <li><code>POST /api/controllers/enroll</code>: the enrollment of a controller with a project's code
 * ({@link Enrollment}), limited to {@value #ATTEMPTS_PER_ADDRESS} attempts per minute and remote
 * address and {@value #ATTEMPTS_TOTAL} in total (429 <code>ehome.rate_limited</code>). A body above
 * {@value #MAX_BODY} bytes is 413 <code>ehome.too_large</code>, one that is no JSON object 400
 * <code>ehome.bad_request</code>.</li>
 * <li><code>GET /api/controllers/&lt;id&gt;/socket</code> without a WebSocket upgrade: 426
 * <code>ehome.upgrade_required</code>. The WebSocket itself is the container's
 * ({@link ControllerSocketRegistration}), which takes the upgrade before the servlet; an upgrade that
 * reaches the servlet is 501 <code>ehome.no_websocket</code>.</li>
 * </ul>
 *
 * <p>
 * The address the controller called E-Home by (for the socket's URL in the enrollment's answer) is
 * the request's scheme, host and context path, or what a reverse proxy names in
 * <code>X-Forwarded-Proto</code> and <code>X-Forwarded-Host</code>: behind a proxy terminating TLS the
 * request reaches E-Home as <code>http</code>, and the controller must still get <code>wss</code>.
 * The remote address of the rate limit is the one the container reports; behind a proxy the
 * container must take it from the forwarded headers (an operations setting, doc/controllers.md).
 * </p>
 */
public class ControllerServlet extends HttpServlet {

	/** The path the servlet is mapped to, below the context path. */
	public static final String PATH = "/api/controllers";

	/** The largest body of an enrollment. */
	public static final int MAX_BODY = 4096;

	/** The enrollment attempts per minute and remote address. */
	public static final int ATTEMPTS_PER_ADDRESS = 10;

	/** The enrollment attempts per minute in total. */
	public static final int ATTEMPTS_TOTAL = 60;

	private static final Pattern SOCKET = Pattern.compile("/([A-Za-z0-9._-]{1,64})/socket");

	private static volatile RateLimit _limit = new RateLimit(ATTEMPTS_PER_ADDRESS, ATTEMPTS_TOTAL, 60_000);

	/**
	 * Replaces the rate limit of the enrollment (for tests).
	 */
	public static void setRateLimit(RateLimit limit) {
		_limit = limit;
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!"/enroll".equals(request.getPathInfo())) {
			error(response, 404, "ehome.not_found", "No such endpoint.");
			return;
		}
		RateLimit limit = _limit;
		if (!limit.allow(request.getRemoteAddr(), System.nanoTime())) {
			response.setHeader("Retry-After", Long.toString(limit.retryAfterSeconds()));
			error(response, 429, "ehome.rate_limited", "Too many enrollment attempts; try again later.");
			return;
		}
		byte[] bytes;
		try (InputStream in = request.getInputStream()) {
			bytes = in.readNBytes(MAX_BODY + 1);
		}
		if (bytes.length > MAX_BODY) {
			error(response, 413, "ehome.too_large", "The request is larger than " + MAX_BODY + " bytes.");
			return;
		}
		Map<String, Object> body;
		try {
			if (!(Json.parse(new String(bytes, StandardCharsets.UTF_8), 4) instanceof Map<?, ?> map)) {
				error(response, 400, Enrollment.BAD_REQUEST, "The request is no JSON object.");
				return;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> object = (Map<String, Object>) map;
			body = object;
		} catch (JsonException ex) {
			error(response, 400, Enrollment.BAD_REQUEST, "The request is no JSON: " + ex.getMessage());
			return;
		}
		String base = base(request);
		Enrollment.Answer answer;
		try {
			answer = ThreadContextManager.inSystemInteraction(ControllerServlet.class,
				() -> {
					ControllerConnections.refetch();
					return Enrollment.enroll(body, base, Instant.now());
				});
		} catch (RuntimeException ex) {
			Logger.error("The enrollment of a controller failed.", ex, ControllerServlet.class);
			error(response, 500, "ehome.internal", "The enrollment failed: " + ex.getMessage());
			return;
		}
		if (answer.status() == 200) {
			Logger.info("Enrolled controller " + answer.body().get("controller") + " for "
				+ answer.body().get("installation") + " from " + request.getRemoteAddr() + ".", ControllerServlet.class);
		}
		write(response, answer.status(), answer.body());
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String path = request.getPathInfo();
		Matcher matcher = path == null ? null : SOCKET.matcher(path);
		if (matcher == null || !matcher.matches()) {
			error(response, 404, "ehome.not_found", "No such endpoint.");
			return;
		}
		String upgrade = request.getHeader("Upgrade");
		if (upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
			// The container's WebSocket implementation takes the upgrades (ControllerSocketRegistration):
			// one that reaches the servlet means the container has none.
			error(response, 501, "ehome.no_websocket", "The server has no WebSocket implementation.");
			return;
		}
		response.setHeader("Upgrade", "websocket");
		error(response, 426, "ehome.upgrade_required", "This is the WebSocket of a controller.");
	}

	/**
	 * The address the controller called E-Home by: <code>scheme://host[:port]/context</code>.
	 */
	static String base(HttpServletRequest request) {
		String scheme = first(request.getHeader("X-Forwarded-Proto"));
		if (scheme == null) {
			scheme = request.getScheme();
		}
		String host = first(request.getHeader("X-Forwarded-Host"));
		if (host == null) {
			host = request.getHeader("Host");
		}
		if (host == null) {
			host = request.getServerName() + ":" + request.getServerPort();
		}
		return scheme.toLowerCase(Locale.ROOT) + "://" + host + request.getContextPath();
	}

	private static String first(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		int comma = header.indexOf(',');
		return (comma < 0 ? header : header.substring(0, comma)).trim();
	}

	private static void error(HttpServletResponse response, int status, String code, String error) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		body.put("code", code);
		write(response, status, body);
	}

	private static void write(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
		byte[] bytes = Json.compact(body).getBytes(StandardCharsets.UTF_8);
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("utf-8");
		response.setHeader("Cache-Control", "no-store");
		response.setContentLength(bytes.length);
		response.getOutputStream().write(bytes);
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

/**
 * The enrollment of a controller for a project (haumacher/ehome #54, homecontrol
 * <code>spec/api.md</code>, "Enrollment").
 *
 * <p>
 * An installer asks for an enrollment code on the project ({@link #createCode(TLObject, Person, Instant)}):
 * eight characters of an alphabet without the look-alikes 0/O, 1/I/L, written <code>ABCD-EFGH</code>,
 * valid {@link #VALIDITY}, one use; asking again replaces the open code. The code is stored with the
 * project (<code>enrollmentCode</code>, <code>enrollmentExpires</code>,
 * <code>enrollmentCreatedBy</code>). The controller presents it to
 * <code>POST /api/controllers/enroll</code> ({@link ControllerServlet}, {@link #enroll(Map, String, Instant)}):
 * E-Home creates the controller under the project with a new id and a new secret of 256 random bits,
 * consumes the code and answers the id, the installation's name, the secret and the URL of the
 * socket. A wrong or used code is 404 <code>ehome.code_unknown</code>, an expired one 410
 * <code>ehome.code_expired</code> (it stays until it is replaced, so it keeps answering 410). A
 * request naming the id of a controller E-Home knows (member <code>controller</code>, sent by a
 * controller that is enrolled already) is 409 <code>ehome.enrolled</code>, and the code stays.
 * </p>
 *
 * <p>
 * The enrollment is serialized in this JVM, so a code is consumed once even by concurrent requests.
 * </p>
 */
public final class Enrollment {

	/** How long a code is valid. */
	public static final Duration VALIDITY = Duration.ofMinutes(15);

	/** The characters of a code: no 0, O, 1, I, L. */
	public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	/** Refusal: no project has the code (wrong, or used). */
	public static final String CODE_UNKNOWN = "ehome.code_unknown";

	/** Refusal: the code has expired. */
	public static final String CODE_EXPIRED = "ehome.code_expired";

	/** Refusal: the controller is enrolled already. */
	public static final String ENROLLED = "ehome.enrolled";

	/** Refusal: the request is malformed. */
	public static final String BAD_REQUEST = "ehome.bad_request";

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Object LOCK = new Object();

	/**
	 * The answer of the enrollment route.
	 *
	 * @param status
	 *        The HTTP status.
	 * @param body
	 *        The JSON body.
	 */
	public record Answer(int status, Map<String, Object> body) {

		static Answer refused(int status, String code, String error) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("error", error);
			body.put("code", code);
			return new Answer(status, body);
		}
	}

	private Enrollment() {
		// Static helpers.
	}

	/**
	 * Creates a new enrollment code for the project, replacing an open one, in a transaction of its
	 * own.
	 *
	 * @param creator
	 *        The user asking for it, <code>null</code> if unknown.
	 * @return The code, as the controller's owner enters it.
	 */
	public static String createCode(TLObject project, Person creator, Instant now) {
		String code = newCode();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project.tUpdateByName("enrollmentCode", code);
			project.tUpdateByName("enrollmentExpires", Date.from(now.plus(VALIDITY)));
			project.tUpdateByName("enrollmentCreatedBy", creator);
			tx.commit();
		}
		return code;
	}

	/**
	 * A new random code <code>ABCD-EFGH</code>.
	 */
	public static String newCode() {
		StringBuilder result = new StringBuilder(9);
		for (int n = 0; n < 8; n++) {
			if (n == 4) {
				result.append('-');
			}
			result.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return result.toString();
	}

	/**
	 * The code as it is stored: upper case, without spaces, with the dash after four characters;
	 * <code>null</code> if the text cannot be a code.
	 */
	public static String normalize(Object text) {
		if (text == null) {
			return null;
		}
		String plain = text.toString().replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (plain.length() != 8) {
			return null;
		}
		for (int n = 0; n < plain.length(); n++) {
			if (ALPHABET.indexOf(plain.charAt(n)) < 0) {
				return null;
			}
		}
		return plain.substring(0, 4) + "-" + plain.substring(4);
	}

	/**
	 * Enrolls a controller (<code>POST /api/controllers/enroll</code>), in a transaction of its own;
	 * runs in a system interaction.
	 *
	 * @param request
	 *        The request body: <code>code</code>, <code>name</code>, <code>version</code>,
	 *        <code>build</code>, optionally <code>controller</code> (the id the controller has from an
	 *        earlier enrollment).
	 * @param base
	 *        The address of E-Home as the controller called it (<code>https://host/path</code>): the
	 *        socket's URL is derived from it.
	 * @param now
	 *        The current time.
	 */
	public static Answer enroll(Map<String, Object> request, String base, Instant now) {
		Object codeText = request.get("code");
		if (codeText == null || codeText.toString().isBlank()) {
			return Answer.refused(400, BAD_REQUEST, "The request has no code.");
		}
		String code = normalize(codeText);
		synchronized (LOCK) {
			TLObject project = code == null ? null : project(code);
			if (project == null) {
				return Answer.refused(404, CODE_UNKNOWN, "The enrollment code is unknown or used.");
			}
			Object expires = project.tValueByName("enrollmentExpires");
			if (!(expires instanceof Date date) || !date.toInstant().isAfter(now)) {
				return Answer.refused(410, CODE_EXPIRED, "The enrollment code has expired; ask for a new one.");
			}
			Object known = request.get("controller");
			if (known != null && !known.toString().isBlank() && ControllerConnections.find(known.toString()) != null) {
				return Answer.refused(409, ENROLLED,
					"The controller is enrolled already; disconnect it from E-Home first.");
			}
			String id = hex(16);
			String secret = hex(32);
			String installation = Objects.toString(project.tValueByName("name"), "");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject controller = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
				controller.tUpdateByName("name", name(request.get("name")));
				controller.tUpdateByName("controllerId", id);
				controller.tUpdateByName("secret", secret);
				controller.tUpdateByName("installation", installation);
				controller.tUpdateByName("firmwareVersion", text(request.get("version")));
				controller.tUpdateByName("firmwareBuild", text(request.get("build")));
				controller.tUpdateByName("enrolled", Date.from(now));
				controller.tUpdateByName("enrolledBy", project.tValueByName("enrollmentCreatedBy"));
				List<Object> controllers = new ArrayList<>();
				if (project.tValueByName("controllers") instanceof Collection<?> before) {
					controllers.addAll(before);
				}
				controllers.add(controller);
				project.tUpdateByName("controllers", controllers);
				if (project.tValueByName("controller") == null) {
					project.tUpdateByName("controller", controller);
				}
				project.tUpdateByName("enrollmentCode", null);
				project.tUpdateByName("enrollmentExpires", null);
				project.tUpdateByName("enrollmentCreatedBy", null);
				tx.commit();
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("controller", id);
			body.put("installation", installation);
			body.put("secret", secret);
			body.put("socket", socketUrl(base, id));
			return new Answer(200, body);
		}
	}

	/**
	 * The URL of the socket of the controller with the given id: the address of E-Home with
	 * <code>ws</code> for <code>http</code> and <code>wss</code> for <code>https</code>.
	 */
	public static String socketUrl(String base, String id) {
		String scheme = base.startsWith("https:") ? "wss:" : "ws:";
		String rest = base.substring(base.indexOf(':') + 1);
		while (rest.endsWith("/")) {
			rest = rest.substring(0, rest.length() - 1);
		}
		return scheme + rest + ControllerServlet.PATH + "/" + id + "/socket";
	}

	/**
	 * The project whose open code is the given one, <code>null</code> if there is none.
	 */
	private static TLObject project(String code) {
		TLClass type = (TLClass) TLModelUtil.findType("ehome.project:Project");
		for (TLObject project : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			if (code.equals(project.tValueByName("enrollmentCode"))) {
				return project;
			}
		}
		return null;
	}

	private static String hex(int bytes) {
		byte[] random = new byte[bytes];
		RANDOM.nextBytes(random);
		return HexFormat.of().formatHex(random);
	}

	private static String name(Object value) {
		String name = text(value);
		if (name == null) {
			return "HomeControl";
		}
		return name.length() > 64 ? name.substring(0, 64) : name;
	}

	private static String text(Object value) {
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		String text = value.toString().strip();
		return text.length() > 64 ? text.substring(0, 64) : text;
	}

}

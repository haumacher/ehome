/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.IntFunction;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.sun.net.httpserver.HttpServer;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;
import de.haumacher.ehome.zigbee.ZigbeeController;
import de.haumacher.ehome.zigbee.ZigbeeExport;

/**
 * The reasons why E-Home refuses a Zigbee export of a controller are whole sentences of the
 * reader's language (haumacher/ehome #173): a file that is no export ({@link ZigbeeExport#parse(String)})
 * and an answer of the controller E-Home cannot read ({@link ZigbeeController#fetch(String, ControllerClient)}).
 * The members of the export stay its names inside the sentence.
 */
@SuppressWarnings("javadoc")
public class TestZigbeeExportReasons extends BasicTestCase {

	private static final String HEAD = "{\"format\": \"homecontrol-zigbee-export\", \"version\": 1, ";

	public void testNoJson() {
		String detail;
		try {
			Json.parse("{", 10);
			throw new AssertionError("Parsed.");
		} catch (JsonException ex) {
			detail = ex.getMessage();
		}
		assertRefused("Die Datei ist kein JSON: Bei Zeichen 1: " + detail,
			"The file is not JSON: At character 1: " + detail, "{");
	}

	public void testExport() {
		assertInvalid("version: Die Version muss eine positive ganze Zahl sein.",
			"version: The version must be a positive integer.",
			"{\"format\": \"homecontrol-zigbee-export\", \"version\": 0}");
		assertInvalid("devices: Die Geräte müssen ein Array sein.", "devices: The devices must be an array.",
			HEAD + "\"devices\": 1}");
		assertInvalid("devices[0]: Ein Gerät muss ein Objekt sein.", "devices[0]: A device must be an object.",
			HEAD + "\"devices\": [1]}");
		assertInvalid("devices[0].ieee: „xyz“ ist keine IEEE-Adresse (16 Hexadezimalziffern, Doppelpunkte erlaubt; nicht"
			+ " 0 und nicht alle Bits gesetzt).", "devices[0].ieee: \"xyz\" is no IEEE address (16 hexadecimal digits,"
				+ " colons allowed; not 0 and not all ones).",
			HEAD + "\"devices\": [{\"ieee\": \"xyz\"}]}");
	}

	public void testDevice() {
		String device = HEAD + "\"devices\": [{\"ieee\": \"00158d0001020304\", ";
		assertInvalid("devices[0].endpoints: Die Endpunkte müssen ein Array sein.",
			"devices[0].endpoints: The endpoints must be an array.", device + "\"endpoints\": 1}]}");
		assertInvalid("devices[0].endpoints[0]: Ein Endpunkt muss ein Objekt sein.",
			"devices[0].endpoints[0]: An endpoint must be an object.", device + "\"endpoints\": [1]}]}");
		assertInvalid("devices[0].endpoints[0].id: Das Element id muss eine ganze Zahl von 1 bis 240 sein.",
			"devices[0].endpoints[0].id: The member id must be an integer from 1 to 240.",
			device + "\"endpoints\": [{\"id\": 241}]}]}");
		assertInvalid("devices[0].endpoints[0].in: Das Element in muss ein Array von Clusternummern sein.",
			"devices[0].endpoints[0].in: The member in must be an array of cluster numbers.",
			device + "\"endpoints\": [{\"id\": 1, \"in\": [\"x\"]}]}]}");
		assertInvalid("devices[0].functions: Die Funktionen müssen ein Array sein.",
			"devices[0].functions: The functions must be an array.", device + "\"functions\": 1}]}");
		assertInvalid("devices[0].functions[0]: Eine Funktion muss ein Objekt sein.",
			"devices[0].functions[0]: A function must be an object.", device + "\"functions\": [1]}]}");
		assertInvalid("devices[0].functions[0].function: Der Schlüssel der Funktion fehlt.",
			"devices[0].functions[0].function: The key of the function is missing.",
			device + "\"functions\": [{}]}]}");
		assertInvalid("devices[0].functions[0].type: „text“ ist kein Werttyp (boolean, unsigned, signed, real, string,"
			+ " timeOfDay, date, dateTime, enumeration, raw, controlled, color).",
			"devices[0].functions[0].type: \"text\" is no value type (boolean, unsigned, signed, real, string,"
				+ " timeOfDay, date, dateTime, enumeration, raw, controlled, color).",
			device + "\"functions\": [{\"function\": \"temperature\", \"endpoint\": 1, \"cluster\": 1026,"
				+ " \"attribute\": 0, \"type\": \"text\"}]}]}");
	}

	public void testBadAnswers() throws IOException {
		assertBadAnswer("Die Geräte sind kein Array.", "The devices are no array.",
			from -> HEAD + "\"devices\": 1}");
		assertBadAnswer("Die nächste Seite 0 folgt nicht auf die Seite 0.", "The next page 0 does not follow the page 0.",
			from -> HEAD + "\"devices\": [], \"next\": 0}");
		assertBadAnswer("Der Export hat mehr als 64 Seiten.", "The export has more than 64 pages.",
			from -> HEAD + "\"devices\": [], \"next\": " + (from + 1) + "}");
	}

	private static void assertInvalid(String de, String en, String text) {
		assertRefused("Der Zigbee-Export ist ungültig bei " + de, "The Zigbee export is invalid at " + en, text);
	}

	private static void assertRefused(String de, String en, String text) {
		try {
			ZigbeeExport.parse(text);
			fail("Not refused: " + text);
		} catch (TopLogicException ex) {
			assertTexts(de, en, ex.getErrorKey());
		}
	}

	private static void assertBadAnswer(String de, String en, IntFunction<String> page) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/zigbee/export", exchange -> {
			String query = exchange.getRequestURI().getQuery();
			int from = Integer.parseInt(query.substring(query.indexOf('=') + 1));
			byte[] body = page.apply(from).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		try {
			ControllerClient client =
				new ControllerClient("127.0.0.1", server.getAddress().getPort(), "token", Duration.ofSeconds(5));
			try {
				ZigbeeController.fetch("Keller", client);
				fail("Not refused.");
			} catch (TopLogicException ex) {
				assertTexts("Der Controller Keller hat eine Antwort gesendet, die E-Home nicht lesen kann: " + de,
					"The controller Keller sent an answer E-Home cannot read: " + en, ex.getErrorKey());
			}
		} finally {
			server.stop(0);
		}
	}

	private static void assertTexts(String de, String en, ResKey key) {
		assertEquals(de, Resources.getInstance(Locale.GERMAN).getString(key));
		assertEquals(en, Resources.getInstance(Locale.ENGLISH).getString(key));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeExportReasons.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.Engine.ParameterStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.I18NConstants;
import de.haumacher.ehome.simulation.ProjectSimulation;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.VirtualZigbee;
import de.haumacher.ehome.zigbee.ZigbeeFunctionTable;

/**
 * The reasons why the simulation refuses a value are whole sentences of the reader's language
 * (haumacher/ehome #173): the refusals of a report of a virtual Zigbee device
 * ({@link VirtualZigbee#report(String, Val, boolean)}, {@link VirtualZigbee#normalize(String, Val)})
 * embedded by {@link I18NConstants#ERROR_ZIGBEE_REPORT_REFUSED__TEXT_DATAPOINT_REASON}, a value that is
 * no literal of the datapoint's type ({@link ProjectSimulation#datapointValue(Simulation, String, String)}),
 * and a refused command or parameter change. The timeline reads them in English.
 */
@SuppressWarnings("javadoc")
public class TestSimulationReasons extends BasicTestCase {

	/** Monday 2026-10-19 06:00 in Berlin (CEST, UTC+2), in seconds. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 4 * 3600;

	private static final String IMAGE = """
		{"image": {"schemaVersion": "1.0", "projectId": "simulation-reasons", "revision": 1},
			"settings": {"tz": "CET-1CEST,M3.5.0,M10.5.0/3"},
			"datapoints": [
			{"id": "hall.button", "type": "enumeration",
				"zigbee": {"ieee": "000b57fffe112233", "endpoint": 1, "cluster": 6, "client": true}},
			{"id": "probe", "type": "boolean", "writable": true, "virtual": {}}]}
		""";

	public void testRanges() {
		assertReason("Eine Helligkeitsstufe nimmt Werte von 0 bis 100 % an.",
			"A level takes values from 0 to 100 %.", refusal("level", Val.ofReal(150)));
		assertReason("Eine Farbtemperatur nimmt Werte von 16 bis 1000000 K an.",
			"A colour temperature takes values from 16 to 1000000 K.", refusal("color_temperature", Val.ofInt(0)));
		assertReason("Eine Temperatur nimmt Werte von -327.67 bis 327.67 °C an.",
			"A temperature takes values from -327.67 to 327.67 °C.", refusal("temperature", Val.ofReal(400)));
		assertReason("Eine Luftfeuchte nimmt Werte von 0 bis 100 % an.",
			"A humidity takes values from 0 to 100 %.", refusal("humidity", Val.ofReal(-1)));
		assertReason("Eine Beleuchtungsstärke nimmt Werte ab 0 Lux an.",
			"An illuminance takes values from 0 lux upwards.", refusal("illuminance", Val.ofReal(-1)));
		assertReason("Eine Beleuchtungsstärke nimmt Werte von 0 bis 3575197.2 Lux an.",
			"An illuminance takes values from 0 to 3575197.2 lux.", refusal("illuminance", Val.ofReal(1e7)));
		assertReason("Ein Batteriestand nimmt Werte von 0 bis 100 % an.",
			"A battery level takes values from 0 to 100 %.", refusal("battery", Val.ofReal(101)));
		assertReason("Ein Mehrfachzustand nimmt die ganzen Zahlen von 0 bis 65535 an.",
			"A multistate value takes the integers from 0 to 65535.", refusal("multistate", Val.ofReal(1.5)));
		assertReason("Eine Aktion nimmt die Werte 0 (off), 1 (on), 2 (toggle), 3 (dim up), 4 (dim down) und 5 (dim stop) an.",
			"An action takes the values 0 (off), 1 (on), 2 (toggle), 3 (dim up), 4 (dim down) and 5 (dim stop).",
			refusal(ZigbeeFunctionTable.ACTION, Val.ofEnum(6)));
	}

	public void testTypes() {
		assertReason("Es ist kein Wert vorhanden.", "There is no value.", refusal("level", Val.none(Type.REAL)));
		assertReason("Die Funktion occupancy nimmt die Werte true und false an.",
			"The function occupancy takes the values true and false.", refusal("occupancy", Val.ofInt(1)));
		assertReason("Die Funktion level nimmt eine Zahl an.", "The function level takes a number.",
			refusal("level", Val.ofBool(true)));
		assertReason("Die Simulation bildet die Funktion pressure nicht nach.",
			"The simulation does not simulate the function pressure.", refusal("pressure", Val.ofReal(1000)));
	}

	public void testReport() {
		VirtualZigbee devices = new VirtualZigbee(null, (kind, source, text) -> fail(text));
		devices.bind(new VirtualZigbee.Point("hall.button", "000b57fffe112233", 1, 6, ZigbeeFunctionTable.ACTION, null));
		assertReason("Der Datenpunkt hall.light ist an kein Zigbee-Gerät gebunden.",
			"The datapoint hall.light is not bound to a Zigbee device.", devices.report("hall.light", Val.ofBool(true), true));

		ResKey press = devices.report("hall.button", Val.ofEnum(1), false);
		assertReason("Ein Tastendruck ist kein Zustand, den das Gerät behält. Drücken Sie die Taste, während die"
			+ " Simulation läuft.", "A press of a button is no state the device keeps. Press the button while the"
				+ " simulation runs.", press);

		ResKey refused = I18NConstants.ERROR_ZIGBEE_REPORT_REFUSED__TEXT_DATAPOINT_REASON.fill("1", "Taster (hall.button)",
			press);
		assertReason("Das Zigbee-Gerät des Datenpunkts Taster (hall.button) kann „1“ nicht melden: Ein Tastendruck ist"
			+ " kein Zustand, den das Gerät behält. Drücken Sie die Taste, während die Simulation läuft.",
			"The Zigbee device of the datapoint Taster (hall.button) cannot report \"1\": A press of a button is no"
				+ " state the device keeps. Press the button while the simulation runs.", refused);
	}

	public void testTimeline() throws Exception {
		Simulation simulation = new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, new ParameterStore());
		try {
			assertNull(simulation.start());
			ResKey refusal = simulation.zigbeeReport("hall.button", Val.ofEnum(6));
			assertEquals(I18NConstants.REASON_ZIGBEE_ACTION_RANGE, refusal);
			Simulation.Entry last = simulation.timeline().get(simulation.timeline().size() - 1);
			assertEquals("report 6 of 'hall.button' refused: An action takes the values 0 (off), 1 (on), 2 (toggle),"
				+ " 3 (dim up), 4 (dim down) and 5 (dim stop).", last.text());
		} finally {
			simulation.close();
		}
	}

	public void testNoLiteral() throws Exception {
		Simulation simulation = new Simulation((Map<?, ?>) Json.parse(IMAGE, 100), START, new ParameterStore());
		try {
			try {
				ProjectSimulation.datapointValue(simulation, "probe", "maybe");
				fail("A text that is no boolean was taken.");
			} catch (TopLogicException ex) {
				assertReason("„maybe“ ist kein Wert des Datenpunkts probe: Ein Wert des Typs boolean wird erwartet.",
					"\"maybe\" is no value of the datapoint probe: A value of the type boolean is expected.",
					ex.getErrorKey());
			}
		} finally {
			simulation.close();
		}
	}

	public void testCommandAndParameter() {
		assertReason("Der Befehl für Licht (0/0/34) wurde abgelehnt: Der Datenpunkt kann nicht geschrieben werden.",
			"The command for Licht (0/0/34) was refused: The datapoint cannot be written.",
			I18NConstants.ERROR_COMMAND_REFUSED__DATAPOINT_REASON.fill("Licht (0/0/34)",
				ProjectSimulation.reason(CommandStatus.NOT_WRITABLE)));
		assertReason("Die Simulation hat keinen solchen Datenpunkt.", "The simulation has no such datapoint.",
			ProjectSimulation.reason(CommandStatus.UNKNOWN_DATAPOINT));
		assertReason("Der Wert passt nicht zum Typ des Datenpunkts.", "The value does not fit the type of the datapoint.",
			ProjectSimulation.reason(CommandStatus.WRONG_TYPE));
		assertReason("Das Gerät des Datenpunkts hat den Wert abgelehnt. Die Zeitleiste zeigt den Grund.",
			"The device of the datapoint refused the value. The timeline shows why.",
			ProjectSimulation.reason(CommandStatus.REFUSED));

		assertReason("Der Wert des Parameters Verzögerung (licht/delay) wurde abgelehnt: Der Parameter ist nicht"
			+ " einstellbar.", "The value of the parameter Verzögerung (licht/delay) was refused: The parameter is not"
				+ " adjustable.", I18NConstants.ERROR_PARAMETER_REFUSED__PARAMETER_REASON.fill("Verzögerung (licht/delay)",
					ProjectSimulation.reason(ParameterStatus.NOT_ADJUSTABLE)));
		assertReason("Die Simulation hat keine solche Regel.", "The simulation has no such rule.",
			ProjectSimulation.reason(ParameterStatus.UNKNOWN_RULE));
		assertReason("Die Regel hat keinen solchen Parameter.", "The rule has no such parameter.",
			ProjectSimulation.reason(ParameterStatus.UNKNOWN_PARAMETER));
		assertReason("Der Wert ist kein gültiger Wert des Parameters.", "The value is no valid value of the parameter.",
			ProjectSimulation.reason(ParameterStatus.INVALID_VALUE));
	}

	private static ResKey refusal(String function, Val value) {
		VirtualZigbee.Normalized normalized = VirtualZigbee.normalize(function, value);
		assertNull(normalized.value());
		return normalized.refusal();
	}

	private static void assertReason(String de, String en, ResKey reason) {
		assertNotNull(reason);
		assertEquals(de, Resources.getInstance(Locale.GERMAN).getString(reason));
		assertEquals(en, Resources.getInstance(Locale.ENGLISH).getString(reason));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestSimulationReasons.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

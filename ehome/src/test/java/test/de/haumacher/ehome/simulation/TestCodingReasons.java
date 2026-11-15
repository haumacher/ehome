/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.simulation.I18NConstants;
import de.haumacher.ehome.simulation.KnxValues;
import de.haumacher.ehome.simulation.KnxValues.CodingException;

/**
 * The reasons why a value of the rules or the data of a telegram cannot be coded
 * ({@link KnxValues.CodingException}) are whole sentences of the reader's language, embedded by the
 * refusal of a value sent in the simulation (haumacher/ehome #171); the log of the controller's port
 * reads them in English.
 */
@SuppressWarnings("javadoc")
public class TestCodingReasons extends BasicTestCase {

	public void testEncode() {
		CodingException noNumber = encodeRefused("9.001", Val.ofBool(true));
		assertEquals("Eine Zahl ist erforderlich, nicht true.", de(noNumber.getReason()));
		assertEquals("A number is required, not true.", en(noNumber.getReason()));
		assertEquals(en(noNumber.getReason()), noNumber.getMessage());

		CodingException range = encodeRefused("9.001", Val.ofReal(1e9));
		assertEquals("Der Wert liegt außerhalb des Wertebereichs des Datenpunkttyps 9.001.", de(range.getReason()));
		assertEquals("The value lies outside the range of the datapoint type 9.001.", en(range.getReason()));

		CodingException type = encodeRefused("1.001", Val.ofInt(1));
		assertEquals("Ein Wert des Typs boolean ist erforderlich, nicht 1.", de(type.getReason()));
		assertEquals("A value of the type boolean is required, not 1.", en(type.getReason()));

		ResKey refused = I18NConstants.ERROR_INVALID_VALUE__TEXT_DATAPOINT_DETAIL.fill("1000000000",
			"Raumtemperatur (0/0/28)", range.getReason());
		assertEquals("„1000000000“ ist kein Wert des Datenpunkts Raumtemperatur (0/0/28): Der Wert liegt außerhalb"
			+ " des Wertebereichs des Datenpunkttyps 9.001.", de(refused));
		assertEquals("\"1000000000\" is no value of the datapoint Raumtemperatur (0/0/28): The value lies outside"
			+ " the range of the datapoint type 9.001.", en(refused));
	}

	public void testDecode() {
		CodingException length = decodeRefused("9.001", new byte[] { 1 });
		assertEquals("Die Daten haben nicht die Länge des Datenpunkttyps 9.001.", de(length.getReason()));
		assertEquals("The data does not have the length of the datapoint type 9.001.", en(length.getReason()));
	}

	private static CodingException encodeRefused(String dpt, Val value) {
		try {
			KnxValues.encode(Dpt.parse(dpt), value);
		} catch (CodingException ex) {
			return ex;
		}
		throw new AssertionError("Not refused: " + value + " as " + dpt);
	}

	private static CodingException decodeRefused(String dpt, byte[] data) {
		try {
			KnxValues.decode(Dpt.parse(dpt), data);
		} catch (CodingException ex) {
			return ex;
		}
		throw new AssertionError("Not refused as " + dpt);
	}

	private static String de(ResKey key) {
		return Resources.getInstance(Locale.GERMAN).getString(key);
	}

	private static String en(ResKey key) {
		return Resources.getInstance(Locale.ENGLISH).getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestCodingReasons.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

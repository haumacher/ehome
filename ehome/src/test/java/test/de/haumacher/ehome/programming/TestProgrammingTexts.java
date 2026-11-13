/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import java.util.List;
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

import de.haumacher.ehome.programming.ParameterEncoding;
import de.haumacher.ehome.programming.ValueException;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.CompareJob;
import de.haumacher.ehome.programming.download.I18NConstants;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.scan.FoundDevice;

/**
 * The texts of the programming tab, the comparison and download reports and the scan in both
 * languages (haumacher/ehome #161): whole sentences per language with the technical values (hex,
 * object and property numbers, addresses) as data, and numbers in the reader's format.
 */
@SuppressWarnings("javadoc")
public class TestProgrammingTexts extends BasicTestCase {

	private static String de(ResKey key) {
		return Resources.getInstance(Locale.GERMAN).getString(key);
	}

	private static String en(ResKey key) {
		return Resources.getInstance(Locale.ENGLISH).getString(key);
	}

	public void testComparisonReport() {
		ResKey descriptor = I18NConstants.REPORT_DESCRIPTOR__VALUE.fill("07 05");
		assertEquals("Device descriptor (mask version): 07 05", en(descriptor));
		assertEquals("Gerätebeschreibung (Maskenversion): 07 05", de(descriptor));

		ResKey equal = I18NConstants.REPORT_BLOCK_EQUAL__COUNT.fill(626);
		assertEquals("626 bytes compared, all equal.", en(equal));
		assertEquals("626 Byte verglichen, alle gleich.", de(equal));

		ResKey state = I18NConstants.FACT_LOAD_STATE__LSM_STATE.fill(1, LoadControls.stateName(LoadControls.STATE_LOADED));
		assertEquals("Load state machine 1: Loaded", en(state));
		assertEquals("Ladezustandsmaschine 1: Geladen", de(state));

		ResKey where = CompareJob.blockRange(de.haumacher.ehome.programming.I18NConstants.BLOCK_ADDRESS_TABLE, 0x4000,
			0x4011);
		assertEquals("Group address table, 4000-4010", en(where));
		assertEquals("Gruppenadresstabelle, 4000-4010", de(where));
	}

	public void testOperations() {
		Operation write = new Operation.WriteMemory("", de.haumacher.ehome.programming.I18NConstants.BLOCK_ASSOCIATION_TABLE,
			0x4100, new byte[] { 0x01, 0x02 }, true);
		assertEquals("Write memory 4100-4101 (2 bytes, Association table), verified: 01 02", en(write.description()));
		assertEquals("Speicher 4100-4101 schreiben (2 Byte, Assoziationstabelle), mit Prüfung: 01 02",
			de(write.description()));
		assertEquals(en(write.description()), write.describe());

		Operation check = new Operation.CheckMask("", new int[] { 0x0705 });
		assertEquals("Check that the device has the mask version 0705", en(check.description()));
		assertEquals("Prüfen, dass das Gerät die Maskenversion 0705 hat", de(check.description()));

		Operation control = new Operation.LoadControl("", 1, 4, LoadControls.event(LoadControls.EVENT_LOAD),
			LoadControls.STATE_LOADING);
		assertEquals(
			"Ladesteuerung der Ladezustandsmaschine 1 (Objekt 4): Laden beginnen [01 00 00 00 00 00 00 00 00 00]; "
				+ "erwarteter Ladezustand: Wird geladen",
			de(control.description()));
	}

	public void testUnreadableProperty() {
		BusException failure = new BusException(
			I18NConstants.ACCESS_READ_PROPERTY_REFUSED__OBJECT_PROPERTY_DEVICE_PATH.fill(3, 13, "1.3.1",
				"Controller homecontrol-f883ec"));
		ResKey note = de.haumacher.ehome.scan.I18NConstants.NOTE__LABEL_VALUE
			.fill(de.haumacher.ehome.scan.I18NConstants.READ_PROGRAM_VERSION, failure.getErrorKey());
		assertEquals("Programmversion: Das Lesen von Objekt 3 Eigenschaft 13 von 1.3.1 über Controller homecontrol-f883ec "
			+ "ist fehlgeschlagen: Das Gerät verweigert den Zugriff (failed/forbidden).", de(note));
		assertEquals("Reading object 3 property 13 of 1.3.1 through Controller homecontrol-f883ec failed: the device "
			+ "refuses the access (failed/forbidden).", failure.getMessage());
	}

	public void testScanTexts() {
		ResKey found = de.haumacher.ehome.scan.I18NConstants.MESSAGE_FOUND__ADDRESS.fill(FoundDevice.formatAddress(0x1332));
		assertEquals("Found 1.3.50.", en(found));
		assertEquals("1.3.50 gefunden.", de(found));

		ResKey led = de.haumacher.ehome.scan.I18NConstants.LED_DONE_ON__ADDRESS_GATEWAY_REPORTED.fill("1.3.5",
			"Controller c", de.haumacher.ehome.scan.I18NConstants.LED_ON);
		assertEquals("Programmiermodus von 1.3.5 über Controller c eingeschaltet; das Gerät meldet: ein.", de(led));

		ResKey states = de.haumacher.ehome.scan.I18NConstants.NOTE__LABEL_VALUE.fill(
			de.haumacher.ehome.scan.I18NConstants.READ_RUN_STATE, de.haumacher.ehome.scan.I18NConstants.RUN_STATE_RUNNING);
		assertEquals("Laufzustand: Läuft", de(states));

		ResKey readBack = de.haumacher.ehome.scan.I18NConstants.READBACK_EQUAL__COUNT.fill(626);
		assertEquals("626 Byte des Download-Abbilds mit dem gelesenen Speicher verglichen, alle gleich.", de(readBack));
	}

	/**
	 * The duration of a download in the reader's number format: "49,7 s" in German.
	 */
	public void testDurationFormat() {
		ResKey done = I18NConstants.DOWNLOAD_DONE__DEVICE_GATEWAY_COUNT_SECONDS.fill("1.3.5", "Controller c", 79,
			Double.valueOf(49.73));
		assertEquals("Download in 1.3.5 über Controller c: 79 Operationen in 49,7 s ausgeführt.", de(done));
		assertEquals("Download into 1.3.5 through Controller c: 79 operations done in 49.7 s.", en(done));
		ResKey grouped = I18NConstants.DOWNLOAD_DONE__DEVICE_GATEWAY_COUNT_SECONDS.fill("1.3.5", "Controller c", 1649,
			Double.valueOf(1234.5));
		assertEquals("Download in 1.3.5 über Controller c: 1.649 Operationen in 1234,5 s ausgeführt.", de(grouped));
	}

	/**
	 * The problems of the download image, shown in the refusal of the download and in the report of a
	 * read-back, with the reason of a value the encoding refuses.
	 */
	public void testImageProblems() {
		ResKey overlap = de.haumacher.ehome.programming.I18NConstants.IMAGE_OVERLAP__TABLE_FROM_TO_OTHER_FROM_TO.fill(
			de.haumacher.ehome.programming.I18NConstants.BLOCK_ADDRESS_TABLE, "4000", "4010",
			de.haumacher.ehome.programming.I18NConstants.BLOCK_ASSOCIATION_TABLE, "4010", "4020");
		assertEquals("Die Tabelle „Gruppenadresstabelle“ (4000-4010) überschneidet sich mit der Tabelle "
			+ "„Assoziationstabelle“ (4010-4020).", de(overlap));
		ResKey refused = I18NConstants.ERROR_IMAGE__PROBLEM.fill(overlap);
		assertTrue(de(refused), de(refused).endsWith(de(overlap)));

		ValueException encoding;
		try {
			ParameterEncoding.unsigned(300, 8);
			fail("Expected a value refused.");
			return;
		} catch (ValueException ex) {
			encoding = ex;
		}
		ResKey parameter = de.haumacher.ehome.programming.I18NConstants.IMAGE_PARAMETER__PARAMETER_REASON.fill(
			"Helligkeit (M-0083_A-0001-01-0000_P-1)", encoding.getErrorKey());
		assertEquals("Parameter Helligkeit (M-0083_A-0001-01-0000_P-1): Der Wert 300 passt nicht in 8 Bit.", de(parameter));
		assertEquals("The value 300 does not fit into 8 bits.", encoding.getMessage());
	}

	/**
	 * Every load state and load control has a German text of its own.
	 */
	public void testStatesTranslated() {
		for (int state : List.of(LoadControls.STATE_UNLOADED, LoadControls.STATE_LOADED, LoadControls.STATE_LOADING,
			LoadControls.STATE_ERROR, LoadControls.STATE_UNLOADING, LoadControls.STATE_LOAD_COMPLETING)) {
			ResKey name = LoadControls.stateName(state);
			assertFalse(en(name), en(name).equals(de(name)));
		}
		for (int event : List.of(LoadControls.EVENT_LOAD, LoadControls.EVENT_LOAD_COMPLETED, LoadControls.EVENT_UNLOAD)) {
			ResKey name = LoadControls.describe(LoadControls.event(event));
			assertFalse(en(name), en(name).equals(de(name)));
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProgrammingTexts.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

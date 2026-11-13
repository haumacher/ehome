/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ImportEtsProjectJob;
import de.haumacher.ehome.etsImport.ImportProductDataJob;
import de.haumacher.ehome.etsImport.importer.format.UnsignedIntFormat;
import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.ParameterEncoding;

/**
 * Test case for the value forms of Theben's complete product database (haumacher/ehome #160), with
 * the artificial product file <code>TestValueForms.knxprod</code> (made by
 * <code>src/test/fixtures/make-fixtures.py</code>): an enumeration entry whose value ETS writes as an
 * unsigned 32-bit number standing for a negative one (<code>4294967236</code> for -60 in 8 bits), and a
 * rename of a separator of the parameter dialog, which E-Home does not import.
 *
 * <p>
 * The file holds its two programs before their hardware, so its import resolves the references of the
 * hardware to programs read in later steps. The tests share one import, through the job of the
 * catalog, which commits each program in a transaction of its own.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestValueForms extends BasicTestCase {

	private static final String FILE = "src/test/fixtures/TestValueForms.knxprod";

	private static final String PROGRAM = "M-0008_A-FFB3-01-0000";

	private static final String SECOND = "M-0008_A-FFB4-01-0000";

	private static final String PROGRAM_TYPE = "ehome.operational:Program";

	/** The report of the first import of the file, shared by the tests. */
	private static Map<?, ?> _report;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		if (_report == null) {
			assertTrue("Fixture already imported.", withExtId(PROGRAM_TYPE, PROGRAM).isEmpty());
			_report = importAction();
		}
	}

	/**
	 * An entry written as an unsigned 32-bit number keeps its 32 bits: it reads as the negative number,
	 * is written back as ETS writes it, and fills its field of 8 bits with the lowest bits.
	 */
	public void testUnsignedEnumerationValue() throws Exception {
		Program program = (Program) byExtId(PROGRAM_TYPE, PROGRAM);
		Parameter parameter = (Parameter) byExtId("ehome.operational:Parameter", PROGRAM + "_P-9");
		EnumerationType type = (EnumerationType) parameter.getType();
		EnumerationValue minus60 = entry(type, PROGRAM + "_PT-Versatz_EN-4294967236");
		EnumerationValue five = entry(type, PROGRAM + "_PT-Versatz_EN-5");

		assertEquals(-60, minus60.getValue());
		assertEquals(4294967236L, minus60.getUnsignedValue());
		assertEquals(BigInteger.valueOf(0xC4), minus60.valueInBits(8));
		assertEquals(BigInteger.valueOf(5), five.valueInBits(8));
		assertTrue(minus60.hasValue(4294967236L));
		assertTrue(minus60.hasValue(-60));
		assertFalse(minus60.hasValue(196));

		// The default of the parameter is the entry, written back as ETS writes it.
		assertSame(minus60, ParameterValues.value(parameter));
		assertEquals("4294967236", ParameterValues.encode(parameter));

		// Its bits in the device: -60 as a signed byte.
		assertEquals(new ParameterEncoding.Bits(8, BigInteger.valueOf(0xC4)),
			ParameterEncoding.encode(type, parameter, program));
		assertEquals(new ParameterEncoding.Bits(8, BigInteger.valueOf(0xC4)),
			ParameterEncoding.encode(type, "4294967236", program));
		assertEquals(new ParameterEncoding.Bits(8, BigInteger.valueOf(5)),
			ParameterEncoding.encode(type, "5", program));
	}

	/**
	 * The format of the import reads the whole range of <code>xs:unsignedInt</code>, and refuses what
	 * lies outside.
	 */
	public void testUnsignedIntFormat() throws Exception {
		UnsignedIntFormat format = new UnsignedIntFormat();
		assertEquals(Integer.valueOf(-60), format.getValue("Value", "4294967236"));
		assertEquals(Integer.valueOf(-1), format.getValue("Value", "4294967295"));
		assertEquals(Integer.valueOf(Integer.MAX_VALUE), format.getValue("Value", "2147483647"));
		assertEquals(Integer.valueOf(-5), format.getValue("Value", "-5"));
		assertEquals("4294967236", format.getSpecification(Integer.valueOf(-60)));
		for (String invalid : List.of("4294967296", "-2147483649", "18d")) {
			try {
				format.getValue("Value", invalid);
				fail("Accepted: " + invalid);
			} catch (ConfigurationException ex) {
				// Expected.
			}
		}
	}

	/**
	 * A rename of a separator of the parameter dialog (which E-Home does not import) is left out with
	 * a warning naming it and its target; the rename of the block in the same case is imported.
	 */
	public void testRenameOfSeparator() throws Exception {
		assertTrue(withExtId("ehome.operational:Rename", PROGRAM + "_PR-1").isEmpty());
		TLObject rename = byExtId("ehome.operational:Rename", PROGRAM + "_PR-2");
		assertSame(byExtId("ehome.operational:ParameterGroup", PROGRAM + "_PB-1"), rename.tValueByName("target"));

		assertEquals(List.of(Resources.getInstance().getString(
			I18NConstants.WARN_RENAME_TARGET_NOT_IMPORTED__RENAME_TARGET.fill(PROGRAM + "_PR-1", PROGRAM + "_PS-1"))),
			messages(_report));
		assertEquals("The rename " + PROGRAM + "_PR-1 is left out: it names " + PROGRAM + "_PS-1, which E-Home does "
			+ "not import (a separator of the parameter dialog, or an element the program does not hold).",
			Resources.getInstance(Locale.ENGLISH).getString(
				I18NConstants.WARN_RENAME_TARGET_NOT_IMPORTED__RENAME_TARGET.fill(PROGRAM + "_PR-1", PROGRAM + "_PS-1")));
	}

	/**
	 * The programs, read in steps after their hardware, are imported, and the hardware runs them; a
	 * second import of the file adds nothing.
	 */
	public void testProgramsAfterHardware() throws Exception {
		TLObject program = byExtId(PROGRAM_TYPE, PROGRAM);
		TLObject second = byExtId(PROGRAM_TYPE, SECOND);
		assertSame(program, byExtId("ehome.catalog:ProgramAssignment", "M-0008_H-TEST.20WERTFORMEN-1_HP-FFB3-01-0000")
			.tValueByName("program"));
		assertSame(second, byExtId("ehome.catalog:ProgramAssignment", "M-0008_H-TEST.20SCHRITTE-1_HP-FFB4-01-0000")
			.tValueByName("program"));
		assertSame(byExtId("ehome.catalog:Manufacturer", "M-0008"), program.tContainer());
		assertSame(byExtId("ehome.catalog:Manufacturer", "M-0008"), second.tContainer());

		int parameters = instances("ehome.operational:Parameter").size();
		int programs = instances(PROGRAM_TYPE).size();
		Map<?, ?> again = importAction();
		assertEquals(List.of(), messages(again));
		assertEquals(parameters, instances("ehome.operational:Parameter").size());
		assertEquals(programs, instances(PROGRAM_TYPE).size());
		String summary = (String) again.get(ImportEtsProjectJob.SUMMARY);
		assertFalse(summary, summary.contains(PROGRAM));
	}

	private static EnumerationValue entry(EnumerationType type, String extId) {
		for (EnumerationValue value : type.getValues()) {
			if (extId.equals(value.getExtId())) {
				return value;
			}
		}
		fail("No entry " + extId);
		return null;
	}

	/**
	 * The messages of the given report without their location, and without those about the master
	 * data, which depend on the imports before.
	 */
	static List<String> messages(Map<?, ?> report) {
		List<String> result = new ArrayList<>();
		for (Object message : (Collection<?>) report.get(ImportEtsProjectJob.MESSAGES)) {
			String text = String.valueOf(((Map<?, ?>) message).get(ImportEtsProjectJob.MESSAGE));
			if (text.contains("master data") || text.contains("Stammdaten")) {
				continue;
			}
			result.add(text.replaceFirst("^In knxproj:[^:]*: ", ""));
		}
		return result;
	}

	private static Map<?, ?> importAction() throws Exception {
		ImportProductDataJob job = new ImportProductDataJob(null, null);
		File source = new File(FILE);
		return (Map<?, ?>) new RecordingJobMonitor().run(job,
			List.of(BinaryDataFactory.createBinaryDataWithName(source, source.getName())));
	}

	static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = withExtId(typeName, extId);
		assertEquals(typeName + " " + extId, 1, result.size());
		return result.get(0);
	}

	static List<TLObject> withExtId(String typeName, String extId) {
		return instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestValueForms.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.w3c.dom.Document;

import de.haumacher.ehome.importer.ModuleExpansion;

/**
 * The expansion of dynamic modules (haumacher/ehome #119, {@link ModuleExpansion}) on small artificial
 * program files, without a database: the constructs it refuses (<code>Repeat</code>, an argument of an
 * <code>Allocator</code>, <code>SubModuleDefs</code>) leave the file as it is; the IDs, numbers,
 * offsets and texts of an expanded instance (the import of an expanded program is
 * {@link TestDynamicModules}).
 */
@SuppressWarnings("javadoc")
public class TestModuleExpansion extends TestCase {

	private static final String P = "M-0008_A-FFF0-01-0000";

	private static final String MODULE = "<ModuleDefs><ModuleDef Id=\"" + P + "_MD-1\" Name=\"Kanal\"><Arguments>"
		+ "<Argument Id=\"" + P + "_MD-1_A-1\" Name=\"Offset\"/><Argument Id=\"" + P + "_MD-1_A-2\" Name=\"Nummer\"/>"
		+ "<Argument Id=\"" + P + "_MD-1_A-3\" Name=\"ChNo\"/></Arguments><Static>"
		+ "<Parameters><Parameter Id=\"" + P + "_MD-1_P-1\" Text=\"{{ChNo}}: Modus\" ParameterType=\"" + P + "_PT-1\">"
		+ "<Memory CodeSegment=\"" + P + "_AS-1\" Offset=\"28\" BitOffset=\"2\" BaseOffset=\"" + P + "_MD-1_A-1\"/>"
		+ "</Parameter></Parameters>"
		+ "<ComObjects><ComObject Id=\"" + P + "_MD-1_O-2-0\" Text=\"Kanal {{ChNo}}: {{0}}\" Number=\"0\" "
		+ "BaseNumber=\"" + P + "_MD-1_A-2\"/></ComObjects></Static>"
		+ "<Dynamic><Channel Id=\"" + P + "_MD-1_CH-1\" Text=\"Kanal {{ChNo}}\"><ParameterBlock Id=\"" + P
		+ "_MD-1_PB-1\"><ParameterRefRef RefId=\"" + P + "_MD-1_P-1_R-1\"/></ParameterBlock></Channel></Dynamic>"
		+ "</ModuleDef></ModuleDefs>";

	private static final String ARGUMENTS = "<NumericArg RefId=\"" + P + "_MD-1_A-1\" Value=\"72\"/>"
		+ "<NumericArg RefId=\"" + P + "_MD-1_A-2\" Value=\"12\"/><TextArg RefId=\"" + P + "_MD-1_A-3\" Value=\"B\"/>";

	public void testExpansion() throws Exception {
		Document document = ModuleExpansion.parse(program(MODULE, "<Module Id=\"" + P + "_MD-1_M-2\" RefId=\"" + P
			+ "_MD-1\">" + ARGUMENTS + "</Module>", ""));
		List<ModuleExpansion.Result> results = ModuleExpansion.expand(document);
		assertEquals(1, results.size());
		assertTrue(results.get(0).isExpanded());
		assertEquals(P, results.get(0).getProgram());
		String expanded = new String(ModuleExpansion.serialize(document), StandardCharsets.UTF_8);
		assertFalse(expanded, expanded.contains("ModuleDefs"));
		assertFalse(expanded, expanded.contains("<Module "));
		String i = P + "_MD-1_M-2_MI-1_";
		// The example of the issue: object 0 + 12, byte 28 + 72, the bit offset kept.
		assertTrue(expanded, expanded.contains("Id=\"" + i + "O-2-0\""));
		assertTrue(expanded, expanded.contains("Number=\"12\""));
		assertTrue(expanded, expanded.contains("Offset=\"100\""));
		assertTrue(expanded, expanded.contains("BitOffset=\"2\""));
		assertFalse(expanded, expanded.contains("BaseOffset"));
		assertFalse(expanded, expanded.contains("BaseNumber"));
		// The text arguments substituted, the placeholder of a text parameter kept.
		assertTrue(expanded, expanded.contains("Text=\"Kanal B: {{0}}\""));
		assertTrue(expanded, expanded.contains("Text=\"B: Modus\""));
		// References within the module renamed, those to the program kept.
		assertTrue(expanded, expanded.contains("RefId=\"" + i + "P-1_R-1\""));
		assertTrue(expanded, expanded.contains("CodeSegment=\"" + P + "_AS-1\""));
		assertTrue(expanded, expanded.contains("ParameterType=\"" + P + "_PT-1\""));
		// The static parts in the program's sections (ComObjects of a module: ComObjectTable).
		assertTrue(expanded, expanded.contains("<ComObjectTable><" + ModuleExpansion.WRAPPER + ">"));
		assertTrue(expanded, expanded.contains("<Parameters><" + ModuleExpansion.WRAPPER + ">"));
	}

	public void testRepeat() throws Exception {
		assertRefused("Repeat", program(MODULE, "<Repeat Id=\"" + P + "_RP-1\" Count=\"2\"><Module Id=\"" + P
			+ "_MD-1_M-1\" RefId=\"" + P + "_MD-1\">" + ARGUMENTS + "</Module></Repeat>", ""));
	}

	public void testAllocator() throws Exception {
		assertRefused("Allocator", program(MODULE, "<Module Id=\"" + P + "_MD-1_M-1\" RefId=\"" + P + "_MD-1\">"
			+ "<NumericArg RefId=\"" + P + "_MD-1_A-1\" AllocatorRefId=\"" + P + "_L-1\"/></Module>",
			"<Allocators><Allocator Id=\"" + P + "_L-1\" Name=\"Test\" Start=\"0\" maxInclusive=\"10\"/></Allocators>"));
	}

	public void testSubModules() throws Exception {
		String nested = MODULE.replace("</ModuleDef></ModuleDefs>",
			"<SubModuleDefs><ModuleDef Id=\"" + P + "_MD-1_SM-1\" Name=\"Sub\"/></SubModuleDefs></ModuleDef></ModuleDefs>");
		assertRefused("SubModuleDefs", program(nested, "<Module Id=\"" + P + "_MD-1_M-1\" RefId=\"" + P + "_MD-1\">"
			+ ARGUMENTS + "</Module>", ""));
	}

	public void testWithoutModules() {
		assertFalse(ModuleExpansion.mentionsModules(program("", "", "")));
	}

	private static void assertRefused(String construct, byte[] file) throws Exception {
		assertTrue(ModuleExpansion.mentionsModules(file));
		Document document = ModuleExpansion.parse(file);
		String before = new String(ModuleExpansion.serialize(document), StandardCharsets.UTF_8);
		List<ModuleExpansion.Result> results = ModuleExpansion.expand(document);
		assertEquals(1, results.size());
		assertFalse(results.get(0).isExpanded());
		assertEquals(construct, results.get(0).getConstruct());
		assertEquals(before, new String(ModuleExpansion.serialize(document), StandardCharsets.UTF_8));
	}

	private static byte[] program(String modules, String dynamic, String statics) {
		return ("<KNX xmlns=\"http://knx.org/xml/project/23\"><ManufacturerData><Manufacturer RefId=\"M-0008\">"
			+ "<ApplicationPrograms><ApplicationProgram Id=\"" + P + "\"><Static><Parameters/>" + statics + "</Static>"
			+ modules + "<Dynamic>" + dynamic + "</Dynamic></ApplicationProgram></ApplicationPrograms>"
			+ "</Manufacturer></ManufacturerData></KNX>").getBytes(StandardCharsets.UTF_8);
	}

	public static Test suite() {
		return new TestSuite(TestModuleExpansion.class);
	}

}

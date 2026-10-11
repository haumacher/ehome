/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.etsImport.importer.format;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.top_logic.basic.config.ConfigurationException;

import de.haumacher.ehome.etsImport.importer.format.ObjectSizeFormat;

/**
 * Test of {@link ObjectSizeFormat}: the size of a communication object read from and written as
 * ETS writes it, and the same text as the display of the size with its unit.
 */
@SuppressWarnings("javadoc")
public class TestObjectSizeFormat extends TestCase {

	public void testText() {
		assertEquals("1 Bit", ObjectSizeFormat.text(Integer.valueOf(1)));
		assertEquals("4 Bit", ObjectSizeFormat.text(Integer.valueOf(4)));
		assertEquals("1 Byte", ObjectSizeFormat.text(Integer.valueOf(8)));
		assertEquals("2 Bytes", ObjectSizeFormat.text(Integer.valueOf(16)));
		assertEquals("14 Bytes", ObjectSizeFormat.text(Integer.valueOf(112)));
		assertEquals("12 Bit", ObjectSizeFormat.text(Integer.valueOf(12)));
		assertNull(ObjectSizeFormat.text(null));
	}

	public void testRoundTrip() throws ConfigurationException {
		ObjectSizeFormat format = new ObjectSizeFormat();
		for (int bits : new int[] { 1, 2, 3, 4, 6, 7, 8, 16, 24, 32, 48, 64, 80, 112 }) {
			String text = format.getSpecification(Integer.valueOf(bits));
			assertEquals(text, ObjectSizeFormat.text(Integer.valueOf(bits)));
			assertEquals(Integer.valueOf(bits), format.getValue("size", text));
		}
	}

	public static Test suite() {
		return new TestSuite(TestObjectSizeFormat.class);
	}

}

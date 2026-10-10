/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;

/**
 * Test case for {@link GroupAddressStyle} and {@link GA}.
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressStyle extends TestCase {

	private static final int VALUE = 8 << 11 | 0 << 8 | 9;

	public void testFormat() {
		assertEquals("8/0/9", GroupAddressStyle.ThreeLevel.format(VALUE));
		assertEquals("8/9", GroupAddressStyle.TwoLevel.format(VALUE));
		assertEquals("16393", GroupAddressStyle.Free.format(VALUE));
		assertEquals("31/7/255", GroupAddressStyle.ThreeLevel.format(0xFFFF));
		assertEquals("31/2047", GroupAddressStyle.TwoLevel.format(0xFFFF));
	}

	public void testParse() {
		assertEquals(VALUE, GroupAddressStyle.ThreeLevel.parse("8/0/9"));
		assertEquals(VALUE, GroupAddressStyle.ThreeLevel.parse(" 8 / 0 / 9 "));
		assertEquals(VALUE, GroupAddressStyle.TwoLevel.parse("8/9"));
		assertEquals(VALUE, GroupAddressStyle.Free.parse("16393"));
	}

	public void testParseAny() {
		assertEquals(VALUE, GroupAddressStyle.parseAny("8/0/9"));
		assertEquals(VALUE, GroupAddressStyle.parseAny("8/9"));
		assertEquals(VALUE, GroupAddressStyle.parseAny("16393"));
	}

	public void testInvalid() {
		assertInvalid(GroupAddressStyle.ThreeLevel, "8/9");
		assertInvalid(GroupAddressStyle.ThreeLevel, "8/8/9");
		assertInvalid(GroupAddressStyle.ThreeLevel, "32/0/0");
		assertInvalid(GroupAddressStyle.ThreeLevel, "1/2/256");
		assertInvalid(GroupAddressStyle.TwoLevel, "1/2048");
		assertInvalid(GroupAddressStyle.Free, "65536");
		assertInvalid(GroupAddressStyle.Free, "-1");
		assertInvalid(GroupAddressStyle.Free, "abc");
	}

	private static void assertInvalid(GroupAddressStyle style, String text) {
		try {
			style.parse(text);
			fail("'" + text + "' accepted as " + style);
		} catch (TopLogicException ex) {
			// Expected.
		}
	}

	public void testGA() {
		GA threeLevel = new GA(VALUE, GroupAddressStyle.ThreeLevel);
		GA twoLevel = threeLevel.withStyle(GroupAddressStyle.TwoLevel);
		assertEquals("8/0/9", threeLevel.toString());
		assertEquals("8/9", twoLevel.toString());
		assertEquals("Equality ignores the notation.", threeLevel, twoLevel);
		assertEquals(threeLevel.hashCode(), twoLevel.hashCode());
		assertEquals("Unknown notation is written three-level.", "8/0/9", new GA(VALUE, null).toString());
		assertTrue(new GA(1, null).compareTo(new GA(2, null)) < 0);
		assertEquals(VALUE, GA.parse("8/9", null).getValue());
	}

	public static Test suite() {
		return new TestSuite(TestGroupAddressStyle.class);
	}

}

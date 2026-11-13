/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.knx.ProgramResources;

/**
 * Probe of the help texts of a program as HTML: what a manufacturer writes in a topic must never
 * reach the page as markup or as a link to anything but the web or a mail address.
 */
@SuppressWarnings("javadoc")
public class TestProgramResourcesProbe extends TestCase {

	public void testRawHtmlEscaped() {
		String html = ProgramResources.html("Text <script>alert(1)</script> <b onclick=\"x()\">b</b>");
		assertFalse(html, html.contains("<script"));
		assertFalse(html, html.contains("<b "));
	}

	public void testScriptLinks() {
		for (String target : new String[] {
			"javascript:alert(1)", "JaVaScRiPt:alert(1)", "&#106;avascript:alert(1)",
			"java%0ascript:alert(1)", "vbscript:x", "data:text/html,<script>alert(1)</script>",
			"file:///etc/passwd" }) {
			String html = ProgramResources.html("[x](" + target + ")");
			String lower = html.toLowerCase();
			assertFalse(target + " -> " + html, lower.contains("script:"));
			assertFalse(target + " -> " + html, lower.contains("href=\"data:"));
			assertFalse(target + " -> " + html, lower.contains("href=\"file:"));
		}
		String auto = ProgramResources.html("<javascript:alert(1)>");
		assertFalse(auto, auto.toLowerCase().contains("href=\"javascript"));
	}

	public void testWebAndMailLinksKept() {
		assertTrue(ProgramResources.html("[web](https://example.org/a)").contains("href=\"https://example.org/a\""));
		assertTrue(ProgramResources.html("[mail](mailto:info@example.org)").contains("href=\"mailto:info@example.org\""));
	}

	public void testImageInsideLinkLeavesText() {
		String html = ProgramResources.html("[![Bild](http://evil.example/x.png)](https://example.org)");
		assertFalse(html, html.contains("<img"));
		assertTrue(html, html.contains("Bild"));
	}

	public void testLineBreaksKept() {
		String html = ProgramResources.html("Wert 1: aus\r\nWert 2: ein\r\n");
		assertTrue(html, html.contains("<br"));
	}

	public static Test suite() {
		return new TestSuite(TestProgramResourcesProbe.class);
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.top_logic.tl.ai.tool.api.AnnotatedTools;
import com.top_logic.tl.ai.tool.api.ToolMethod;

import de.haumacher.ehome.ai.BuildingTools;
import de.haumacher.ehome.ai.DeviceTools;
import de.haumacher.ehome.ai.WiringTools;
import de.haumacher.ehome.ai.logic.BlockTools;
import de.haumacher.ehome.ai.logic.DiagnosisTools;
import de.haumacher.ehome.ai.logic.FunctionTools;
import de.haumacher.ehome.ai.logic.RuleTools;
import de.haumacher.ehome.ai.logic.SimulationTools;

/**
 * Every writing tool of E-Home's assistant describes its call for the revision log (TL #30276): its
 * <code>@ToolMethod</code> names a commit message method, so that the commit of an agent's turn
 * names the change and its target instead of the generic "tool call". What the messages say for real
 * calls is checked by <code>TestAssistantTools</code>.
 */
@SuppressWarnings("javadoc")
public class TestToolCommitMessages extends TestCase {

	/** The classes of E-Home's tools (<code>ehome.ai.config.xml</code>, <code>ehome.ai.logic.config.xml</code>). */
	static final List<Class<?>> TOOL_CLASSES = List.of(BuildingTools.class, DeviceTools.class, WiringTools.class,
		RuleTools.class, SimulationTools.class, DiagnosisTools.class, FunctionTools.class, BlockTools.class);

	public void testEveryWritingToolHasACommitMessage() throws Exception {
		List<String> missing = new ArrayList<>();
		int writing = 0;
		for (Class<?> toolClass : TOOL_CLASSES) {
			for (Method method : toolClass.getDeclaredMethods()) {
				ToolMethod annotation = method.getAnnotation(ToolMethod.class);
				if (annotation == null || !annotation.mode().writesApplicationState()) {
					continue;
				}
				writing++;
				if (annotation.commitMessage().isEmpty()) {
					missing.add(toolClass.getSimpleName() + "#" + method.getName());
				}
			}
			// Builds the tools: a commit message method whose name or parameters do not fit fails here.
			AnnotatedTools.from(toolClass.getConstructor().newInstance());
		}
		assertTrue("Writing tools found: " + writing, writing >= 19);
		assertEquals("Writing tools without a commit message:", List.of(), missing);
	}

	public static Test suite() {
		return new TestSuite(TestToolCommitMessages.class);
	}

}

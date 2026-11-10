/*
 * Copyright (c) 2026 Bernhard Haumacher
 */

/**
 * The assistant of E-Home (haumacher/ehome #126, <code>doc/ai.md</code>): E-Home's own tools for the
 * agents of the TopLogic AI modules.
 *
 * <p>
 * The foundation (#126) configures the modules: the chat in the shell
 * (<code>WEB-INF/views/app.view.xml</code>), the models and E-Home's application context
 * (<code>WEB-INF/autoconf/ehome.ai.config.xml</code>), and the rights per role
 * (<code>WEB-INF/autoconf/ehome.security.config.xml</code>, <code>AiToolAccessService</code>). The
 * assistant reads and changes data with the shipped data tools (<code>query</code>,
 * <code>present</code>, <code>updateObject</code>, ...).
 * </p>
 *
 * <h2>E-Home's tools (haumacher/ehome #127)</h2>
 *
 * <ul>
 * <li>{@link de.haumacher.ehome.ai.BuildingTools} (factory <code>ehome-building</code>): the building
 * structure, creating and moving parts, placing a device.</li>
 * <li>{@link de.haumacher.ehome.ai.DeviceTools} (<code>ehome-devices</code>): the catalog, the devices
 * by line, planning and duplicating a device, its parameter pages and setting a parameter.</li>
 * <li>{@link de.haumacher.ehome.ai.WiringTools} (<code>ehome-wiring</code>): the group addresses with
 * what tells their purpose (paged), creating one, setting its datapoint type, naming several at once,
 * a device's communication objects, connecting and disconnecting them.</li>
 * <li>{@link de.haumacher.ehome.ai.NewPartsSecurityContextFactory}: the workaround of an engine gap of
 * the pre-commit guard (doc/tl-todo.md, "AI modules").</li>
 * </ul>
 *
 * <h2>Where further domain tools join</h2>
 *
 * <p>
 * The tools for buildings, devices and wiring (haumacher/ehome #127) and for rules, functions,
 * schedules and pages (#128) are classes of this package with <code>@ToolMethod</code> methods:
 * </p>
 * <ul>
 * <li><b>Groups.</b> A reading tool declares <code>ToolGroups.DATA_READ</code>, a changing one
 * <code>ToolGroups.DATA_WRITE</code> with the mode <code>DATA_WRITE</code> (approval by the user). These
 * are the groups the roles hold: installers both, viewers the reading one. No E-Home group of its own
 * is needed, and none of the privileged groups.</li>
 * <li><b>Rights.</b> E-Home's Java code is not access checked, so a changing tool checks
 * <code>Write</code> on the project before it changes anything, and refuses with the reason the
 * views give (their veto functions). Objects come in as <code>Type:id</code> and are resolved with
 * <code>ToolExecutionContext.resolveObject</code>, under the run's read rights.</li>
 * <li><b>Registration.</b> A factory in <code>ToolRegistryService</code> (a
 * <code>ReflectiveToolFactory</code> over the class), and its tools added to the chat's agent
 * <code>TL_SingleAgent</code> in <code>TopLogicAgentDefinitions</code>, additively, as
 * <code>tl-chat.conf.config.xml</code> adds its <code>present</code> tool - in
 * <code>ehome.ai.config.xml</code>.</li>
 * <li><b>Skills.</b> The knowledge the tools do not carry (building layout, device planning, wiring,
 * rules) is a skill each, in <code>&lt;skills&gt;</code> of <code>TopLogicAgentDefinitions</code> in
 * the same file, naming its tools and added to <code>TL_SingleAgent</code>.</li>
 * </ul>
 *
 * <p>
 * Deploying to a controller and programming a device stay commands of the installer in the views:
 * no tool does them.
 * </p>
 */
package de.haumacher.ehome.ai;

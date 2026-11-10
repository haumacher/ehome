/*
 * Copyright (c) 2026 Bernhard Haumacher
 */

/**
 * The assistant's tools for E-Home's logic (haumacher/ehome #128, <code>doc/ai.md</code>): rules,
 * schedule entries and application datapoints ({@link de.haumacher.ehome.ai.logic.RuleTools}), the
 * simulation of a scenario ({@link de.haumacher.ehome.ai.logic.SimulationTools}), the diagnosis of
 * the runtime image and of the monitor's recordings ({@link de.haumacher.ehome.ai.logic.DiagnosisTools}),
 * functions and control pages ({@link de.haumacher.ehome.ai.logic.FunctionTools}) and virtual devices
 * ({@link de.haumacher.ehome.ai.logic.BlockTools}).
 *
 * <p>
 * The tools follow the contract of the package <code>de.haumacher.ehome.ai</code>: groups
 * <code>data.read</code> and <code>data.write</code>, <code>Write</code> on the project checked before
 * a change, the application's refusals relayed, objects by <code>Type:id</code>, datapoints named
 * <code>Name (id)</code>. Each delegates to the Java code behind the views' commands. A change is
 * tried in a {@link de.haumacher.ehome.ai.logic.Sandbox} first (a transaction that is rolled back), so
 * that a change the application refuses leaves nothing behind, and a dry run is the same trial.
 * </p>
 *
 * <p>
 * The tools, the skills that carry them (<code>EHome_rules</code>, <code>EHome_functionsAndPages</code>,
 * <code>EHome_blocks</code>, <code>EHome_diagnose</code>) and their registration with the chat's agent
 * are configured in <code>WEB-INF/autoconf/ehome.ai.logic.config.xml</code>. No tool deploys, programs a
 * device or writes to the bus.
 * </p>
 */
package de.haumacher.ehome.ai.logic;

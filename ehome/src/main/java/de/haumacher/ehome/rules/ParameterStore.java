/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * The persisted parameter overrides of <code>spec/rules.md</code> 8.4 under their logical keys
 * <code>&lt;ruleId&gt;/&lt;parameterId&gt;</code>. Survives a reboot of the {@link Engine} when
 * the same store is given to the new engine; how it is kept beyond the process is the embedding
 * application's business.
 */
public final class ParameterStore {

	private final Map<String, Val> _overrides = new LinkedHashMap<>();

	/**
	 * The inhabitant's timelines (<code>spec/rules.md</code> 8.7) under their logical keys
	 * <code>&lt;ruleId&gt;/.timeline</code>: the canonical form as a JSON value, which the controller
	 * keeps in its blob store.
	 */
	private final Map<String, Object> _timelines = new LinkedHashMap<>();

	/** Stores an override. */
	public void write(String rule, String parameter, Val value) {
		_overrides.put(key(rule, parameter), value);
	}

	/** The override, <code>null</code> if there is none. */
	public Val read(String rule, String parameter) {
		return _overrides.get(key(rule, parameter));
	}

	/** Removes the override. */
	public void erase(String rule, String parameter) {
		_overrides.remove(key(rule, parameter));
	}

	/** Removes every override for which the test (logical key, value) is <code>false</code>. */
	public void prune(BiPredicate<String, Val> keep) {
		_overrides.entrySet().removeIf(entry -> !keep.test(entry.getKey(), entry.getValue()));
	}

	/** The stored overrides by logical key. */
	public Map<String, Val> overrides() {
		return Collections.unmodifiableMap(_overrides);
	}

	/** Stores the inhabitant's timeline of the rule: its canonical form as a JSON value. */
	public void writeTimeline(String rule, Object timeline) {
		_timelines.put(key(rule, RuleSet.TIMELINE_KEY), timeline);
	}

	/** The inhabitant's timeline of the rule (a JSON value), <code>null</code> if there is none. */
	public Object readTimeline(String rule) {
		return _timelines.get(key(rule, RuleSet.TIMELINE_KEY));
	}

	/** Removes the inhabitant's timeline of the rule. */
	public void eraseTimeline(String rule) {
		_timelines.remove(key(rule, RuleSet.TIMELINE_KEY));
	}

	/** The stored timelines by logical key (<code>&lt;ruleId&gt;/.timeline</code>). */
	public Map<String, Object> timelines() {
		return Collections.unmodifiableMap(_timelines);
	}

	/** The logical key. */
	public static String key(String rule, String parameter) {
		return rule + "/" + parameter;
	}

	/**
	 * The 48-bit hash the controller stores an override under (homecontrol
	 * <code>ParameterStore::hash</code>: FNV-1a over the logical key, folded): two parameters with
	 * the same hash refuse an image on the controller.
	 */
	public static long hash(String rule, String parameter) {
		long h = 0xcbf29ce484222325L;
		for (byte b : key(rule, parameter).getBytes(StandardCharsets.UTF_8)) {
			h ^= b & 0xFF;
			h *= 0x100000001b3L;
		}
		return (h ^ (h >>> 48)) & 0xFFFFFFFFFFFFL;
	}

}

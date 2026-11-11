/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.db2.DBKnowledgeBase;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * Whether the communication object of a connector of a device is in effect (haumacher/ehome #137):
 * the connector is in use ({@link ModuleParts#inEffect(TLObject)}: not marked inactive, its module
 * instance in effect), and the device's parameters place the object
 * ({@link DeviceConfiguration#getActiveInterfaces()}). An object switched off by a parameter may
 * still have connections (the import keeps those of the project, a parameter changed after the
 * connection): such a connection does not communicate.
 *
 * <p>
 * The objects in effect of a device are computed once per device and revision of the knowledge base
 * (as {@link de.haumacher.ehome.runtime.ImageCache} does): the derivations of the group addresses ask
 * for every connection of every address, and the configuration of a device walks the whole structure
 * of its program. While the current thread has changes not yet committed (an import, a check during a
 * commit, a change of a parameter in a test), they are computed fresh for each question. The
 * configuration reads only stored values of the device, so the answer does not depend on the reader.
 * </p>
 */
public final class ObjectsInEffect {

	/** How many devices are kept (the least recently used one is dropped first). */
	private static final int CAPACITY = 512;

	private static final Map<ObjectKey, Entry> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ObjectKey, Entry> eldest) {
			return size() > CAPACITY;
		}
	};

	/** The objects in effect of a device in a revision. */
	private record Entry(long revision, Set<Port> ports) {
		// A value.
	}

	private ObjectsInEffect() {
		// Static only.
	}

	/**
	 * Whether the object of the given connector (<code>ehome.physical:Connector</code>) is in effect:
	 * see the class comment. A connector of a device without a program, or one naming no object, is
	 * taken as it is marked ({@link ModuleParts#inEffect(TLObject)}): there is no structure to decide
	 * by.
	 */
	public static boolean inEffect(TLObject connector) {
		if (connector == null || !ModuleParts.inEffect(connector)) {
			return false;
		}
		TLObject device = connector.tContainer();
		Interface placed = ModuleParts.port(connector);
		if (device == null || placed == null || DeviceConfiguration.program(device) == null) {
			return true;
		}
		return ports(device).contains(placed.getPort());
	}

	/**
	 * The communication objects in effect of the device.
	 */
	private static Set<Port> ports(TLObject device) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		ObjectKey key = device.tId();
		if (!(kb instanceof DBKnowledgeBase db) || db.hasUncommittedChanges() || key == null) {
			return compute(device);
		}
		long revision = kb.getHistoryManager().getLastRevision();
		synchronized (CACHE) {
			Entry cached = CACHE.get(key);
			if (cached != null && cached.revision() == revision) {
				return cached.ports();
			}
		}
		Set<Port> result = compute(device);
		synchronized (CACHE) {
			Entry cached = CACHE.get(key);
			if (cached == null || cached.revision() < revision) {
				CACHE.put(key, new Entry(revision, result));
			}
		}
		return result;
	}

	private static Set<Port> compute(TLObject device) {
		return new HashSet<>(new DeviceConfiguration(device).getActiveInterfaces().keySet());
	}

}

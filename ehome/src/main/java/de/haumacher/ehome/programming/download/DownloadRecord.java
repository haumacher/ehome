/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.modules.ModuleInstances;

/**
 * Records a successful download at the device: what is loaded now (<code>Device#loaded</code>),
 * when (<code>Device#lastDownload</code>) and, for a full download, the revision of the project data
 * it was computed from (<code>Device#downloadRevision</code>), the reference of the changes since
 * (<code>de.haumacher.ehome.programming.DeviceChanges</code>).
 */
public class DownloadRecord {

	/** The parts a full download loads (all of <code>ehome.physical:DownloadPart</code>). */
	private static final String[] FULL = { "IndividualAddress", "ApplicationProgram", "Parameters", "GroupAddresses",
		"MediumConfig" };

	/**
	 * Records a successful download of the given kind, in a transaction of its own.
	 *
	 * @param revision
	 *        The commit number of the revision the download was computed from
	 *        ({@link DownloadPlan#getRevision()}).
	 */
	public static void record(TLObject device, DownloadPlan.Kind kind, long revision) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			Set<Object> loaded = new LinkedHashSet<>();
			Object current = device.tValueByName("loaded");
			if (current instanceof Collection<?> set) {
				loaded.addAll(set);
			}
			if (kind == DownloadPlan.Kind.ADDRESS) {
				loaded.add(part("IndividualAddress"));
			} else {
				for (String part : FULL) {
					loaded.add(part(part));
				}
				device.tUpdateByName("downloadRevision", Long.valueOf(revision));
			}
			if (kind == DownloadPlan.Kind.LAYOUT) {
				// The device holds E-Home's layout of its modules now (haumacher/ehome #119).
				ModuleInstances.clearDifferences(device);
			}
			device.tUpdateByName("loaded", loaded);
			device.tUpdateByName("lastDownload", new Date());
			tx.commit();
		}
	}

	private static TLClassifier part(String name) {
		return (TLClassifier) TLModelUtil.findPart("ehome.physical:DownloadPart#" + name);
	}

}

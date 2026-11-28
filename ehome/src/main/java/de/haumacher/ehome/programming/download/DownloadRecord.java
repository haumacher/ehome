/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.modules.ModuleInstances;

/**
 * Records a successful download at the device: what is loaded now (<code>Device#loaded</code>),
 * when (<code>Device#lastDownload</code>) and, for a full download, the revision of the project data
 * it was computed from (<code>Device#downloadRevision</code>), the reference of the changes since
 * (<code>de.haumacher.ehome.programming.DeviceChanges</code>).
 *
 * <p>
 * A download of the parameters only ({@link DownloadPlan.Kind#PARAMETERS}) is recorded as a full one:
 * it checked that the device holds the program before it wrote the parameters and the tables, so the
 * device holds every part afterwards.
 * </p>
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
		String label = MetaLabelProvider.INSTANCE.getLabel(device);
		record(device, kind, revision, switch (kind) {
			case ADDRESS -> I18NConstants.COMMIT_ADDRESS_PROGRAMMED__DEVICE.fill(label);
			case LAYOUT -> I18NConstants.COMMIT_LAYOUT_PROGRAMMED__DEVICE.fill(label);
			case FULL -> I18NConstants.COMMIT_PROGRAMMED__DEVICE.fill(label);
			case PARAMETERS -> I18NConstants.COMMIT_PARAMETERS_PROGRAMMED__DEVICE.fill(label);
		});
	}

	/**
	 * Records that the device holds what a download of the given kind loads, in a transaction of its
	 * own with the given commit message (haumacher/ehome #143).
	 *
	 * @param revision
	 *        The commit number of the revision the state of the device corresponds to.
	 * @param commitMessage
	 *        What happened to the device, in the user's terms.
	 */
	public static void record(TLObject device, DownloadPlan.Kind kind, long revision, ResKey commitMessage) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction(commitMessage)) {
			Device known = (Device) device;
			Set<TLClassifier> loaded = new LinkedHashSet<>(known.getLoaded());
			if (kind == DownloadPlan.Kind.ADDRESS) {
				loaded.add(part("IndividualAddress"));
			} else {
				for (String part : FULL) {
					loaded.add(part(part));
				}
				known.setDownloadRevision(Long.valueOf(revision));
			}
			if (kind == DownloadPlan.Kind.LAYOUT) {
				// The device holds E-Home's layout of its modules now (haumacher/ehome #119).
				ModuleInstances.clearDifferences(device);
			}
			known.setLoaded(loaded);
			known.setLastDownload(new Date());
			tx.commit();
		}
	}

	private static TLClassifier part(String name) {
		return (TLClassifier) TLModelUtil.findPart("ehome.physical:DownloadPart#" + name);
	}

}

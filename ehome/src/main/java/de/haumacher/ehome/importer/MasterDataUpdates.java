/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.xml.log.XMLStreamLog;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;

/**
 * The objects of the master data an import found known already, decided at the end of the import
 * (when every reference of the master data is resolved): updated in place by master data of a higher
 * version, kept otherwise, with a warning if the master data define them differently.
 *
 * <p>
 * The import creates the definition of known master data as a transient candidate (see
 * {@link #importCandidate(Candidate)}), which is compared with the known object and never stored.
 * </p>
 *
 * @see VersionedMasterData
 */
public class MasterDataUpdates {

	/**
	 * The {@link MasterDataUpdates} of an import in its {@link ImportContext}.
	 */
	public static final Property<MasterDataUpdates> PROPERTY =
		TypedAnnotatable.property(MasterDataUpdates.class, "masterDataUpdates");

	/**
	 * A known object and the candidate the import created from the master data.
	 *
	 * @param version
	 *        The version of the master data the candidate comes from, <code>null</code> if not known.
	 * @param position
	 *        Where the master data define the object, for the messages.
	 */
	private record Pending(TLObject known, TLObject candidate, String id, String versionAttribute, Integer version,
			Location position) {
		// Pure data.
	}

	private final List<Pending> _pending = new ArrayList<>();

	private final JoiningModelBinding _binding;

	/**
	 * Creates a {@link MasterDataUpdates} for an import with the given binding.
	 */
	public MasterDataUpdates(JoiningModelBinding binding) {
		_binding = binding;
	}

	/**
	 * The {@link MasterDataUpdates} of the given import, see {@link #PROPERTY}.
	 */
	public static MasterDataUpdates get(ImportContext context) {
		MasterDataUpdates result = context.get(PROPERTY);
		if (result == null) {
			throw new IllegalStateException("No master data updates in the import context.");
		}
		return result;
	}

	/**
	 * An import creating a candidate.
	 */
	public interface Candidate {

		/**
		 * Imports the candidate.
		 */
		Object importCandidate() throws XMLStreamException;

	}

	/**
	 * Runs the given import creating transient objects only: the candidate of known master data.
	 */
	public Object importCandidate(Candidate candidate) throws XMLStreamException {
		boolean before = _binding.isTransient();
		_binding.setTransient(true);
		try {
			return candidate.importCandidate();
		} finally {
			_binding.setTransient(before);
		}
	}

	/**
	 * Records a known object and the candidate created from the master data being imported.
	 *
	 * @param versionAttribute
	 *        The attribute of the known object recording the version of its master data.
	 * @param version
	 *        The version of the master data being imported, <code>null</code> if not known.
	 */
	public void add(TLObject known, TLObject candidate, String id, String versionAttribute, Integer version,
			Location position) {
		_pending.add(new Pending(known, candidate, id, versionAttribute, version, position));
	}

	/**
	 * Decides about every known object recorded.
	 *
	 * <p>
	 * Master data of a higher version than the one a known object was imported from (or of a known
	 * version, where the object's is not known) update the object in place, keeping its identity and
	 * the references to it, and record their version. Other master data keep the object; if they
	 * define it differently, a warning names the object and both versions.
	 * </p>
	 *
	 * @param log
	 *        Where to report updates and differences.
	 */
	public void apply(XMLStreamLog log) {
		List<TLObject> garbage = new ArrayList<>();
		ObjectMerge merge = new ObjectMerge();
		for (Pending pending : _pending) {
			TLObject known = pending.known();
			TLObject candidate = pending.candidate();
			String versionAttribute = pending.versionAttribute();
			Integer knownVersion = (Integer) known.tValueByName(versionAttribute);
			Integer version = pending.version();
			Set<String> ignored = Set.of(versionAttribute);
			boolean same = merge.equal(known, candidate, ignored);
			boolean newer = version != null && (knownVersion == null || version.intValue() > knownVersion.intValue());
			if (newer) {
				if (!same) {
					merge.update(known, candidate, ignored, garbage);
					log.log(Level.INFO, pending.position(),
						I18NConstants.INFO_MASTER_DATA_UPDATED__ID_KNOWN_VERSION.fill(pending.id(),
							text(knownVersion), text(version)));
				}
				known.tUpdateByName(versionAttribute, version);
			} else if (!same) {
				log.log(Level.WARN, pending.position(),
					I18NConstants.WARN_MASTER_DATA_DIFFER__ID_VERSION_KNOWN.fill(pending.id(), text(version),
						text(knownVersion)));
			}
		}
		_pending.clear();
		delete(garbage);
	}

	/**
	 * Deletes the given objects (with their parts) at once: a deletion checks the references to the
	 * deleted objects, which is expensive one by one.
	 */
	private static void delete(List<TLObject> objects) {
		List<KnowledgeItem> items = new ArrayList<>();
		for (TLObject obj : objects) {
			if (obj.tValid()) {
				items.add(obj.tHandle());
			}
		}
		if (!items.isEmpty()) {
			items.get(0).getKnowledgeBase().deleteAll(items);
		}
	}

	private static String text(Integer version) {
		return version == null ? "-" : version.toString();
	}

	/**
	 * A copy of the given location of the XML reader, which may change it while it reads on.
	 */
	public static Position position(Location location) {
		return new Position(location.getLineNumber(), location.getColumnNumber(), location.getCharacterOffset(),
			location.getPublicId(), location.getSystemId());
	}

	/**
	 * A fixed {@link Location}.
	 */
	public record Position(int line, int column, int offset, String publicId, String systemId) implements Location {

		@Override
		public int getLineNumber() {
			return line;
		}

		@Override
		public int getColumnNumber() {
			return column;
		}

		@Override
		public int getCharacterOffset() {
			return offset;
		}

		@Override
		public String getPublicId() {
			return publicId;
		}

		@Override
		public String getSystemId() {
			return systemId;
		}

	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.xml.log.XMLStreamLog;
import com.top_logic.basic.util.ResKey;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelNamingConvention;
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
 * <p>
 * A comparison that keeps the known object is remembered (haumacher/ehome #213): an import of the
 * same master data (the same bytes, see {@link #setSource(byte[])}) into the same state of the known
 * object (its identity, the revision of its last change and the version it records) comes to the same
 * decision, so it neither creates the candidate nor compares (see {@link #decided}); only the message
 * of the comparison is repeated. The known object changes only by master data of a higher version,
 * which this class updates together with the version the object records, so a changed object is
 * never decided by an earlier comparison.
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
			Location position, Comparison comparison, Boolean decided) {
		// Pure data.
	}

	/**
	 * A comparison of a known object with the definition of master data that keep it: the known
	 * object in a state (its key, the revision of its last change and the version it records), and
	 * the master data (their version and the fingerprint of their file).
	 */
	private record Comparison(ObjectKey known, long revision, Integer knownVersion, String id, Integer version,
			String source) {
		// Pure data.
	}

	/** The most comparisons remembered, see {@link #COMPARED}. */
	private static final int COMPARED_LIMIT = 10_000;

	/**
	 * The decisions of earlier comparisons that kept the known object: whether the master data define
	 * it alike, by the {@link Comparison}; the oldest are forgotten beyond {@link #COMPARED_LIMIT}.
	 */
	private static final Map<Comparison, Boolean> COMPARED =
		Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Comparison, Boolean> eldest) {
				return size() > COMPARED_LIMIT;
			}
		});

	/** The fingerprint of the master data being read, <code>null</code> if not known. */
	private String _source;

	private static final String NAME_ATTRIBUTE = "name";

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
	 * Sets the content of the master data file read next, whose fingerprint identifies its
	 * definitions in the decisions remembered; <code>null</code> after the file, or for master data
	 * whose content is not known, whose known objects are always compared.
	 */
	public void setSource(byte[] content) {
		_source = content == null ? null : fingerprint(content);
	}

	private static String fingerprint(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (NoSuchAlgorithmException ex) {
			// Every Java platform has SHA-256.
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Whether the given known object is decided by an earlier comparison with the same master data
	 * (see {@link #setSource(byte[])}): if so, the import keeps it without reading its definition
	 * into a candidate, and the decision is reported at the end of the import as if compared.
	 *
	 * @param versionAttribute
	 *        The attribute of the known object recording the version of its master data.
	 * @param version
	 *        The version of the master data being imported, <code>null</code> if not known.
	 */
	public boolean decided(TLObject known, String id, String versionAttribute, Integer version, Location position) {
		Comparison comparison = comparison(known, id, versionAttribute, version);
		Boolean same = comparison == null ? null : COMPARED.get(comparison);
		if (same == null) {
			return false;
		}
		_pending.add(new Pending(known, null, id, versionAttribute, version, position, comparison, same));
		return true;
	}

	/**
	 * The comparison of the given known object with the master data being read, if it is to be
	 * remembered: only one that keeps the object (the master data have no higher version than the
	 * object's) of master data whose content is known; <code>null</code> otherwise.
	 */
	private Comparison comparison(TLObject known, String id, String versionAttribute, Integer version) {
		if (_source == null || version == null || id == null || known.tTransient()) {
			return null;
		}
		Integer knownVersion = (Integer) known.tValueByName(versionAttribute);
		if (knownVersion == null || version.intValue() > knownVersion.intValue()) {
			return null;
		}
		return new Comparison(known.tId(), known.tHandle().getLastUpdate(), knownVersion, id, version, _source);
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
		_pending.add(new Pending(known, candidate, id, versionAttribute, version, position,
			comparison(known, id, versionAttribute, version), null));
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
			boolean same;
			if (candidate == null) {
				same = pending.decided().booleanValue();
			} else {
				same = merge.equal(known, candidate, ignored);
				if (pending.comparison() != null) {
					COMPARED.put(pending.comparison(), Boolean.valueOf(same));
				}
			}
			boolean newer = version != null && (knownVersion == null || version.intValue() > knownVersion.intValue());
			if (newer) {
				if (!same) {
					merge.update(known, candidate, ignored, garbage);
					log.log(Level.INFO, pending.position(),
						I18NConstants.INFO_MASTER_DATA_UPDATED__OBJECT_KNOWN_VERSION.fill(label(known, pending.id()),
							text(knownVersion), text(version)));
				}
				known.tUpdateByName(versionAttribute, version);
			} else if (!same) {
				log.log(Level.WARN, pending.position(),
					I18NConstants.WARN_MASTER_DATA_DIFFER__OBJECT_VERSION_KNOWN.fill(label(known, pending.id()),
						text(version), text(knownVersion)));
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

	/**
	 * The given object of the master data in a message: the label of its type, its name (if it has
	 * one) and its ID in the master data, e.g. <i>Mask version "KNXnet/IP Router" (MV-091A)</i>.
	 */
	public static ResKey label(TLObject obj, String id) {
		ResKey type = TLModelNamingConvention.getTypeLabelKey(obj.tType());
		TLStructuredTypePart namePart = obj.tType().getPart(NAME_ATTRIBUTE);
		Object name = namePart == null ? null : obj.tValue(namePart);
		if (name == null || name.toString().isBlank()) {
			return I18NConstants.MASTER_DATA_OBJECT__TYPE_ID.fill(type, id);
		}
		return I18NConstants.MASTER_DATA_OBJECT__TYPE_NAME_ID.fill(type, name.toString(), id);
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

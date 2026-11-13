/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.objects.identifier.ObjectReference;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.knowledge.service.Revision;
import com.top_logic.knowledge.wrap.WrapperHistoryUtils;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * The changes of the configuration of a device (<code>ehome.physical:Device</code>) against a
 * reference (haumacher/ehome #15): its parameter values and the group addresses its communication
 * objects are connected to.
 *
 * <p>
 * Two references are offered ({@link Reference}):
 * </p>
 * <ul>
 * <li>{@link Reference#DEVICE}: what the real device holds, i.e. the state of the device in the
 * revision of the project data its last download was computed from (see
 * {@link #referenceRevision(TLObject)}). The device as it was then is read from the history of the
 * knowledge base ({@link WrapperHistoryUtils#getWrapper(Revision, TLObject)}): its settings and
 * connectors are the historic objects of that revision. The entries are the values that differ from
 * the value the device had then, and the communication objects whose connections (group address and
 * direction) differ.</li>
 * <li>{@link Reference#DEFAULT}: the defaults of the program; the entries are the values the device
 * sets (its settings), no connections.</li>
 * </ul>
 *
 * <p>
 * Each entry knows the page of the configuration it is shown on, so that the list is grouped by the
 * structure of the program and a page holding a change (or a page below it holding one) is marked.
 * A value is compared in the notation of ETS (both the reference and the current value), so that a
 * value equal to the reference is no change wherever it is stored. A default changed in the catalog
 * since the download is not detected: the default of a placement is read in its current state.
 * </p>
 *
 * <p>
 * The changes are computed on demand, cached per device for the state of the device and the last
 * revision of the knowledge base, so that the many readers of one display (the entries, the pages,
 * the device in the topology) compute them once.
 * </p>
 */
public class DeviceChanges {

	/**
	 * What a device is compared with.
	 */
	public enum Reference {
		/** The defaults of the device's program. */
		DEFAULT("default"),

		/** The state of the device at its last download. */
		DEVICE("device");

		private final String _classifier;

		Reference(String classifier) {
			_classifier = classifier;
		}

		/**
		 * The classifier of <code>ehome.physical:ChangeReference</code> standing for this reference.
		 */
		public TLClassifier classifier() {
			return ((TLEnumeration) TLModelUtil.findType("ehome.physical:ChangeReference")).getClassifier(_classifier);
		}

		/**
		 * The reference a classifier of <code>ehome.physical:ChangeReference</code> stands for,
		 * <code>null</code> for anything else.
		 */
		public static Reference of(Object classifier) {
			if (classifier instanceof TLClassifier value) {
				for (Reference reference : values()) {
					if (reference._classifier.equals(value.getName())) {
						return reference;
					}
				}
			}
			return null;
		}
	}

	/**
	 * A change: a parameter placement whose value differs from the reference, or a communication
	 * object whose connections differ.
	 *
	 * @param item
	 *        The {@link ParameterView} or the {@link Interface} (the placement of the object in
	 *        effect, else the one its connector names).
	 * @param page
	 *        The page the item is shown on (the device for the top of the structure),
	 *        <code>null</code> if the current values do not show it.
	 * @param referenceHolder
	 *        For a value: the holder of the reference value (a historic setting, else the default
	 *        holder of the placement); <code>null</code> for connections.
	 * @param referenceValue
	 *        For a value: the reference value in the notation of ETS.
	 * @param currentValue
	 *        For a value: the current value in the notation of ETS.
	 * @param referenceConnections
	 *        For connections: the connections of the reference (historic objects).
	 * @param currentConnections
	 *        For connections: the current connections.
	 */
	public record Entry(Option item, Object page, TLObject referenceHolder, String referenceValue, String currentValue,
			List<TLObject> referenceConnections, List<TLObject> currentConnections) {

		/**
		 * Whether this is a change of the connections of a communication object.
		 */
		public boolean isConnections() {
			return item instanceof Interface;
		}
	}

	private static final int CACHE_SIZE = 64;

	private static final Map<CacheKey, DeviceChanges> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<CacheKey, DeviceChanges> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private record CacheKey(TLObject device, Reference reference, Object modified, Object downloadRevision,
			long lastRevision) {
		// Identity of a computation.
	}

	private final TLObject _device;

	private final Reference _reference;

	private final Revision _revision;

	private final DeviceConfiguration _configuration;

	private final Map<Option, Entry> _entries = new LinkedHashMap<>();

	private final Set<Object> _changedPages = new HashSet<>();

	/**
	 * The changes of the given device against the given reference.
	 *
	 * @param reference
	 *        The reference; <code>null</code> for the device's state at its last download if it has
	 *        one, else the defaults.
	 */
	public static DeviceChanges of(TLObject device, Reference reference) {
		Reference effective = reference;
		if (effective == null || effective == Reference.DEVICE && referenceRevision(device) == null) {
			effective = referenceRevision(device) != null ? Reference.DEVICE : Reference.DEFAULT;
		}
		CacheKey key = new CacheKey(device, effective, device.tValueByName("lastModified"),
			device.tValueByName("downloadRevision"), HistoryUtils.getLastRevision().getCommitNumber());
		synchronized (CACHE) {
			DeviceChanges cached = CACHE.get(key);
			if (cached != null) {
				return cached;
			}
		}
		DeviceChanges result = new DeviceChanges(device, effective);
		synchronized (CACHE) {
			CACHE.put(key, result);
		}
		return result;
	}

	/**
	 * The changes of the given device since its last download, <code>null</code> if no download of
	 * it is known.
	 */
	public static DeviceChanges sinceDownload(TLObject device) {
		return referenceRevision(device) == null ? null : of(device, Reference.DEVICE);
	}

	private DeviceChanges(TLObject device, Reference reference) {
		_device = device;
		_reference = reference;
		_configuration = new DeviceConfiguration(device);
		_revision = reference == Reference.DEVICE ? referenceRevision(device) : null;
		if (_revision != null) {
			computeAgainstDevice(WrapperHistoryUtils.getWrapper(_revision, device));
		} else {
			computeAgainstDefaults();
		}
		for (Entry entry : _entries.values()) {
			for (Object page = entry.page(); page != null && page != device; page = _configuration.getParentPage(page)) {
				_changedPages.add(page);
			}
		}
	}

	/**
	 * The revision whose state of the device the real device holds: the one its last full download
	 * was computed from (<code>Device#downloadRevision</code>), else, for a device ETS had
	 * downloaded when the project was imported (a <code>lastDownload</code> from the import), the
	 * revision it was imported in; <code>null</code> if no download of the device is known.
	 */
	public static Revision referenceRevision(TLObject device) {
		if (device.tValueByName("downloadRevision") instanceof Number number) {
			return HistoryUtils.getRevision(number.longValue());
		}
		long created = device.tHandle().getCreateCommitNumber();
		Revision last = HistoryUtils.getLastRevision();
		if (created <= 0 || created > last.getCommitNumber()) {
			// Not committed yet.
			return null;
		}
		Revision creation = HistoryUtils.getRevision(created);
		TLObject imported = WrapperHistoryUtils.getWrapper(creation, device);
		return imported != null && imported.tValueByName("lastDownload") != null ? creation : null;
	}

	private void computeAgainstDefaults() {
		Map<Option, Object> shown = _configuration.getShownItems();
		List<Entry> entries = new ArrayList<>();
		for (Object setting : (Collection<?>) _device.tValueByName("settings")) {
			TLObject value = (TLObject) setting;
			ParameterView view = ModuleParts.parameter(value);
			if (view != null) {
				TLObject holder = DeviceConfiguration.defaultHolder(view);
				String reference = ParameterValues.encode(holder);
				String current = ParameterValues.encode(value);
				if (!Objects.equals(reference, current)) {
					entries.add(new Entry(view, shown.get(view), holder, reference, current, null, null));
				}
			}
		}
		addInOrder(entries, shown);
	}

	private void computeAgainstDevice(TLObject historic) {
		Map<Option, Object> shown = _configuration.getShownItems();
		List<Entry> entries = new ArrayList<>();

		// Values: every placement with a setting now or then.
		Map<PartRef, TLObject> referenceSettings = new LinkedHashMap<>();
		for (Object setting : (Collection<?>) historic.tValueByName("settings")) {
			TLObject value = (TLObject) setting;
			if (value.tValueByName("parameter") instanceof TLObject view) {
				referenceSettings.putIfAbsent(new PartRef(identity(view), ModuleParts.instanceId(value)), value);
			}
		}
		Set<ParameterView> views = new LinkedHashSet<>();
		for (Object setting : (Collection<?>) _device.tValueByName("settings")) {
			ParameterView view = ModuleParts.parameter((TLObject) setting);
			if (view != null) {
				views.add(view);
			}
		}
		for (TLObject setting : referenceSettings.values()) {
			if (current(setting, "parameter") instanceof ParameterView view && view.tValid()) {
				views.add(view);
			}
		}
		for (ParameterView view : views) {
			TLObject referenceSetting = referenceSettings.get(ref(view));
			TLObject holder = referenceSetting != null ? referenceSetting : DeviceConfiguration.defaultHolder(view);
			String reference = ParameterValues.encode(holder);
			String current = _configuration.value(view);
			if (!Objects.equals(reference, current)) {
				entries.add(new Entry(view, shown.get(view), holder, reference, current, null, null));
			}
		}

		// Connections: per communication object (by its definition), the group addresses with the
		// direction.
		Map<PartRef, List<TLObject>> referenceConnections = connectionsByPort(historic);
		Map<PartRef, List<TLObject>> currentConnections = connectionsByPort(_device);
		Set<PartRef> ports = new LinkedHashSet<>(currentConnections.keySet());
		ports.addAll(referenceConnections.keySet());
		Map<PartRef, Interface> placements = placements(historic);
		for (PartRef port : ports) {
			List<TLObject> before = referenceConnections.getOrDefault(port, List.of());
			List<TLObject> now = currentConnections.getOrDefault(port, List.of());
			if (keys(before).equals(keys(now))) {
				continue;
			}
			Interface placed = placements.get(port);
			if (placed == null) {
				// The object no longer exists in the program.
				continue;
			}
			entries.add(new Entry(placed, shown.get(placed), null, null, null, before, now));
		}
		addInOrder(entries, shown);
	}

	/**
	 * The entries in the order of the structure; those not shown last, values before objects.
	 */
	private void addInOrder(List<Entry> entries, Map<Option, Object> shown) {
		Map<Option, Integer> order = new HashMap<>();
		int index = 0;
		for (Option item : shown.keySet()) {
			order.put(item, index++);
		}
		List<Entry> sorted = new ArrayList<>(entries);
		int end = index;
		sorted.sort((a, b) -> {
			int x = order.getOrDefault(a.item(), end + (a.isConnections() ? 1 : 0));
			int y = order.getOrDefault(b.item(), end + (b.isConnections() ? 1 : 0));
			return Integer.compare(x, y);
		});
		for (Entry entry : sorted) {
			_entries.putIfAbsent(entry.item(), entry);
		}
	}

	/**
	 * The current placement of each communication object the device or its historic self has
	 * connectors for: the one in effect, else the one a connector names.
	 */
	private Map<PartRef, Interface> placements(TLObject historic) {
		Map<PartRef, Interface> result = new HashMap<>();
		for (Map.Entry<Port, Interface> active : _configuration.getActiveInterfaces().entrySet()) {
			result.put(ref(active.getKey()), active.getValue());
		}
		for (TLObject device : List.of(_device, historic)) {
			for (Object connector : (Collection<?>) device.tValueByName("connectors")) {
				if (current((TLObject) connector, "port") instanceof Interface placed && placed.tValid()
					&& placed.getPort() != null) {
					result.putIfAbsent(ref(placed.getPort()), placed);
				}
			}
		}
		return result;
	}

	/**
	 * A part of the program as a device has it: the unversioned identity of the part of the program
	 * (of a module, of its template) and the ID of the module instance (haumacher/ehome #119),
	 * <code>null</code> outside of modules.
	 */
	private record PartRef(ObjectReference part, String instance) {
		// Value.
	}

	private static PartRef ref(TLObject part) {
		InstanceInfo instance = ModuleParts.instance(part);
		return new PartRef(identity(ModuleParts.template(part)), instance == null ? null : instance.getId());
	}

	/**
	 * The part a setting or connector of the device, now or at an earlier revision, names, as it is
	 * now in the device's module instance of the same ID; <code>null</code> if it is gone.
	 */
	private TLObject current(TLObject holder, String attribute) {
		if (!(holder.tValueByName(attribute) instanceof TLObject part)) {
			return null;
		}
		TLObject template = WrapperHistoryUtils.getCurrent(part);
		String instance = ModuleParts.instanceId(holder);
		if (template == null || instance == null) {
			return template;
		}
		for (TLObject stored : ModuleInstances.stored(_device)) {
			if (instance.equals(stored.tValueByName(ModuleInstances.NAME))) {
				return ModuleParts.wrap(template, InstanceInfo.of(stored));
			}
		}
		return null;
	}

	private static Map<PartRef, List<TLObject>> connectionsByPort(TLObject device) {
		Map<PartRef, List<TLObject>> result = new LinkedHashMap<>();
		for (Object connector : (Collection<?>) device.tValueByName("connectors")) {
			TLObject value = (TLObject) connector;
			if (!(value.tValueByName("port") instanceof TLObject placed)
				|| !(placed.tValueByName("port") instanceof TLObject port)) {
				continue;
			}
			Collection<?> connections = (Collection<?>) value.tValueByName("connections");
			if (connections.isEmpty()) {
				continue;
			}
			PartRef key = new PartRef(identity(port), ModuleParts.instanceId(value));
			List<TLObject> list = result.computeIfAbsent(key, x -> new ArrayList<>());
			for (Object connection : connections) {
				list.add((TLObject) connection);
			}
		}
		return result;
	}

	private static Set<String> keys(List<TLObject> connections) {
		Set<String> result = new HashSet<>();
		for (TLObject connection : connections) {
			result.add(key(connection));
		}
		return result;
	}

	private static String key(TLObject connection) {
		Object target = connection.tValueByName("target");
		Object kind = connection.tValueByName("kind");
		return (target instanceof TLObject address ? identity(address).toString() : "-") + "|"
			+ (kind instanceof TLClassifier classifier ? classifier.getName() : "-");
	}

	private static ObjectReference identity(TLObject obj) {
		return WrapperHistoryUtils.getUnversionedIdentity(obj);
	}

	/**
	 * The device.
	 */
	public TLObject getDevice() {
		return _device;
	}

	/**
	 * The reference the device is compared with.
	 */
	public Reference getReference() {
		return _reference;
	}

	/**
	 * The revision of the reference state, <code>null</code> for the defaults.
	 */
	public Revision getRevision() {
		return _revision;
	}

	/**
	 * The changes in the order of the structure.
	 */
	public List<Entry> getEntries() {
		return Collections.unmodifiableList(new ArrayList<>(_entries.values()));
	}

	/**
	 * The number of changes.
	 */
	public int getCount() {
		return _entries.size();
	}

	/**
	 * The change of the given item, <code>null</code> if it is unchanged.
	 */
	public Entry getEntry(Object item) {
		return _entries.get(item);
	}

	/**
	 * Whether the given object is changed: a placement of a parameter or a communication object with
	 * a change, a page showing a change on it or on a page below it, the device with any change.
	 */
	public boolean isChanged(Object obj) {
		if (obj == _device) {
			return !_entries.isEmpty();
		}
		return _entries.containsKey(obj) || _changedPages.contains(obj);
	}

	/**
	 * The pages showing a change on them or on a page below them.
	 */
	public Set<Object> getChangedPages() {
		return Collections.unmodifiableSet(_changedPages);
	}

	/**
	 * The changes grouped by the page they are shown on, in the order of the structure: per group
	 * its title (the path of the page in the structure) and its items. The values not shown are
	 * grouped last, then the communication objects not shown.
	 */
	public List<Map<String, Object>> getGroups() {
		Map<Object, List<Option>> groups = new LinkedHashMap<>();
		for (Entry entry : _entries.values()) {
			Object group = entry.page() != null ? entry.page()
				: entry.isConnections() ? I18NConstants.CHANGES_OBJECTS : I18NConstants.CHANGES_NOT_SHOWN;
			groups.computeIfAbsent(group, x -> new ArrayList<>()).add(entry.item());
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map.Entry<Object, List<Option>> group : groups.entrySet()) {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("title", title(group.getKey()));
			value.put("items", group.getValue());
			result.add(value);
		}
		return result;
	}

	private String title(Object group) {
		if (group instanceof ResKey key) {
			return Resources.getInstance().getString(key);
		}
		List<String> path = new ArrayList<>();
		for (Object page = group; page != null; page = _configuration.getParentPage(page)) {
			path.add(0, DeviceTexts.label(_device, page));
			if (page == _device) {
				break;
			}
		}
		if (path.size() > 1) {
			// The device stands for the top of the structure only.
			path.remove(0);
		}
		return String.join(" › ", path);
	}

	/**
	 * What the reference holds for the given item, for a person: the value of a parameter, the
	 * connections of a communication object; <code>null</code> if the item is unchanged.
	 */
	public String referenceText(Object item) {
		Entry entry = _entries.get(item);
		if (entry == null) {
			return null;
		}
		if (entry.isConnections()) {
			return connectionsText(entry.referenceConnections());
		}
		Object display = entry.referenceHolder().tValueByName("displayValue");
		if (display != null && !display.toString().isEmpty()) {
			return display.toString();
		}
		return entry.referenceValue() == null ? "–" : entry.referenceValue();
	}

	/**
	 * The given connections for a person: "0/0/5 (Senden), 0/0/7 (Empfangen)", "nicht verbunden"
	 * without any.
	 */
	public static String connectionsText(List<TLObject> connections) {
		if (connections.isEmpty()) {
			return Resources.getInstance().getString(I18NConstants.NOT_CONNECTED);
		}
		List<String> texts = new ArrayList<>();
		for (TLObject connection : connections) {
			Object target = connection.tValueByName("target");
			Object address = target instanceof TLObject groupAddress ? groupAddress.tValueByName("address") : null;
			Object kind = connection.tValueByName("kind");
			String kindText = kind instanceof TLClassifier classifier ? MetaLabelProvider.INSTANCE.getLabel(
				kind(classifier.getName())) : "";
			texts.add(Resources.getInstance().getString(
				I18NConstants.CONNECTION__ADDRESS_KIND.fill(Objects.toString(address, "?"), kindText)));
		}
		Collections.sort(texts);
		return String.join(", ", texts);
	}

	/**
	 * Sets the given item of the device back to the reference: the value of a parameter, the
	 * connections of a communication object. Must run in a transaction.
	 */
	public void reset(Object item) {
		Entry entry = _entries.get(item);
		if (entry == null) {
			return;
		}
		if (entry.isConnections()) {
			resetConnections(entry);
		} else {
			resetValue(entry);
		}
	}

	private void resetValue(Entry entry) {
		ParameterView view = (ParameterView) entry.item();
		TLObject holder = entry.referenceHolder();
		if (_reference == Reference.DEFAULT || holder == DeviceConfiguration.defaultHolder(view)) {
			DeviceSettings.restoreValue(_device, view, null);
			return;
		}
		Object value = ParameterValues.value(holder);
		if (value instanceof EnumerationValue entryValue) {
			value = WrapperHistoryUtils.getCurrent(entryValue);
			if (value == null || !((TLObject) value).tValid()) {
				value = Double.valueOf(entryValue.getUnsignedValue());
			}
		}
		if (value == null) {
			// A value that could not be decoded for its type: the device had it in ETS notation.
			value = entry.referenceValue();
		}
		DeviceSettings.restoreValue(_device, view, value);
	}

	private void resetConnections(Entry entry) {
		Interface placed = (Interface) entry.item();
		Set<String> reference = keys(entry.referenceConnections());
		Set<String> current = new HashSet<>();
		for (TLObject connection : entry.currentConnections()) {
			if (connection.tValid() && !reference.contains(key(connection))) {
				DeviceSettings.disconnect(connection);
			} else {
				current.add(key(connection));
			}
		}
		for (TLObject connection : entry.referenceConnections()) {
			if (current.contains(key(connection))) {
				continue;
			}
			TLObject target = (TLObject) connection.tValueByName("target");
			TLObject address = target == null ? null : WrapperHistoryUtils.getCurrent(target);
			if (address == null || !address.tValid()) {
				throw new TopLogicException(I18NConstants.ERROR_ADDRESS_GONE__ADDRESS_OBJECT.fill(
					target == null ? "?" : Objects.toString(target.tValueByName("address")),
					GroupAddressConnections.objectLabel(placed)));
			}
			TLClassifier kind = kind(((TLClassifier) connection.tValueByName("kind")).getName());
			DeviceSettings.connect(_device, placed, address, kind);
		}
	}

	/**
	 * Sets every changed value and connection of the device back to the reference: the values first
	 * (they decide which objects are in effect), then the connections. Must run in a transaction.
	 *
	 * @return The number of changes discarded.
	 */
	public int resetAll() {
		List<Entry> entries = getEntries();
		for (Entry entry : entries) {
			if (!entry.isConnections()) {
				resetValue(entry);
			}
		}
		for (Entry entry : entries) {
			if (entry.isConnections()) {
				resetConnections(entry);
			}
		}
		return entries.size();
	}

	/**
	 * Why the changes of the device since its last download cannot be discarded,
	 * <code>null</code> if they can.
	 */
	public static ResKey discardVeto(TLObject device) {
		DeviceChanges changes = sinceDownload(device);
		if (changes == null) {
			return I18NConstants.ERROR_NO_DOWNLOAD__DEVICE.fill(device);
		}
		if (changes.getCount() == 0) {
			return I18NConstants.ERROR_NO_CHANGES__DEVICE.fill(device);
		}
		return null;
	}

	/**
	 * Discards the changes of the device since its last download. Must run in a transaction.
	 *
	 * @return The number of changes discarded.
	 */
	public static int discard(TLObject device) {
		ResKey veto = discardVeto(device);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		return sinceDownload(device).resetAll();
	}

	/**
	 * The devices of the project (<code>ehome.project:Project</code>): those in its lines, then
	 * those not placed in a line.
	 */
	public static List<TLObject> devices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					result.add((TLObject) device);
				}
			}
		}
		Object unassigned = project.tValueByName("unassignedDevices");
		if (unassigned instanceof Collection<?> devices) {
			for (Object device : devices) {
				result.add((TLObject) device);
			}
		}
		return result;
	}

	/**
	 * The devices of the project with changes since their last download.
	 */
	public static List<TLObject> changedDevices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject device : devices(project)) {
			DeviceChanges changes = sinceDownload(device);
			if (changes != null && changes.getCount() > 0) {
				result.add(device);
			}
		}
		return result;
	}

	/**
	 * The number of changes since the last download of all devices of the project.
	 */
	public static int projectCount(TLObject project) {
		int result = 0;
		for (TLObject device : changedDevices(project)) {
			result += sinceDownload(device).getCount();
		}
		return result;
	}

	/**
	 * Why the changes of the project's devices since their last download cannot be discarded,
	 * <code>null</code> if they can.
	 */
	public static ResKey projectDiscardVeto(TLObject project) {
		return changedDevices(project).isEmpty() ? I18NConstants.ERROR_NO_CHANGES_PROJECT : null;
	}

	/**
	 * Discards the changes since their last download of all devices of the project. Must run in a
	 * transaction.
	 *
	 * @return The number of changes discarded.
	 */
	public static int discardProject(TLObject project) {
		ResKey veto = projectDiscardVeto(project);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		int result = 0;
		for (TLObject device : changedDevices(project)) {
			result += sinceDownload(device).resetAll();
		}
		return result;
	}

	private static TLClassifier kind(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType")).getClassifier(name);
	}

}

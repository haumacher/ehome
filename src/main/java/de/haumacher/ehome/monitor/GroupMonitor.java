/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.top_logic.basic.Logger;
import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.ProjectIndex.AddressInfo;
import de.haumacher.ehome.monitor.ProjectIndex.DeviceInfo;
import de.haumacher.ehome.programming.download.BusException;

/**
 * The group monitor of a project (haumacher/ehome #10): attached to the bus through a
 * {@link BusLink}, it receives the group telegrams, decodes each by the datapoint type of its
 * address in the project, attributes the sender to the project's device, and keeps
 * <ul>
 * <li>the <b>state board</b>: per group address the last value, its sender, the time of the last
 * telegram and the number of telegrams, for every address of the project and every other address
 * seen;</li>
 * <li>the <b>log</b>: the last {@value #LOG_LIMIT} telegrams, newest first;</li>
 * <li>a <b>recording</b> while one runs: the telegrams are stored with the project
 * (<code>ehome.monitor:Recording</code>) in batches, one transaction per {@value #BATCH_SIZE}
 * telegrams or per {@value #BATCH_MILLIS} ms.</li>
 * </ul>
 *
 * <p>
 * Through a HomeControl controller ({@link ControllerBusLink}) the telegrams carry the name and
 * value of the controller's datapoint: its value wins over the decoding by the project, since it is
 * what the house actually runs; its name fills the name of an address the project does not know.
 * The controller's other events (Zigbee, rules, commands, image loads, gaps) are rows of the log of
 * their own ({@link LinkEvent}); they do not change the state board and are not recorded.
 * </p>
 *
 * <p>
 * Sending a value ({@link #send(Object, Object, String)}) or a read request ({@link #read(Object)})
 * is refused until sending is allowed explicitly ({@link #setSending(boolean)}); connecting locks it
 * again.
 * </p>
 *
 * <p>
 * The views read the monitor through {@link #snapshot()}, a dictionary taken anew after every
 * change; a {@link Publisher} is told about every change, from whatever thread it happens on.
 * </p>
 */
public class GroupMonitor {

	/** The number of telegrams the log keeps. */
	public static final int LOG_LIMIT = 1000;

	/** The number of telegrams stored in one transaction of a recording at most. */
	public static final int BATCH_SIZE = 100;

	/** The time in milliseconds a received telegram waits at most before it is stored. */
	public static final long BATCH_MILLIS = 1000;

	/** The time in milliseconds a row of the state board is marked as changed. */
	public static final long FRESH_MILLIS = 3000;

	/**
	 * Told about every change of the monitor.
	 */
	public interface Publisher {

		/**
		 * The monitor changed; a new {@link GroupMonitor#snapshot()} shows it. Called from any
		 * thread, possibly while the monitor is locked: an implementation only schedules its work.
		 */
		void changed();
	}

	private final Object _lock = new Object();

	private ProjectIndex _index;

	private BusLink _link;

	private String _gateway;

	private String _lostReason;

	private boolean _viaController;

	private boolean _sending;

	private final Map<Integer, MonitorRow> _board = new TreeMap<>();

	private final ArrayDeque<MonitorRow> _log = new ArrayDeque<>();

	private List<MonitorRow> _pausedLog;

	private long _sequence;

	private long _received;

	private TLObject _recording;

	private int _recorded;

	private final List<BusTelegram> _pending = new ArrayList<>();

	private ScheduledFuture<?> _flush;

	private SubSessionContext _context;

	private TimeZone _timeZone = TimeZone.getDefault();

	private ScheduledExecutorService _executor;

	private volatile Publisher _publisher;

	private long _revision;

	private long _batchMillis = BATCH_MILLIS;

	/**
	 * Creates a {@link GroupMonitor} for the given project.
	 */
	public GroupMonitor(ProjectIndex index) {
		_index = index;
		initBoard();
	}

	/**
	 * Sets the time in milliseconds a received telegram of a recording waits at most before it is
	 * stored (default {@value #BATCH_MILLIS}).
	 */
	public void setBatchMillis(long batchMillis) {
		synchronized (_lock) {
			_batchMillis = batchMillis;
		}
	}

	/**
	 * The project the monitor is for.
	 */
	public TLObject getProject() {
		return _index.getProject();
	}

	/**
	 * Reads the group addresses and devices of the project again (they may have changed since the
	 * monitor was created). Keeps what the state board shows.
	 */
	public void refresh(ProjectIndex index) {
		synchronized (_lock) {
			_index = index;
			initBoard();
		}
	}

	private void initBoard() {
		for (AddressInfo address : _index.getAddresses().values()) {
			MonitorRow row = _board.get(address.address());
			if (row == null) {
				row = new MonitorRow(Integer.valueOf(address.address()));
				row.put("count", Integer.valueOf(0));
				row.put("value", "");
				row.put("sender", "");
				row.put("time", "");
				_board.put(address.address(), row);
			}
			describe(row, address.address());
		}
	}

	/**
	 * Sets the {@link Publisher} told about the changes; <code>null</code> for none.
	 */
	public void setPublisher(Publisher publisher) {
		_publisher = publisher;
	}

	/**
	 * Removes the given {@link Publisher}, if it is the one set.
	 *
	 * @return Whether it was the one set.
	 */
	public boolean removePublisher(Publisher publisher) {
		synchronized (_lock) {
			if (_publisher == publisher) {
				_publisher = null;
				return true;
			}
			return false;
		}
	}

	/**
	 * Attaches the monitor to the bus through the given link.
	 *
	 * <p>
	 * Called in the request of the user: the session context of the caller is the one the
	 * recordings are stored in, and its time zone the one the times are shown in. Sending is locked.
	 * </p>
	 */
	public void connect(BusLink link) {
		initClassifiers();
		synchronized (_lock) {
			disconnectLink();
			_context = ThreadContextManager.getSubSession();
			TimeZone timeZone = timeZone(_context);
			if (timeZone != null) {
				_timeZone = timeZone;
			}
			_link = link;
			_gateway = link.description();
			_viaController = link.viaController();
			_lostReason = null;
			_sending = false;
			link.setListener(new BusLink.Listener() {
				@Override
				public void received(BusTelegram telegram) {
					// On the thread of the link: in the session of the user, and never failing, or the
					// link drops its listener.
					try {
						inContext(() -> receive(telegram));
					} catch (RuntimeException ex) {
						Logger.error("Receiving a telegram failed: " + telegram, ex, GroupMonitor.class);
					}
				}

				@Override
				public void closed(String reason) {
					try {
						lost(link, reason);
					} catch (RuntimeException ex) {
						Logger.error("Closing the monitor failed.", ex, GroupMonitor.class);
					}
				}

				@Override
				public void event(LinkEvent event) {
					try {
						inContext(() -> receive(event));
					} catch (RuntimeException ex) {
						Logger.error("Receiving an event failed: " + event, ex, GroupMonitor.class);
					}
				}

				@Override
				public void stateChanged() {
					changed();
				}
			});
		}
		changed();
	}

	/**
	 * Detaches the monitor from the bus. A running recording ends.
	 */
	public void disconnect() {
		synchronized (_lock) {
			disconnectLink();
			_lostReason = null;
			_sending = false;
		}
		stopRecording();
		changed();
	}

	/**
	 * Detaches the monitor and frees what it holds.
	 */
	public void close() {
		disconnect();
		synchronized (_lock) {
			if (_executor != null) {
				_executor.shutdown();
				_executor = null;
			}
			_publisher = null;
		}
	}

	private void disconnectLink() {
		if (_link != null) {
			BusLink link = _link;
			_link = null;
			link.setListener(null);
			link.close();
		}
	}

	private void lost(BusLink link, String reason) {
		synchronized (_lock) {
			if (_link != link) {
				return;
			}
			_link = null;
			_sending = false;
			_lostReason = reason == null ? "" : reason;
		}
		inContext(this::stopRecording);
		changed();
	}

	/**
	 * Whether the monitor is attached through a link with the given {@link BusLink#description()}.
	 */
	public boolean isConnectedTo(String description) {
		synchronized (_lock) {
			return _link != null && _link.description().equals(description);
		}
	}

	/**
	 * Whether the monitor is attached to the bus.
	 */
	public boolean isConnected() {
		synchronized (_lock) {
			return _link != null;
		}
	}

	/**
	 * Whether sending is allowed.
	 */
	public boolean isSending() {
		synchronized (_lock) {
			return _sending;
		}
	}

	/**
	 * Allows or locks sending. Sending can only be allowed while the monitor is connected.
	 */
	public void setSending(boolean sending) {
		synchronized (_lock) {
			if (sending && _link == null) {
				throw new TopLogicException(I18NConstants.ERROR_NOT_CONNECTED);
			}
			_sending = sending;
		}
		changed();
	}

	/**
	 * Holds the log as it is (the state board goes on), or lets it follow the telegrams again.
	 */
	public void setLogPaused(boolean paused) {
		synchronized (_lock) {
			_pausedLog = paused ? new ArrayList<>(_log) : null;
		}
		changed();
	}

	/**
	 * Empties the log and the values of the state board.
	 */
	public void clear() {
		synchronized (_lock) {
			_log.clear();
			if (_pausedLog != null) {
				_pausedLog = new ArrayList<>();
			}
			_received = 0;
			for (Iterator<MonitorRow> it = _board.values().iterator(); it.hasNext();) {
				MonitorRow row = it.next();
				if (_index.address((Integer) row.get(MonitorRow.KEY)) == null) {
					it.remove();
				} else {
					row.put("count", Integer.valueOf(0));
					row.put("value", "");
					row.put("sender", "");
					row.put("time", "");
					row.remove("millis");
				}
			}
		}
		changed();
	}

	/**
	 * Takes a telegram the link received.
	 */
	public void receive(BusTelegram telegram) {
		receive(telegram, null);
	}

	/**
	 * Takes a telegram, decoded by the given datapoint type instead of the one of its address (a
	 * value sent to an address the project does not know, with the type entered).
	 */
	private void receive(BusTelegram telegram, Dpt type) {
		synchronized (_lock) {
			_received++;
			String value = type == null ? decode(telegram)
				: DatapointCodec.decode(type, telegram.service(), telegram.data());
			AddressInfo address = _index.address(telegram.destination());
			String name = address != null ? address.name() : telegram.name() == null ? "" : telegram.name();
			MonitorRow row = _board.get(telegram.destination());
			if (row == null) {
				row = new MonitorRow(Integer.valueOf(telegram.destination()));
				describe(row, telegram.destination());
				_board.put(telegram.destination(), row);
			}
			if (address == null && !name.isEmpty()) {
				row.put("name", name);
			}
			int count = row.get("count") instanceof Integer n ? n.intValue() : 0;
			row.put("count", Integer.valueOf(count + 1));
			row.put("time", format(telegram.time(), "HH:mm:ss"));
			row.put("millis", Long.valueOf(telegram.time()));
			row.put("service", telegram.service().classifier());
			if (telegram.service() != BusTelegram.Service.READ) {
				row.put("value", value);
				row.put("sender", senderLabel(telegram));
			}

			MonitorRow entry = new MonitorRow(Long.valueOf(++_sequence));
			entry.put("number", Long.valueOf(_sequence));
			entry.put("time", format(telegram.time(), "HH:mm:ss.SSS"));
			entry.put("sender", senderLabel(telegram));
			entry.put("source", telegram.sourceText());
			entry.put("address", _index.format(telegram.destination()));
			entry.put("name", name);
			entry.put("value", value);
			entry.put("kind", Resources.getInstance().getString(I18NConstants.EVENT_KIND_KNX));
			entry.put("service", classifier("ehome.monitor:TelegramService#" + telegram.service().classifier()));
			entry.put("priority", classifier("ehome.monitor:TelegramPriority#" + telegram.priorityName()));
			entry.put("repeated", Boolean.valueOf(telegram.repeated()));
			entry.put("hops", Integer.valueOf(telegram.hopCount()));
			entry.put("data", telegram.hex());
			entry.put("own", Boolean.valueOf(telegram.own()));
			_log.addFirst(entry);
			while (_log.size() > LOG_LIMIT) {
				_log.removeLast();
			}

			if (_recording != null) {
				_pending.add(telegram);
				scheduleFlush(_pending.size() >= BATCH_SIZE ? 0 : _batchMillis);
			}
			schedule(this::changed, FRESH_MILLIS + 50);
		}
		changed();
	}

	/**
	 * Takes an event of the link that is no group telegram: a row of the log of its own.
	 */
	public void receive(LinkEvent event) {
		synchronized (_lock) {
			MonitorRow entry = new MonitorRow(Long.valueOf(++_sequence));
			entry.put("number", Long.valueOf(_sequence));
			entry.put("time", format(event.time(), "HH:mm:ss.SSS"));
			entry.put("kind", Resources.getInstance().getString(kindLabel(event.kind())));
			entry.put("event", event.kind().externalName());
			entry.put("sender", Resources.getInstance().getString(describe(event)));
			entry.put("source", "");
			entry.put("address", "");
			entry.put("name", event.name() == null ? "" : event.name());
			entry.put("value", DatapointCodec.valueText(null, event.value()));
			entry.put("data", "");
			entry.put("own", Boolean.FALSE);
			_log.addFirst(entry);
			while (_log.size() > LOG_LIMIT) {
				_log.removeLast();
			}
		}
		changed();
	}

	private static ResKey kindLabel(LinkEvent.Kind kind) {
		switch (kind) {
			case ZIGBEE:
				return I18NConstants.EVENT_KIND_ZIGBEE;
			case RULE:
				return I18NConstants.EVENT_KIND_RULE;
			case COMMAND:
				return I18NConstants.EVENT_KIND_COMMAND;
			case IMAGE:
				return I18NConstants.EVENT_KIND_IMAGE;
			case GAP:
				return I18NConstants.EVENT_KIND_GAP;
			case RESET:
				return I18NConstants.EVENT_KIND_RESET;
			default:
				return I18NConstants.EVENT_KIND_OTHER;
		}
	}

	/**
	 * What happened, for a person.
	 */
	private static ResKey describe(LinkEvent event) {
		Map<String, Object> fields = event.fields();
		switch (event.kind()) {
			case ZIGBEE: {
				Object device = text(fields.get("ieee"));
				Object endpoint = text(fields.get("ep"));
				Object cluster = text(fields.get("cluster"));
				switch (text(fields.get("event"))) {
					case "report":
						return I18NConstants.EVENT_ZIGBEE_REPORT__DEVICE_ENDPOINT_CLUSTER_ATTRIBUTE.fill(device, endpoint,
							cluster, text(fields.get("attr")));
					case "action":
						return I18NConstants.EVENT_ZIGBEE_ACTION__DEVICE_ENDPOINT_CLUSTER_COMMAND.fill(device, endpoint,
							cluster, text(fields.get("cmd")));
					default:
						return I18NConstants.EVENT_ZIGBEE_COMMAND__DEVICE_ENDPOINT_CLUSTER_COMMAND.fill(device, endpoint,
							cluster, text(fields.get("cmd")));
				}
			}
			case RULE:
				return I18NConstants.EVENT_RULE__RULE.fill(text(fields.get("rule")));
			case COMMAND: {
				String via = text(fields.get("via"));
				return I18NConstants.EVENT_COMMAND__ORIGIN
					.fill(text(fields.get("origin")) + (via.isEmpty() ? "" : " (" + via + ")"));
			}
			case IMAGE:
				switch (text(fields.get("event"))) {
					case "unload":
						return I18NConstants.EVENT_IMAGE_UNLOAD;
					case "reload":
						return I18NConstants.EVENT_IMAGE_RELOAD;
					default:
						return I18NConstants.EVENT_IMAGE_LOAD__PROJECT_REVISION_SOURCE.fill(text(fields.get("project")),
							text(fields.get("revision")), text(fields.get("source")));
				}
			case GAP:
				return I18NConstants.EVENT_GAP__COUNT.fill(text(fields.get("count")));
			case RESET:
				return I18NConstants.EVENT_RESET;
			default:
				return I18NConstants.EVENT_OTHER__KIND.fill(text(fields.get("kind")));
		}
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	/**
	 * Sends a value to a group address (group write).
	 *
	 * @param address
	 *        The group address: its 16-bit number or its text in the notation of the project.
	 * @param value
	 *        The value, see {@link DatapointCodec#encodeWrite(Dpt, Object)}.
	 * @param type
	 *        The datapoint type to encode the value with (e.g. <code>1.001</code>); <code>null</code>
	 *        or empty for the one of the address in the project.
	 * @throws TopLogicException
	 *         If sending is not allowed, the value does not fit the type, or the link fails.
	 */
	public void send(Object address, Object value, String type) {
		int destination = destination(address);
		boolean typed = type != null && !type.isBlank();
		Dpt dpt = typed ? Dpt.parse(type) : dpt(destination);
		transmit(destination, DatapointCodec.encodeWrite(dpt, value), typed ? dpt : null);
	}

	/**
	 * Sends a read request to a group address; the answer appears as any other telegram.
	 *
	 * @see #send(Object, Object, String)
	 */
	public void read(Object address) {
		transmit(destination(address), DatapointCodec.encodeRead(), null);
	}

	private void transmit(int destination, byte[] apdu, Dpt type) {
		BusLink link;
		synchronized (_lock) {
			if (_link == null) {
				throw new TopLogicException(I18NConstants.ERROR_NOT_CONNECTED);
			}
			if (!_sending) {
				throw new TopLogicException(I18NConstants.ERROR_SENDING_DISABLED);
			}
			link = _link;
		}
		try {
			link.send(destination, apdu);
		} catch (BusException ex) {
			throw new TopLogicException(I18NConstants.ERROR_SEND_FAILED__DETAIL.fill(ex.getMessage()), ex);
		}
		if (link.reportsOwnTelegrams()) {
			// The telegram comes back from the link.
			return;
		}
		BusTelegram.Service service =
			BusTelegram.Service.ofCode(tuwien.auto.calimero.DataUnitBuilder.getAPDUService(apdu));
		receive(new BusTelegram(System.currentTimeMillis(), link.localAddress(), destination, service,
			BusTelegram.PRIORITY_LOW, false, BusTelegram.DEFAULT_HOP_COUNT, DatapointCodec.data(apdu), true), type);
	}

	/**
	 * The 16-bit group address of an address given as number or as text.
	 */
	public int destination(Object address) {
		if (address instanceof Number number) {
			return number.intValue();
		}
		if (address instanceof GA ga) {
			return ga.getValue();
		}
		if (address == null || address.toString().isBlank()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_ADDRESS);
		}
		synchronized (_lock) {
			return _index.parse(address.toString());
		}
	}

	private Dpt dpt(int destination) {
		synchronized (_lock) {
			AddressInfo info = _index.address(destination);
			return info == null ? null : info.dpt();
		}
	}

	/**
	 * What the send form shows for an address: its text, name, datapoint type, the kind of input, its
	 * current value and, for an address the project does not know, the hint how to send to it.
	 *
	 * <p>
	 * Besides <code>address</code>, <code>name</code>, <code>dpt</code>, <code>kind</code>
	 * ({@link DatapointCodec.Kind#externalName()}), <code>value</code>, <code>label</code> and
	 * <code>hint</code>, the dictionary carries what the input of the kind needs:
	 * </p>
	 * <ul>
	 * <li><code>off</code>, <code>on</code>: the two values of a switch ("0", "1" for another
	 * kind),</li>
	 * <li><code>unit</code>: the unit of the values for the user, empty if none
	 * ({@link DatapointCodec#unit(Dpt)}),</li>
	 * <li><code>min</code>, <code>max</code>: the range of a percentage or a byte,</li>
	 * <li><code>up</code>, <code>down</code>, <code>steps</code>: what the directions of a step do,
	 * in the user's language, and the step sizes to choose from
	 * ({@link DatapointCodec#stepSizes()}),</li>
	 * <li><code>options</code>: the named values of an enumeration, empty for another kind
	 * ({@link DatapointCodec#choices(Dpt)}),</li>
	 * <li><code>days</code>: the days of a time of day ({@link DatapointCodec#days()}).</li>
	 * </ul>
	 *
	 * @param address
	 *        The group address as number or text; <code>null</code> for none.
	 * @param type
	 *        The datapoint type entered for the address, <code>null</code> or empty for the one of the
	 *        project.
	 * @return The dictionary, <code>null</code> for no address; for a text that is no address one
	 *         with the entry <code>problem</code>.
	 */
	public Map<String, Object> target(Object address, String type) {
		if (address == null || address.toString().isBlank()) {
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		int destination;
		Dpt dpt;
		try {
			destination = destination(address);
			dpt = type == null || type.isBlank() ? dpt(destination) : Dpt.parse(type);
		} catch (TopLogicException ex) {
			result.put("problem", Resources.getInstance().getString(ex.getErrorKey()));
			result.put("kind", "none");
			return result;
		}
		synchronized (_lock) {
			AddressInfo info = _index.address(destination);
			MonitorRow row = _board.get(destination);
			result.put("address", _index.format(destination));
			result.put("name", info == null ? "" : info.name());
			result.put("dpt", dpt == null ? "" : dpt.code());
			result.put("kind", DatapointCodec.kind(dpt).externalName());
			String[] values = DatapointCodec.switchValues(dpt);
			result.put("off", values == null ? "0" : values[0]);
			result.put("on", values == null ? "1" : values[1]);
			Object current = row == null ? null : row.get("value");
			result.put("value", current == null ? "" : current);
			Resources resources = Resources.getInstance();
			String label = resources.getString(I18NConstants.TARGET__ADDRESS_NAME_TYPE_VALUE.fill(
				_index.format(destination), info == null ? "" : info.name(), dpt == null ? "–" : dpt.code(),
				current == null || current.toString().isEmpty() ? "–" : current));
			if (values != null) {
				label += " " + resources.getString(I18NConstants.TARGET_VALUES__OFF_ON.fill(values[0], values[1]));
			}
			String unit = DatapointCodec.unit(dpt);
			result.put("unit", unit);
			int[] range = DatapointCodec.range(dpt);
			if (range != null) {
				result.put("min", Integer.valueOf(range[0]));
				result.put("max", Integer.valueOf(range[1]));
				label += " " + resources.getString(DatapointCodec.rangeText(dpt, range[0], range[1]));
			}
			ResKey directions = DatapointCodec.stepDirections(dpt);
			if (directions != null) {
				result.put("steps", DatapointCodec.stepSizes());
				label += " " + resources.getString(directions);
			}
			result.put("options", DatapointCodec.choices(dpt));
			if (DatapointCodec.kind(dpt) == DatapointCodec.Kind.TIME) {
				result.put("days", DatapointCodec.days());
			}
			if (info == null && (type == null || type.isBlank())) {
				String hint = resources.getString(I18NConstants.TARGET_UNKNOWN__ADDRESS.fill(_index.format(destination)));
				result.put("hint", hint);
				label += " " + hint;
			}
			result.put("label", label);
		}
		return result;
	}

	/**
	 * Starts a recording: the telegrams received from now on are stored with the project.
	 *
	 * <p>
	 * Called in the request of the user; the recording is created in a transaction of its own.
	 * </p>
	 *
	 * @param name
	 *        The name of the recording; empty for a name with the current time.
	 * @return The recording (<code>ehome.monitor:Recording</code>).
	 */
	public TLObject startRecording(String name) {
		synchronized (_lock) {
			if (_recording != null) {
				throw new TopLogicException(I18NConstants.ERROR_RECORDING_RUNNING);
			}
			if (_link == null) {
				throw new TopLogicException(I18NConstants.ERROR_NOT_CONNECTED);
			}
			Date now = new Date();
			String recordingName = name == null || name.isBlank()
				? Resources.getInstance().getString(
					I18NConstants.DEFAULT_RECORDING_NAME__TIME.fill(format(now.getTime(), "yyyy-MM-dd HH:mm:ss")))
				: name.trim();
			TLObject recording;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				recording = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.monitor:Recording"));
				recording.tUpdateByName("name", recordingName);
				recording.tUpdateByName("gateway", _gateway);
				recording.tUpdateByName("start", now);
				recording.tUpdateByName("count", Integer.valueOf(0));
				getProject().tAdd((TLReference) TLModelUtil.findPart("ehome.project:Project#recordings"), recording);
				tx.commit();
			}
			if (_context == null) {
				_context = ThreadContextManager.getSubSession();
			}
			_recording = recording;
			_recorded = 0;
			_pending.clear();
		}
		changed();
		return _recording;
	}

	/**
	 * Ends the running recording, if there is one: stores what is not stored yet and sets its end.
	 */
	public void stopRecording() {
		synchronized (_lock) {
			if (_recording == null) {
				return;
			}
			if (_flush != null) {
				_flush.cancel(false);
				_flush = null;
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				storePending();
				if (_recording.tValid()) {
					_recording.tUpdateByName("end", new Date());
				}
				tx.commit();
			} catch (RuntimeException ex) {
				Logger.error("Ending the recording failed.", ex, GroupMonitor.class);
			}
			_recording = null;
			_pending.clear();
		}
		changed();
	}

	/**
	 * The running recording, <code>null</code> if none runs.
	 */
	public TLObject getRecording() {
		synchronized (_lock) {
			return _recording;
		}
	}

	/**
	 * Stores the telegrams of the running recording that are not stored yet, in one transaction.
	 */
	public void flushRecording() {
		synchronized (_lock) {
			_flush = null;
			if (_recording == null || _pending.isEmpty()) {
				return;
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				storePending();
				tx.commit();
			} catch (RuntimeException ex) {
				Logger.error("Storing telegrams of the recording failed.", ex, GroupMonitor.class);
				_pending.clear();
			}
		}
		changed();
	}

	/** Called while holding {@link #_lock}, in a transaction. */
	private void storePending() {
		if (_pending.isEmpty() || !_recording.tValid()) {
			_pending.clear();
			return;
		}
		TLClass type = (TLClass) TLModelUtil.findType("ehome.monitor:Telegram");
		TLReference telegrams = (TLReference) TLModelUtil.findPart("ehome.monitor:Recording#telegrams");
		GroupAddressStyle style = GroupAddressStyle.fromModel(getProject().tValueByName("groupAddressStyle"));
		for (BusTelegram telegram : _pending) {
			TLObject stored = ModelService.getInstance().getFactory().createObject(type);
			stored.tUpdateByName("number", Integer.valueOf(++_recorded));
			stored.tUpdateByName("time", new Date(telegram.time()));
			stored.tUpdateByName("source", telegram.sourceText());
			DeviceInfo device = _index.device(telegram.source());
			if (device != null && device.device().tValid()) {
				stored.tUpdateByName("sender", device.device());
			}
			stored.tUpdateByName("address", new GA(telegram.destination(), style));
			AddressInfo address = _index.address(telegram.destination());
			if (address != null && address.groupAddress().tValid()) {
				stored.tUpdateByName("groupAddress", address.groupAddress());
			}
			stored.tUpdateByName("service",
				classifier("ehome.monitor:TelegramService#" + telegram.service().classifier()));
			stored.tUpdateByName("priority", classifier("ehome.monitor:TelegramPriority#" + telegram.priorityName()));
			stored.tUpdateByName("repeated", Boolean.valueOf(telegram.repeated()));
			stored.tUpdateByName("hopCount", Integer.valueOf(telegram.hopCount()));
			stored.tUpdateByName("data", telegram.hex());
			stored.tUpdateByName("value", decode(telegram));
			stored.tUpdateByName("own", Boolean.valueOf(telegram.own()));
			_recording.tAdd(telegrams, stored);
		}
		_recording.tUpdateByName("count", Integer.valueOf(_recorded));
		_pending.clear();
	}

	/**
	 * The state of the monitor as the views read it: a dictionary with
	 * <ul>
	 * <li><code>monitor</code>: this monitor,</li>
	 * <li><code>connected</code>, <code>sending</code>, <code>recording</code>, <code>paused</code>:
	 * whether the monitor is attached, may send, records (the recording), holds the log,</li>
	 * <li><code>status</code>, <code>sendingStatus</code>, <code>recordingStatus</code>: these states
	 * for a person,</li>
	 * <li><code>board</code>: the rows of the state board by group address,</li>
	 * <li><code>log</code>: the rows of the log, newest first,</li>
	 * <li><code>revision</code>: a number that differs in every snapshot.</li>
	 * </ul>
	 * Texts are written in the language of the calling thread.
	 */
	public Map<String, Object> snapshot() {
		synchronized (_lock) {
			Resources resources = Resources.getInstance();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("monitor", this);
			result.put("revision", Long.valueOf(++_revision));
			result.put("connected", Boolean.valueOf(_link != null));
			result.put("sending", Boolean.valueOf(_sending));
			result.put("recording", _recording);
			result.put("paused", Boolean.valueOf(_pausedLog != null));
			ResKey status;
			String problem = _link == null ? null : _link.problem();
			if (problem != null) {
				status = _viaController
					? I18NConstants.STATUS_CONTROLLER_INTERRUPTED__CONTROLLER_REASON.fill(_gateway, problem)
					: I18NConstants.STATUS_INTERRUPTED__GATEWAY_REASON.fill(_gateway, problem);
			} else if (_link != null) {
				int local = _link.localAddress();
				Long count = Long.valueOf(_received);
				if (_viaController) {
					status = local == 0
						? I18NConstants.STATUS_CONTROLLER_CONNECTED__CONTROLLER_COUNT.fill(_gateway, count)
						: I18NConstants.STATUS_CONTROLLER_CONNECTED__CONTROLLER_ADDRESS_COUNT.fill(_gateway,
							BusTelegram.individualAddress(local), count);
				} else {
					status = local == 0
						? I18NConstants.STATUS_CONNECTED__GATEWAY_COUNT.fill(_gateway, count)
						: I18NConstants.STATUS_CONNECTED__GATEWAY_ADDRESS_COUNT.fill(_gateway,
							BusTelegram.individualAddress(local), count);
				}
			} else if (_lostReason != null) {
				status = _viaController
					? I18NConstants.STATUS_CONTROLLER_LOST__CONTROLLER_REASON.fill(_gateway, _lostReason)
					: I18NConstants.STATUS_LOST__GATEWAY_REASON.fill(_gateway, _lostReason);
			} else {
				status = I18NConstants.STATUS_DISCONNECTED;
			}
			result.put("status", resources.getString(status));
			result.put("sendingStatus",
				resources.getString(_sending ? I18NConstants.SENDING_ALLOWED : I18NConstants.SENDING_LOCKED));
			result.put("recordingStatus", resources.getString(_recording == null ? I18NConstants.RECORDING_NONE
				: I18NConstants.RECORDING_RUNNING__NAME_COUNT.fill(_recording.tValueByName("name"),
					Integer.valueOf(_recorded + _pending.size()))));
			result.put("logStatus", _pausedLog == null ? null : resources.getString(I18NConstants.LOG_PAUSED));
			long now = System.currentTimeMillis();
			List<MonitorRow> board = new ArrayList<>(_board.size());
			for (MonitorRow row : _board.values()) {
				MonitorRow copy = row.copy();
				copy.put("fresh", Boolean.valueOf(row.get("millis") instanceof Long millis
					&& now - millis.longValue() < FRESH_MILLIS));
				board.add(copy);
			}
			result.put("board", board);
			result.put("log", new ArrayList<>(_pausedLog != null ? _pausedLog : _log));
			return result;
		}
	}

	/**
	 * The telegrams the monitor received (and sent) since it was connected or cleared.
	 */
	public long getReceived() {
		synchronized (_lock) {
			return _received;
		}
	}

	/** Called while holding {@link #_lock}. */
	private void describe(MonitorRow row, int destination) {
		AddressInfo info = _index.address(destination);
		row.put("address", _index.format(destination));
		row.put("number", Integer.valueOf(destination));
		row.put("name", info == null ? "" : info.name());
		row.put("dpt", info == null || info.dpt() == null ? "" : info.dpt().code());
		row.put("dptLabel", info == null ? "" : info.dptLabel());
		row.put("known", Boolean.valueOf(info != null));
		row.put("kind", DatapointCodec.kind(info == null ? null : info.dpt()).externalName());
	}

	/**
	 * The value of a telegram: the one the link reports, else decoded by the type of its address.
	 * Called while holding {@link #_lock}.
	 */
	private String decode(BusTelegram telegram) {
		AddressInfo info = _index.address(telegram.destination());
		Dpt dpt = info == null ? null : info.dpt();
		if (telegram.value() != null && telegram.service() != BusTelegram.Service.READ) {
			return DatapointCodec.valueText(dpt, telegram.value());
		}
		return DatapointCodec.decode(dpt, telegram.service(), telegram.data());
	}

	/** Called while holding {@link #_lock}. */
	private String senderLabel(BusTelegram telegram) {
		DeviceInfo device = _index.device(telegram.source());
		return device != null ? device.label() : telegram.sourceText();
	}

	private String format(long time, String pattern) {
		SimpleDateFormat format = new SimpleDateFormat(pattern);
		format.setTimeZone(_timeZone);
		return format.format(new Date(time));
	}

	private static final Map<String, Object> CLASSIFIERS = new ConcurrentHashMap<>();

	/**
	 * The classifier with the given name; looked up once, so that the threads of the link need no
	 * access to the model.
	 */
	private static Object classifier(String name) {
		return CLASSIFIERS.computeIfAbsent(name, TLModelUtil::findPart);
	}

	/**
	 * Looks up the classifiers of the telegrams in the request attaching the monitor.
	 */
	private static void initClassifiers() {
		for (BusTelegram.Service service : BusTelegram.Service.values()) {
			classifier("ehome.monitor:TelegramService#" + service.classifier());
		}
		for (String priority : new String[] { "system", "normal", "urgent", "low" }) {
			classifier("ehome.monitor:TelegramPriority#" + priority);
		}
	}

	/** Called while holding {@link #_lock}. */
	private void scheduleFlush(long delay) {
		if (_flush != null) {
			if (delay > 0 || _flush.getDelay(TimeUnit.MILLISECONDS) <= 0) {
				return;
			}
			_flush.cancel(false);
		}
		_flush = schedule(() -> inContext(this::flushRecording), delay);
	}

	/** Called while holding {@link #_lock}. */
	private ScheduledFuture<?> schedule(Runnable action, long delay) {
		if (_executor == null) {
			_executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "group-monitor");
				thread.setDaemon(true);
				return thread;
			});
		}
		return _executor.schedule(() -> {
			try {
				action.run();
			} catch (RuntimeException ex) {
				Logger.error("Work of the group monitor failed.", ex, GroupMonitor.class);
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	private void inContext(Runnable action) {
		SubSessionContext context = _context;
		if (context == null || context == ThreadContextManager.getSubSession()) {
			action.run();
		} else {
			ThreadContextManager.inContext(context, action::run);
		}
	}

	private void changed() {
		Publisher publisher = _publisher;
		if (publisher != null) {
			publisher.changed();
		}
	}

	private static TimeZone timeZone(SubSessionContext context) {
		return context == null ? null : context.getCurrentTimeZone();
	}

}

/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import com.top_logic.basic.Logger;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.base.context.TLSubSessionContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;

/**
 * The downloads of a project, one device after another (haumacher/ehome #200, #218): one queue per
 * project, runtime state of this application (no model), shown in the tab "Programming" of every
 * selection in the tree of the devices.
 *
 * <p>
 * Each {@link Entry} is a device with the kind of its download (full, or the parameters only,
 * haumacher/ehome #195) and what became of it (waiting, running with its progress, done, failed with
 * the reason, cancelled, aborted). {@link #enqueue(TLObject, boolean)} appends a device at the end and
 * starts the worker if none runs; the worker is a background task of the application (not a job of
 * a view), so the user keeps working, and works through the waiting entries strictly in order, one
 * connection and one device at a time, each download vetoed, planned, performed and recorded as a
 * single one ({@link DownloadService}, {@link DownloadRecord}). A failure does not stop the queue; a
 * device not reached keeps its pending state.
 * </p>
 *
 * <p>
 * A device waiting already is not enqueued a second time: a request for the full download raises a
 * waiting entry of the parameters only to the full one, which covers the parameters; a request for the
 * parameters only leaves a waiting full one as it is. A device whose download runs no longer waits and
 * is enqueued anew. A waiting entry is cancelled ({@link #cancel(Collection)},
 * {@link #cancelWaiting()}) without touching its device; the running one is aborted
 * ({@link #abortRunning()}) before its next operation, and the queue goes on with the next device.
 * Finished entries stay until {@link #clearFinished()}.
 * </p>
 *
 * <p>
 * Each download runs in a sub-session derived from the one of the request that enqueued it, with the
 * user who enqueued it. While the worker runs, it holds the project's {@link DownloadLock}, so that the
 * jobs writing outside the queue (the address, the layout of the modules, a restore) are refused
 * meanwhile; while one of those runs, the worker waits for it.
 * </p>
 *
 * <p>
 * Every change of a queue is announced to the listeners ({@link #addListener(Consumer)}) with the key
 * of its project ({@link #key(TLObject)}), which {@link DownloadQueueSource} passes to the views
 * showing the queue of that project.
 * </p>
 */
public class DownloadQueue {

	/**
	 * Opens the connection a download writes into its device through.
	 */
	public interface Opener {

		/**
		 * Opens the connection for the given plan of the given device.
		 */
		CalimeroWriter open(TLObject device, DownloadPlan plan) throws BusException;
	}

	/**
	 * The connection over the project's path to the bus ({@link BusPath#of(TLObject)}).
	 */
	public static final Opener BUS = (device, plan) -> CalimeroWriter.open(BusPath.of(device), plan.getIndividualAddress());

	/** How long the worker waits before it tries again to take a lock held by another download. */
	private static final long LOCK_RETRY_MILLIS = 250;

	/** The queues of the projects, by the key of the project ({@link #key(TLObject)}). */
	private static final Map<Object, DownloadQueue> QUEUES = new ConcurrentHashMap<>();

	private static final List<Consumer<Object>> LISTENERS = new CopyOnWriteArrayList<>();

	/**
	 * What became of an entry.
	 */
	public enum State {
		/** Not started yet. */
		WAITING,
		/** Its download runs. */
		RUNNING,
		/** Downloaded and recorded. */
		DONE,
		/** The download was refused or failed. */
		FAILED,
		/** Cancelled before it started: its device is untouched. */
		CANCELLED,
		/** Aborted while its download ran. */
		ABORTED;

		/**
		 * Whether the entry is finished.
		 */
		public boolean isFinal() {
			return this != WAITING && this != RUNNING;
		}
	}

	/**
	 * A device of the queue with the kind of its download and what became of it.
	 */
	public final class Entry {

		private final TLObject _device;

		private final String _label;

		/** Who enqueued the download, <code>null</code> without a person (a test in the system context). */
		private final Person _person;

		/** The language of the texts the download writes (its report), the one of the person enqueuing it. */
		private final Locale _locale;

		/** The time zone of the person enqueuing the download. */
		private final TimeZone _timeZone;

		private boolean _parametersOnly;

		private State _state = State.WAITING;

		private int _done;

		private int _count;

		private ResKey _details;

		private Map<String, Object> _report;

		private volatile boolean _abort;

		Entry(TLObject device, boolean parametersOnly, Person person, Locale locale, TimeZone timeZone) {
			_device = device;
			_label = DownloadQueue.label(device);
			_parametersOnly = parametersOnly;
			_person = person;
			_locale = locale;
			_timeZone = timeZone;
		}

		/**
		 * The queue of this entry.
		 */
		public DownloadQueue queue() {
			return DownloadQueue.this;
		}

		/**
		 * The device.
		 */
		public TLObject device() {
			return _device;
		}

		/**
		 * The device for a person (its name and its individual address).
		 */
		public String label() {
			return _label;
		}

		/**
		 * Whether the download writes the parameters only ({@link DownloadPlan.Kind#PARAMETERS}).
		 */
		public boolean parametersOnly() {
			synchronized (DownloadQueue.this) {
				return _parametersOnly;
			}
		}

		/**
		 * The kind of the download.
		 */
		public DownloadPlan.Kind kind() {
			return parametersOnly() ? DownloadPlan.Kind.PARAMETERS : DownloadPlan.Kind.FULL;
		}

		/**
		 * What became of the entry.
		 */
		public State state() {
			synchronized (DownloadQueue.this) {
				return _state;
			}
		}

		/**
		 * The details of the state: the operation running, the outcome, the reason of a failure;
		 * <code>null</code> while the entry waits.
		 */
		public ResKey details() {
			synchronized (DownloadQueue.this) {
				return _details;
			}
		}

		/**
		 * The report of the finished download ({@link ProgrammingReport#toMap(ResKey, boolean)}), as
		 * the single download shows it; <code>null</code> before the download ended and for an entry
		 * refused, cancelled or aborted.
		 */
		public Map<String, Object> report() {
			synchronized (DownloadQueue.this) {
				return _report;
			}
		}

		/**
		 * The operations performed of the download, while it runs and after.
		 */
		public int done() {
			synchronized (DownloadQueue.this) {
				return _done;
			}
		}

		/**
		 * The number of operations of the download, <code>0</code> before it starts.
		 */
		public int count() {
			synchronized (DownloadQueue.this) {
				return _count;
			}
		}

		void progress(int done, int count, ResKey details) {
			synchronized (DownloadQueue.this) {
				_done = done;
				_count = count;
				_details = details;
				_version++;
			}
			changed();
		}

		/**
		 * The download became the complete one: the device does not hold the project's application
		 * (see {@link DownloadService}).
		 */
		void completeInstead() {
			synchronized (DownloadQueue.this) {
				_parametersOnly = false;
				_version++;
			}
			changed();
		}

		void finish(State state, ResKey details, Map<String, Object> report) {
			synchronized (DownloadQueue.this) {
				_state = state;
				_details = details;
				_report = report;
				_version++;
			}
			changed();
		}

		@Override
		public String toString() {
			return _label + " " + state();
		}
	}

	/**
	 * Thrown out of a download by {@link Entry#_abort}: the operation not performed.
	 */
	private static final class Aborted extends RuntimeException {

		final int _number;

		final int _count;

		Aborted(int number, int count) {
			super("Aborted before operation " + number + " of " + count + ".", null, false, false);
			_number = number;
			_count = count;
		}
	}

	private final TLObject _project;

	private final Object _key;

	private final Opener _opener;

	private final Executor _executor;

	private final List<Entry> _entries = new ArrayList<>();

	private boolean _running;

	private Entry _current;

	private long _version;

	/**
	 * Creates a {@link DownloadQueue} of the given project.
	 *
	 * <p>
	 * The application has one queue per project ({@link #of(TLObject)}); a test creates its own with
	 * its own connections and worker threads.
	 * </p>
	 *
	 * @param opener
	 *        How a download connects to its device: {@link #BUS} over the project's path to the bus.
	 * @param executor
	 *        Where the worker runs.
	 */
	public DownloadQueue(TLObject project, Opener opener, Executor executor) {
		_project = project;
		_key = key(project);
		_opener = opener;
		_executor = executor;
	}

	/**
	 * The queue of the project of the given object (a device or the project), created on first use.
	 */
	public static DownloadQueue of(TLObject object) {
		TLObject project = BusPath.project(object);
		return QUEUES.computeIfAbsent(key(project),
			key -> new DownloadQueue(project, BUS, task -> SchedulerService.getInstance().execute(task)));
	}

	/**
	 * The queue of the project of the given object (a device or the project), <code>null</code> if the
	 * project has none yet.
	 */
	public static DownloadQueue find(TLObject object) {
		return object == null || !object.tValid() ? null : QUEUES.get(key(object));
	}

	/**
	 * The key of the project of the given object (a device or the project), by which the listeners
	 * are told which queue changed.
	 */
	public static Object key(TLObject object) {
		return BusPath.project(object).tId();
	}

	/**
	 * Calls the given listener with the key of the project ({@link #key(TLObject)}) whenever a queue
	 * or one of its entries changes, from the thread changing it.
	 */
	public static void addListener(Consumer<Object> listener) {
		LISTENERS.add(listener);
	}

	/**
	 * Removes a listener added by {@link #addListener(Consumer)}.
	 */
	public static void removeListener(Consumer<Object> listener) {
		LISTENERS.remove(listener);
	}

	/**
	 * The project.
	 */
	public TLObject project() {
		return _project;
	}

	/**
	 * The entries, in the order they are worked through.
	 */
	public synchronized List<Entry> entries() {
		return List.copyOf(_entries);
	}

	/**
	 * A number changing with every change of the queue or of one of its entries.
	 */
	public synchronized long version() {
		return _version;
	}

	/**
	 * Whether the worker is working through the entries.
	 */
	public synchronized boolean isRunning() {
		return _running;
	}

	/**
	 * The entry whose download runs, <code>null</code> if none.
	 */
	public synchronized Entry current() {
		return _current;
	}

	/**
	 * The entries in the given state.
	 */
	public synchronized List<Entry> entries(State state) {
		return _entries.stream().filter(e -> e._state == state).toList();
	}

	/**
	 * The entry of the given device waiting for its download, <code>null</code> if none waits.
	 */
	public synchronized Entry waiting(TLObject device) {
		for (Entry entry : _entries) {
			if (entry._state == State.WAITING && entry._device == device) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * Why no device of the project of the given object (a device or the project) can be enqueued now,
	 * <code>null</code> if it can: writing to the bus is switched off, or the project's path to the
	 * bus cannot be used.
	 */
	public static ResKey enqueueVeto(TLObject object) {
		if (!CalimeroWriter.writeEnabled()) {
			return I18NConstants.ERROR_WRITE_DISABLED;
		}
		return BusPath.veto(object);
	}

	/**
	 * Why a download of the given kind into the device cannot be enqueued, as far as the queue
	 * decides: the device waits already with the same kind or with the full download, which covers
	 * the parameters; <code>null</code> if it can.
	 */
	public synchronized ResKey waitingVeto(TLObject device, boolean parametersOnly) {
		Entry waiting = waiting(device);
		if (waiting != null && (parametersOnly || !waiting._parametersOnly)) {
			return I18NConstants.QUEUE_DEVICE_WAITS;
		}
		return null;
	}

	/**
	 * Appends a download of the given kind into the device at the end of the queue and starts the
	 * worker if none runs.
	 *
	 * <p>
	 * A device waiting already is not appended again: a request for the full download raises its
	 * entry of the parameters only to the full download, a request for the parameters only leaves it
	 * as it is. The parameters only are requested in vain for a device whose program does not allow
	 * them ({@link #allowsParametersOnly(TLObject)}): it gets the full download.
	 * </p>
	 *
	 * @return Whether the queue changed.
	 * @throws IllegalArgumentException
	 *         If the device is not one of the project.
	 */
	public boolean enqueue(TLObject device, boolean parametersOnly) {
		return enqueue(List.of(device), d -> Boolean.valueOf(parametersOnly)) > 0;
	}

	/**
	 * Appends the downloads into the given devices, in their order, each of the kind the given
	 * function says (whether it writes the parameters only), as {@link #enqueue(TLObject, boolean)}
	 * does for one.
	 *
	 * @return The number of entries appended or raised to the full download.
	 */
	public int enqueue(Collection<? extends TLObject> devices, Function<TLObject, Boolean> parametersOnly) {
		TLSubSessionContext session = TLContextManager.getSubSession();
		Person person = session == null ? null : session.getPerson();
		Locale locale = session == null ? null : session.getCurrentLocale();
		TimeZone timeZone = session == null ? null : session.getCurrentTimeZone();
		List<Entry> created = new ArrayList<>();
		for (TLObject device : devices) {
			if (BusPath.project(device) != _project) {
				throw new IllegalArgumentException("The device " + device + " is not one of the project " + _project + ".");
			}
			boolean parameters = Boolean.TRUE.equals(parametersOnly.apply(device)) && allowsParametersOnly(device);
			created.add(new Entry(device, parameters, person, locale, timeZone));
		}
		int result = 0;
		boolean start = false;
		synchronized (this) {
			for (Entry entry : created) {
				Entry waiting = waiting(entry._device);
				if (waiting == null) {
					_entries.add(entry);
					result++;
				} else if (waiting._parametersOnly && !entry._parametersOnly) {
					waiting._parametersOnly = false;
					result++;
				}
			}
			if (result > 0) {
				_version++;
				if (!_running) {
					_running = true;
					start = true;
				}
			}
		}
		if (result > 0) {
			changed();
		}
		if (start) {
			startWorker();
		}
		return result;
	}

	/**
	 * Cancels the given entries that wait; their devices are not touched.
	 *
	 * @return The number of entries cancelled.
	 */
	public int cancel(Collection<Entry> entries) {
		int result = 0;
		synchronized (this) {
			for (Entry entry : entries) {
				if (entry.queue() == this && entry._state == State.WAITING) {
					entry._state = State.CANCELLED;
					entry._details = I18NConstants.QUEUE_CANCELLED;
					result++;
				}
			}
			if (result > 0) {
				_version++;
			}
		}
		if (result > 0) {
			changed();
		}
		return result;
	}

	/**
	 * Cancels every waiting entry.
	 *
	 * @return The number of entries cancelled.
	 */
	public int cancelWaiting() {
		return cancel(entries());
	}

	/**
	 * Aborts the download running now before its next operation; the queue goes on with the next
	 * entry.
	 *
	 * @return Whether a download runs.
	 */
	public boolean abortRunning() {
		synchronized (this) {
			if (_current == null) {
				return false;
			}
			_current._abort = true;
			_version++;
		}
		changed();
		return true;
	}

	/**
	 * Removes the finished entries (done, failed, cancelled, aborted).
	 *
	 * @return The number of entries removed.
	 */
	public int clearFinished() {
		int result;
		synchronized (this) {
			int before = _entries.size();
			_entries.removeIf(entry -> entry._state.isFinal());
			result = before - _entries.size();
			if (result > 0) {
				_version++;
			}
		}
		if (result > 0) {
			changed();
		}
		return result;
	}

	/**
	 * Waits until the worker has worked through every waiting entry and released the lock.
	 *
	 * @return Whether the worker ended within the given time.
	 */
	public synchronized boolean awaitIdle(long millis) throws InterruptedException {
		long end = System.currentTimeMillis() + millis;
		while (_running) {
			long left = end - System.currentTimeMillis();
			if (left <= 0) {
				return false;
			}
			wait(left);
		}
		return true;
	}

	private void startWorker() {
		try {
			_executor.execute(this::work);
		} catch (RuntimeException ex) {
			Logger.error("The download queue of project " + _key + " cannot start its worker.", ex, DownloadQueue.class);
			synchronized (this) {
				_running = false;
				notifyAll();
			}
			throw ex;
		}
	}

	/**
	 * The worker: takes the project's lock, waiting while another download holds it, and works
	 * through the waiting entries one after another; ends when none waits, and starts anew if one was
	 * enqueued while it released the lock.
	 */
	private void work() {
		try {
			try (DownloadLock lock = acquireLock()) {
				while (true) {
					Entry entry = next();
					if (entry == null) {
						break;
					}
					try {
						runInContext(entry);
					} finally {
						synchronized (this) {
							_current = null;
							_version++;
						}
						changed();
					}
				}
			}
		} catch (InterruptedException ex) {
			Logger.warn("The download queue of project " + _key + " was interrupted.", ex, DownloadQueue.class);
			cancelWaiting();
		} catch (RuntimeException | Error ex) {
			Logger.error("The download queue of project " + _key + " failed.", ex, DownloadQueue.class);
			cancelWaiting();
		} finally {
			boolean again;
			synchronized (this) {
				again = _entries.stream().anyMatch(e -> e._state == State.WAITING);
				if (!again) {
					_running = false;
					notifyAll();
				}
				_version++;
			}
			changed();
			if (again) {
				startWorker();
			}
		}
	}

	private DownloadLock acquireLock() throws InterruptedException {
		while (true) {
			try {
				return DownloadLock.acquireKey(_key, I18NConstants.QUEUE_RUNNING);
			} catch (TopLogicException ex) {
				// Another download of the project (the address, the layout, a restore) runs: wait for it.
				Thread.sleep(LOCK_RETRY_MILLIS);
			}
		}
	}

	/**
	 * The first waiting entry, now running; <code>null</code> if none waits.
	 */
	private Entry next() {
		Entry result = null;
		synchronized (this) {
			for (Entry entry : _entries) {
				if (entry._state == State.WAITING) {
					entry._state = State.RUNNING;
					entry._details = null;
					_current = entry;
					_version++;
					result = entry;
					break;
				}
			}
		}
		if (result != null) {
			changed();
		}
		return result;
	}

	/**
	 * Runs the download of the entry as the person who enqueued it, in a context of that person of its
	 * own, with the person's language and time zone: the download does not depend on the session that
	 * enqueued it, which may have ended meanwhile (haumacher/ehome #218). Only an entry enqueued without
	 * a person (a test in the system context) runs in the system context.
	 */
	private void runInContext(Entry entry) {
		try {
			if (entry._person == null) {
				ThreadContext.inSystemContext(DownloadQueue.class, () -> run(entry));
			} else {
				TLContextManager.inPersonContext(entry._person, () -> {
					if (!entry._person.tValid()) {
						entry.finish(State.FAILED, I18NConstants.QUEUE_PERSON_DELETED, null);
						return;
					}
					TLSubSessionContext session = TLContextManager.getSubSession();
					if (entry._locale != null) {
						session.setCurrentLocale(entry._locale);
					}
					if (entry._timeZone != null) {
						session.setCurrentTimeZone(entry._timeZone);
					}
					run(entry);
				});
			}
		} catch (RuntimeException ex) {
			Logger.error("Download into " + entry.label() + " failed.", ex, DownloadQueue.class);
			if (entry.state() == State.RUNNING) {
				entry.finish(State.FAILED, I18NConstants.QUEUE_INTERNAL_ERROR__DETAIL.fill(String.valueOf(ex.getMessage())), null);
			}
		}
	}

	private void run(Entry entry) {
		TLObject device = entry.device();
		if (!device.tValid()) {
			entry.finish(State.FAILED, I18NConstants.QUEUE_DEVICE_DELETED, null);
			return;
		}
		DownloadPlan.Kind kind = entry.kind();
		ResKey veto = ProgrammingFunctions.downloadVeto(device, kind);
		if (veto != null) {
			entry.finish(State.FAILED, veto, null);
			return;
		}
		DownloadPlan plan = DownloadPlanner.plan(device, kind);
		String path = BusPath.describe(device);
		entry.progress(0, plan.getOperations().size(), I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(path));
		DownloadService.Outcome outcome;
		try (CalimeroWriter access = _opener.open(device, plan)) {
			outcome = DownloadService.perform(device, plan, access, new DownloadExecutor(), (number, count, operation) -> {
				if (entry._abort) {
					throw new Aborted(number, count);
				}
				entry.progress(number - 1, count,
					I18NConstants.PROGRESS_OPERATION__NUMBER_COUNT_TEXT.fill(number, count, operation.description()));
			});
		} catch (Aborted ex) {
			entry.finish(State.ABORTED, I18NConstants.QUEUE_ABORTED_AT__NUMBER_COUNT.fill(ex._number, ex._count), null);
			return;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			entry.finish(State.ABORTED, I18NConstants.QUEUE_ABORTED, null);
			return;
		} catch (BusException ex) {
			entry.finish(State.FAILED, I18NConstants.ERROR_CONNECTION__PATH_DETAIL.fill(path, ex.getErrorKey()), null);
			return;
		} catch (TopLogicException ex) {
			entry.finish(State.FAILED, ex.getErrorKey(), null);
			return;
		} catch (RuntimeException ex) {
			Logger.error("Download into " + entry.label() + " failed.", ex, DownloadQueue.class);
			entry.finish(State.FAILED, I18NConstants.QUEUE_INTERNAL_ERROR__DETAIL.fill(String.valueOf(ex.getMessage())), null);
			return;
		}
		if (outcome.completeInstead() != null) {
			entry.completeInstead();
		}
		DownloadExecutor.Result result = outcome.result();
		entry.progress(result.done(), result.count(), null);
		Map<String, Object> report = DownloadJob.report(outcome, path);
		if (result.success()) {
			entry.finish(State.DONE, I18NConstants.QUEUE_DONE__COUNT_SECONDS.fill(result.done(),
				Double.valueOf(result.duration().toMillis() / 1000.0)), report);
		} else {
			entry.finish(State.FAILED, I18NConstants.QUEUE_FAILED__NUMBER_OPERATION_REASON.fill(result.done() + 1,
				result.failed().description(), result.reason()), report);
		}
	}

	private void changed() {
		for (Consumer<Object> listener : LISTENERS) {
			try {
				listener.accept(_key);
			} catch (RuntimeException ex) {
				Logger.error("A listener of the download queues failed.", ex, DownloadQueue.class);
			}
		}
	}

	/**
	 * The given devices, each once, in the order of their individual addresses (devices in no line
	 * last).
	 */
	public static List<TLObject> sorted(Collection<? extends TLObject> devices) {
		List<TLObject> result = new ArrayList<>(new LinkedHashSet<>(devices));
		result.sort(Comparator.comparingLong(DownloadQueue::order));
		return result;
	}

	private static long order(TLObject device) {
		Integer address = DownloadImage.individualAddress(device);
		if (address != null) {
			return address.longValue();
		}
		Object number = device.tValueByName("address");
		return 0x10000L + (number instanceof Number n ? n.longValue() : 0xFFFF);
	}

	/**
	 * Whether a download of the parameters only can be chosen for the device: its program allows it
	 * (not <code>LegacyNoPartialDownload</code>).
	 */
	public static boolean allowsParametersOnly(TLObject device) {
		Program program = DeviceConfiguration.program(device);
		return program != null && !program.getNoPartialDownload();
	}

	/**
	 * The devices of the project that need a download (<code>Device#downloadPending</code>), in the
	 * order of their individual addresses.
	 */
	public static List<TLObject> pendingDevices(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			if (Boolean.TRUE.equals(device.tValueByName("downloadPending"))) {
				result.add(device);
			}
		}
		return sorted(result);
	}

	static String label(TLObject device) {
		return MetaLabelProvider.INSTANCE.getLabel(device);
	}

}

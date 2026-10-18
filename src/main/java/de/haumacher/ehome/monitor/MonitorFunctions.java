/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

/**
 * The TL-Script functions of the group monitor view (<code>projects/monitor.view.xml</code>).
 *
 * <p>
 * The first argument of the functions working on the monitor is the state the view holds, the
 * {@link GroupMonitor#snapshot()} its {@link ConnectMonitorAction} delivered; the functions changing
 * the monitor return its new snapshot, which the view writes to its channel. Each function is
 * registered in <code>ehomeConf.config.xml</code> under the name of its {@link Function} constant
 * with the {@link Builder} of this class.
 * </p>
 */
public class MonitorFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>monitorDisconnect(state)</code>: {@link GroupMonitor#disconnect()}. */
		monitorDisconnect(1, 1, false),
		/** <code>monitorAllowSending(state, allowed)</code>: {@link GroupMonitor#setSending(boolean)}. */
		monitorAllowSending(2, 2, false),
		/**
		 * <code>monitorSend(state, address, value, type?)</code>:
		 * {@link GroupMonitor#send(Object, Object, String)}.
		 */
		monitorSend(3, 4, false),
		/** <code>monitorRead(state, address)</code>: {@link GroupMonitor#read(Object)}. */
		monitorRead(2, 2, false),
		/** <code>monitorStartRecording(state, name)</code>: {@link GroupMonitor#startRecording(String)}. */
		monitorStartRecording(2, 2, false),
		/** <code>monitorStopRecording(state)</code>: {@link GroupMonitor#stopRecording()}. */
		monitorStopRecording(1, 1, false),
		/** <code>monitorPauseLog(state, paused)</code>: {@link GroupMonitor#setLogPaused(boolean)}. */
		monitorPauseLog(2, 2, false),
		/** <code>monitorClear(state)</code>: {@link GroupMonitor#clear()}. */
		monitorClear(1, 1, false),
		/**
		 * <code>monitorTarget(state, address, type?)</code>: what the send form shows for an
		 * address, {@link GroupMonitor#target(Object, String)}.
		 */
		monitorTarget(2, 3, true),
		/**
		 * <code>monitorSendVeto(state)</code>: why the monitor cannot send now, <code>null</code> if
		 * it can.
		 */
		monitorSendVeto(1, 1, true),
		/** <code>monitorRecordingCsv(recording)</code>: {@link RecordingCsv#export(TLObject, TimeZone)}. */
		monitorRecordingCsv(1, 1, true),
		/**
		 * <code>monitorRecordingRows(recording)</code>: the telegrams and events of a recording as rows,
		 * {@link RecordingEntries#rows(TLObject, TimeZone)}.
		 */
		monitorRecordingRows(1, 1, true),
		/**
		 * <code>monitorDay(date)</code>: the day of the week of a point in time as the send form
		 * offers it, {@link DatapointCodec#day(Date)}.
		 */
		monitorDay(1, 1, true);

		private final int _minArguments;

		private final int _maxArguments;

		private final boolean _sideEffectFree;

		Function(int minArguments, int maxArguments, boolean sideEffectFree) {
			_minArguments = minArguments;
			_maxArguments = maxArguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link MonitorFunctions}.
	 */
	protected MonitorFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new MonitorFunctions(getName(), _function, arguments);
	}

	@Override
	public Object getId() {
		return _function;
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return _function._sideEffectFree;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (_function == Function.monitorRecordingCsv || _function == Function.monitorRecordingRows) {
			if (!(arguments[0] instanceof TLObject recording) || !recording.tValid()) {
				return _function == Function.monitorRecordingRows ? List.of() : null;
			}
			SubSessionContext session = ThreadContextManager.getSubSession();
			TimeZone timeZone = session == null ? TimeZone.getDefault() : session.getCurrentTimeZone();
			return _function == Function.monitorRecordingRows ? RecordingEntries.rows(recording, timeZone)
				: RecordingCsv.export(recording, timeZone);
		}
		if (_function == Function.monitorDay) {
			return arguments[0] instanceof Date date ? DatapointCodec.day(date) : null;
		}
		GroupMonitor monitor = monitor(arguments[0]);
		if (_function == Function.monitorSendVeto) {
			if (monitor == null || !monitor.isConnected()) {
				return Resources.getInstance().getString(I18NConstants.ERROR_NOT_CONNECTED);
			}
			return monitor.isSending() ? null : Resources.getInstance().getString(I18NConstants.ERROR_SENDING_DISABLED);
		}
		if (_function == Function.monitorTarget) {
			return monitor == null ? null : monitor.target(arguments[1], text(arguments, 2));
		}
		if (monitor == null) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_CONNECTED);
		}
		switch (_function) {
			case monitorDisconnect:
				monitor.disconnect();
				break;
			case monitorAllowSending:
				monitor.setSending(SearchExpression.asBoolean(arguments[1]));
				break;
			case monitorSend:
				monitor.send(arguments[1], arguments[2], text(arguments, 3));
				break;
			case monitorRead:
				monitor.read(arguments[1]);
				break;
			case monitorStartRecording:
				monitor.startRecording(arguments[1] == null ? null : arguments[1].toString());
				break;
			case monitorStopRecording:
				monitor.stopRecording();
				break;
			case monitorPauseLog:
				monitor.setLogPaused(SearchExpression.asBoolean(arguments[1]));
				break;
			case monitorClear:
				monitor.clear();
				break;
			case monitorTarget:
			case monitorSendVeto:
			case monitorRecordingCsv:
			case monitorRecordingRows:
			case monitorDay:
				break;
		}
		return monitor.snapshot();
	}

	private static GroupMonitor monitor(Object state) {
		if (state instanceof GroupMonitor monitor) {
			return monitor;
		}
		if (state instanceof Map<?, ?> map && map.get("monitor") instanceof GroupMonitor monitor) {
			return monitor;
		}
		return null;
	}

	private static String text(Object[] arguments, int index) {
		return arguments.length > index && arguments[index] != null ? arguments[index].toString() : null;
	}

	/**
	 * Builder of a {@link MonitorFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<MonitorFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public MonitorFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._minArguments, _function._maxArguments);
			return new MonitorFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}

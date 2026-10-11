/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;

import com.top_logic.basic.SessionContext;
import com.top_logic.basic.SubSessionContext;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.model.TLObject;

/**
 * The group monitors of the sessions: one per project and session, detached from the bus when the
 * session ends.
 */
public class MonitorSessions implements HttpSessionBindingListener {

	private static final Map<SessionContext, MonitorSessions> SESSIONS = new HashMap<>();

	private final SessionContext _session;

	private final Map<TLObject, GroupMonitor> _monitors = new HashMap<>();

	private MonitorSessions(SessionContext session) {
		_session = session;
	}

	/**
	 * The monitor of the given project in the current session; a new one, not connected, if the
	 * session has none yet.
	 */
	public static GroupMonitor monitor(TLObject project) {
		SubSessionContext subSession = ThreadContextManager.getSubSession();
		SessionContext session = subSession == null ? null : subSession.getSessionContext();
		if (session == null) {
			return new GroupMonitor(ProjectIndex.of(project));
		}
		MonitorSessions monitors;
		synchronized (SESSIONS) {
			monitors = SESSIONS.get(session);
			if (monitors == null) {
				monitors = new MonitorSessions(session);
				SESSIONS.put(session, monitors);
				session.addHttpSessionBindingListener(monitors);
			}
		}
		synchronized (monitors) {
			GroupMonitor result = monitors._monitors.get(project);
			if (result == null) {
				result = new GroupMonitor(ProjectIndex.of(project));
				monitors._monitors.put(project, result);
			}
			return result;
		}
	}

	@Override
	public void valueBound(HttpSessionBindingEvent event) {
		// Nothing to do.
	}

	@Override
	public void valueUnbound(HttpSessionBindingEvent event) {
		synchronized (SESSIONS) {
			SESSIONS.remove(_session);
		}
		synchronized (this) {
			for (GroupMonitor monitor : _monitors.values()) {
				monitor.close();
			}
			_monitors.clear();
		}
	}

}

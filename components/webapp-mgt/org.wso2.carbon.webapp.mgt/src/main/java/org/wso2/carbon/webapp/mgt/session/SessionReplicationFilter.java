/*
* Copyright (c) 2008-2014, Hazelcast, Inc. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.wso2.carbon.webapp.mgt.session;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.web.InvalidateEntryProcessor;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionReplicationFilter implements Filter {

	static final String HAZELCAST_SESSION_ATTRIBUTE_SEPARATOR = "::hz::";
	private static final String HAZELCAST_REQUEST = "*hazelcast-request";
	private static final String HAZELCAST_SESSION_COOKIE_NAME = "hazelcast.sessionId";
	private static final LocalCacheEntry NULL_ENTRY = new LocalCacheEntry();
	private static final ConcurrentMap<String, String> mapOriginalSessions = new
			ConcurrentHashMap<String, String>(1000);
	private static final ConcurrentMap<String, HazelcastHttpSession> mapSessions = new
			ConcurrentHashMap<String, HazelcastHttpSession>(1000);
	private HazelcastInstance hazelcastInstance;
	private String clusterMapName = "my-sessions";
	private String sessionCookieName = HAZELCAST_SESSION_COOKIE_NAME;
	private String sessionCookieDomain = null;
	private boolean sessionCookieSecure = false;
	private boolean sessionCookieHttpOnly = false;
	private boolean stickySession = true;
	private boolean shutdownOnDestroy = true;
	private boolean deferredWrite = false;
	private Properties properties;
	protected ServletContext servletContext;
	protected FilterConfig filterConfig;

	@Override
	public void init(FilterConfig config) throws ServletException {

		filterConfig = config;
		servletContext = filterConfig.getServletContext();
		initInstance();

		String mapName = getParam("map-name");
		if (mapName != null) {
			clusterMapName = mapName;
		} else {
			clusterMapName = "_web_" + servletContext.getServletContextName();
		}
		try {
			Config hzConfig = hazelcastInstance.getConfig();
			String sessionTTL = getParam("session-ttl-seconds");
			if (sessionTTL != null) {
				MapConfig mapConfig = hzConfig.getMapConfig(clusterMapName);
				mapConfig.setTimeToLiveSeconds(Integer.parseInt(sessionTTL));
				hzConfig.addMapConfig(mapConfig);
			}
		} catch (UnsupportedOperationException ignored) {
		}
		if (!stickySession) {
			getClusterMap().addEntryListener(new EntryListener<String, Object>() {
				public void entryAdded(EntryEvent<String, Object> entryEvent) {
				}

				public void entryRemoved(EntryEvent<String, Object> entryEvent) {
					if (entryEvent.getMember() == null || // client events has no owner member
					    !entryEvent.getMember().localMember()) {
						removeSessionLocally(entryEvent.getKey());
					}
				}

				public void entryUpdated(EntryEvent<String, Object> entryEvent) {
				}

				public void entryEvicted(EntryEvent<String, Object> entryEvent) {
					entryRemoved(entryEvent);
				}
			}, false);
		}
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, final FilterChain chain)
			throws IOException, ServletException {

		if (!(req instanceof HttpServletRequest)) {
			chain.doFilter(req, res);
		} else {
			if (req instanceof RequestWrapper) {
				chain.doFilter(req, res);
				return;
			}
			HttpServletRequest httpReq = (HttpServletRequest) req;
			RequestWrapper existingReq = (RequestWrapper) req.getAttribute(HAZELCAST_REQUEST);
			final ResponseWrapper resWrapper = new ResponseWrapper((HttpServletResponse) res);
			final RequestWrapper reqWrapper = new RequestWrapper(httpReq, resWrapper);
			if (existingReq != null) {
				reqWrapper.setHazelcastSession(existingReq.hazelcastSession,
				                               existingReq.requestedSessionId);
			}
			chain.doFilter(reqWrapper, resWrapper);
			if (existingReq != null) {
				return;
			}
			HazelcastHttpSession session = reqWrapper.getSession(false);
			if (session != null && session.isValid()) {
				if (session.sessionChanged() || !deferredWrite) {
					session.sessionDeferredWrite();
				}
			}
		}
	}

	@Override
	public void destroy() {
		mapSessions.clear();
		mapOriginalSessions.clear();
		shutdownInstance();
	}

	protected void shutdownInstance() {
		if (shutdownOnDestroy && hazelcastInstance != null) {
			hazelcastInstance.getLifecycleService().shutdown();
		}
	}

	private void initInstance() throws ServletException {
		if (properties == null) {
			properties = new Properties();
		}
		setProperty(HazelcastInstanceLoader.CONFIG_LOCATION);
		setProperty(HazelcastInstanceLoader.INSTANCE_NAME);
		setProperty(HazelcastInstanceLoader.USE_CLIENT);
		setProperty(HazelcastInstanceLoader.CLIENT_CONFIG_LOCATION);
		hazelcastInstance = getInstance(properties);
	}

	private void setProperty(String propertyName) {
		String value = getParam(propertyName);
		if (value != null) {
			properties.setProperty(propertyName, value);
		}
	}

	private String getParam(String name) {
		if (properties != null && properties.containsKey(name)) {
			return properties.getProperty(name);
		} else {
			return filterConfig.getInitParameter(name);
		}
	}

	protected HazelcastInstance getInstance(Properties properties) throws ServletException {
		return HazelcastInstanceLoader.createInstance(filterConfig, properties);
	}

	private void removeSessionLocally(String sessionId) {
		HazelcastHttpSession hazelSession = mapSessions.remove(sessionId);
		if (hazelSession != null) {
			mapOriginalSessions.remove(hazelSession.originalSession.getId());
			hazelSession.destroy();
		}
	}

	public static void destroyOriginalSession(HttpSession originalSession) {
		String hazelcastSessionId = mapOriginalSessions.remove(originalSession.getId());
		if (hazelcastSessionId != null) {
			HazelcastHttpSession hazelSession = mapSessions.remove(hazelcastSessionId);
			if (hazelSession != null) {
				hazelSession.sessionReplicationFilter.destroySession(hazelSession, false);
			}
		}
	}

	private String extractAttributeKey(String key) {
		return key.substring(key.indexOf(HAZELCAST_SESSION_ATTRIBUTE_SEPARATOR) +
		                     HAZELCAST_SESSION_ATTRIBUTE_SEPARATOR.length());
	}

	private HazelcastHttpSession createNewSession(RequestWrapper requestWrapper,
	                                              String existingSessionId) {
		String id = existingSessionId != null ? existingSessionId : generateSessionId();
		if (requestWrapper.getOriginalSession(false) != null) {
		}
		HttpSession originalSession = requestWrapper.getOriginalSession(true);
		HazelcastHttpSession hazelcastSession = new HazelcastHttpSession(
				SessionReplicationFilter.this, id, originalSession, deferredWrite);
		if (existingSessionId == null) {
			hazelcastSession.setClusterWideNew(true);
			getClusterMap().put(id, Boolean.TRUE);
		}
		mapSessions.put(hazelcastSession.getId(), hazelcastSession);
		String oldHazelcastSessionId = mapOriginalSessions.put(originalSession.getId(),
		                                                       hazelcastSession.getId());
		addSessionCookie(requestWrapper, id);
		if (deferredWrite) {
			loadHazelcastSession(hazelcastSession);
		}
		return hazelcastSession;
	}

	private void loadHazelcastSession(HazelcastHttpSession hazelcastSession) {
		Set<Map.Entry<String, Object>> entrySet = getClusterMap().entrySet(
				new SessionAttributePredicate(hazelcastSession.getId()));
		Map<String, LocalCacheEntry> cache = hazelcastSession.localCache;
		for (Map.Entry<String, Object> entry : entrySet) {
			String attributeKey = extractAttributeKey(entry.getKey());
			LocalCacheEntry cacheEntry = cache.get(attributeKey);
			if (cacheEntry == null) {
				cacheEntry = new LocalCacheEntry();
				cache.put(attributeKey, cacheEntry);
			}
			cacheEntry.value = entry.getValue();
			cacheEntry.dirty = false;
		}
	}

	private void prepareReloadingSession(HazelcastHttpSession hazelcastSession) {
		if (deferredWrite && hazelcastSession != null) {
			Map<String, LocalCacheEntry> cache = hazelcastSession.localCache;
			for (LocalCacheEntry cacheEntry : cache.values()) {
				cacheEntry.reload = true;
			}
		}
	}

	private void destroySession(HazelcastHttpSession session, boolean removeGlobalSession) {
		mapSessions.remove(session.getId());
		mapOriginalSessions.remove(session.originalSession.getId());
		session.destroy();
		if (removeGlobalSession) {
			IMap<String, Object> clusterMap = getClusterMap();
			clusterMap.delete(session.getId());
			clusterMap.executeOnEntries(new InvalidateEntryProcessor(session.getId()));
		}
	}

	private IMap<String, Object> getClusterMap() {
		return hazelcastInstance.getMap(clusterMapName);
	}

	private HazelcastHttpSession getSessionWithId(final String sessionId) {
		HazelcastHttpSession session = mapSessions.get(sessionId);
		if (session != null && !session.isValid()) {
			destroySession(session, true);
			session = null;
		}
		return session;
	}

	private static synchronized String generateSessionId() {
		final String id = UUID.randomUUID().toString();
		final StringBuilder sb = new StringBuilder("HZ");
		final char[] chars = id.toCharArray();
		for (final char c : chars) {
			if (c != '-') {
				if (Character.isLetter(c)) {
					sb.append(Character.toUpperCase(c));
				} else {
					sb.append(c);
				}
			}
		}
		return sb.toString();
	}

	private void addSessionCookie(final RequestWrapper req, final String sessionId) {
		final Cookie sessionCookie = new Cookie(sessionCookieName, sessionId);
		String path = req.getContextPath();
		if ("".equals(path)) {
			path = "/";
		}
		sessionCookie.setPath(path);
		sessionCookie.setMaxAge(-1);
		if (sessionCookieDomain != null) {
			sessionCookie.setDomain(sessionCookieDomain);
		}
		try {
			sessionCookie.setHttpOnly(sessionCookieHttpOnly);
		} catch (NoSuchMethodError e) {

		}
		sessionCookie.setSecure(sessionCookieSecure);
		req.res.addCookie(sessionCookie);
	}

	private String getSessionCookie(final RequestWrapper req) {
		final Cookie[] cookies = req.getCookies();
		if (cookies != null) {
			for (final Cookie cookie : cookies) {
				final String name = cookie.getName();
				final String value = cookie.getValue();
				if (name.equalsIgnoreCase(sessionCookieName)) {
					return value;
				}
			}
		}
		return null;
	}

	private class RequestWrapper extends HttpServletRequestWrapper {
		final ResponseWrapper res;
		HazelcastHttpSession hazelcastSession;
		String requestedSessionId;

		public RequestWrapper(final HttpServletRequest req, final ResponseWrapper res) {
			super(req);
			this.res = res;
			req.setAttribute(HAZELCAST_REQUEST, this);
		}

		public void setHazelcastSession(HazelcastHttpSession hazelcastSession,
		                                String requestedSessionId) {
			this.hazelcastSession = hazelcastSession;
			this.requestedSessionId = requestedSessionId;
		}

		HttpSession getOriginalSession(boolean create) {
			return super.getSession(create);
		}

		@Override
		public RequestDispatcher getRequestDispatcher(final String path) {
			final ServletRequest original = getRequest();
			return new RequestDispatcher() {
				public void forward(ServletRequest servletRequest,
				                    ServletResponse servletResponse) throws ServletException,
				                                                            IOException {
					original.getRequestDispatcher(path).forward(servletRequest, servletResponse);
				}

				public void include(ServletRequest servletRequest,
				                    ServletResponse servletResponse) throws ServletException,
				                                                            IOException {
					original.getRequestDispatcher(path).include(servletRequest, servletResponse);
				}
			};
		}

		public String fetchHazelcastSessionId() {
			if (requestedSessionId != null) {
				return requestedSessionId;
			}
			requestedSessionId = getSessionCookie(this);
			if (requestedSessionId != null) {
				return requestedSessionId;
			}
			requestedSessionId = getParameter(HAZELCAST_SESSION_COOKIE_NAME);
			return requestedSessionId;
		}

		@Override
		public HttpSession getSession() {
			return getSession(true);
		}

		@Override
		public HazelcastHttpSession getSession(final boolean create) {
			if (hazelcastSession != null && !hazelcastSession.isValid()) {
				destroySession(hazelcastSession, true);
				hazelcastSession = null;
			}
			if (hazelcastSession == null) {
				HttpSession originalSession = getOriginalSession(false);
				if (originalSession != null) {
					String hazelcastSessionId = mapOriginalSessions.get(originalSession.getId());
					if (hazelcastSessionId != null) {
						hazelcastSession = mapSessions.get(hazelcastSessionId);
					}
					if (hazelcastSession == null) {
						mapOriginalSessions.remove(originalSession.getId());
						originalSession.invalidate();
					}
				}
			}
			if (hazelcastSession != null) {
				return hazelcastSession;
			}
			final String requestedSessionId = fetchHazelcastSessionId();
			if (requestedSessionId != null) {
				hazelcastSession = getSessionWithId(requestedSessionId);
				if (hazelcastSession == null) {
					final Boolean existing = (Boolean) getClusterMap().get(requestedSessionId);
					if (existing != null && existing && create) {
						// we already have the session in the cluster loading it...
						hazelcastSession = createNewSession(RequestWrapper.this,
						                                    requestedSessionId);
					}
				}
			}
			if (hazelcastSession == null && create) {
				hazelcastSession = createNewSession(RequestWrapper.this, null);
			}
			if (deferredWrite) {
				prepareReloadingSession(hazelcastSession);
			}
			return hazelcastSession;
		}
	}

	private static class ResponseWrapper extends HttpServletResponseWrapper {

		public ResponseWrapper(final HttpServletResponse original) {
			super(original);
		}
	}

	private static class LocalCacheEntry {
		private Object value;
		volatile boolean dirty = false;
		volatile boolean reload = false;
		boolean removed = false; // does not need to be volatile - it's piggybacked on dirty!
	}

	private class HazelcastHttpSession implements HttpSession {

		volatile boolean valid = true;
		final String id;
		final HttpSession originalSession;
		private final Map<String, LocalCacheEntry> localCache;
		private final boolean deferredWrite;
		final SessionReplicationFilter sessionReplicationFilter;
		// only true if session is created first time in the cluster
		private volatile boolean clusterWideNew;

		public HazelcastHttpSession(SessionReplicationFilter sessionReplicationFilter,
		                            final String sessionId, HttpSession originalSession,
		                            boolean deferredWrite) {
			this.sessionReplicationFilter = sessionReplicationFilter;
			this.id = sessionId;
			this.originalSession = originalSession;
			this.deferredWrite = deferredWrite;
			this.localCache = deferredWrite ? new ConcurrentHashMap<String,
					LocalCacheEntry>() : null;
		}

		public Object getAttribute(final String name) {
			IMap<String, Object> clusterMap = getClusterMap();
			if (deferredWrite) {
				LocalCacheEntry cacheEntry = localCache.get(name);
				if (cacheEntry == null || (cacheEntry.reload && !cacheEntry.dirty)) {
					Object value = clusterMap.get(buildAttributeName(name));
					if (value == null) {
						cacheEntry = NULL_ENTRY;
					} else {
						cacheEntry = new LocalCacheEntry();
						cacheEntry.value = value;
						cacheEntry.reload = false;
					}
					localCache.put(name, cacheEntry);
				}
				return cacheEntry != NULL_ENTRY ? cacheEntry.value : null;
			}
			return clusterMap.get(buildAttributeName(name));
		}

		public Enumeration<String> getAttributeNames() {
			final Set<String> keys = selectKeys();
			return new Enumeration<String>() {
				private final String[] elements = keys.toArray(new String[keys.size()]);
				private int index = 0;

				@Override
				public boolean hasMoreElements() {
					return index < elements.length;
				}

				@Override
				public String nextElement() {
					return elements[index++];
				}
			};
		}

		public String getId() {
			return id;
		}

		public ServletContext getServletContext() {
			return servletContext;
		}

		public HttpSessionContext getSessionContext() {
			return originalSession.getSessionContext();
		}

		public Object getValue(final String name) {
			return getAttribute(name);
		}

		public String[] getValueNames() {
			final Set<String> keys = selectKeys();
			return keys.toArray(new String[keys.size()]);
		}

		public void invalidate() {
			originalSession.invalidate();
			destroySession(this, true);
		}

		public boolean isNew() {
			return originalSession.isNew() && clusterWideNew;
		}

		public void putValue(final String name, final Object value) {
			setAttribute(name, value);
		}

		public void removeAttribute(final String name) {
			if (deferredWrite) {
				LocalCacheEntry entry = localCache.get(name);
				if (entry != null && entry != NULL_ENTRY) {
					entry.value = null;
					entry.removed = true;
					// dirty needs to be set as last value for memory visibility reasons!
					entry.dirty = true;
				}
			} else {
				getClusterMap().delete(buildAttributeName(name));
			}
		}

		public void setAttribute(final String name, final Object value) {
			if (name == null) {
				throw new NullPointerException("name must not be null");
			}
			if (value == null) {
				removeAttribute(name);
				return;
			}
			if (deferredWrite) {
				LocalCacheEntry entry = localCache.get(name);
				if (entry == null || entry == NULL_ENTRY) {
					entry = new LocalCacheEntry();
					localCache.put(name, entry);
				}
				entry.value = value;
				entry.dirty = true;
			} else {
				getClusterMap().put(buildAttributeName(name), value);
			}
		}

		public void removeValue(final String name) {
			removeAttribute(name);
		}

		public boolean sessionChanged() {
			if (!deferredWrite) {
				return false;
			}
			for (Map.Entry<String, LocalCacheEntry> entry : localCache.entrySet()) {
				if (entry.getValue().dirty) {
					return true;
				}
			}
			return false;
		}

		public long getCreationTime() {
			return originalSession.getCreationTime();
		}

		public long getLastAccessedTime() {
			return originalSession.getLastAccessedTime();
		}

		public int getMaxInactiveInterval() {
			return originalSession.getMaxInactiveInterval();
		}

		public void setMaxInactiveInterval(int maxInactiveSeconds) {
			originalSession.setMaxInactiveInterval(maxInactiveSeconds);
		}

		void destroy() {
			valid = false;
		}

		public boolean isValid() {
			return valid;
		}

		private String buildAttributeName(String name) {
			return id + HAZELCAST_SESSION_ATTRIBUTE_SEPARATOR + name;
		}

		private void sessionDeferredWrite() {
			IMap<String, Object> clusterMap = getClusterMap();
			if (deferredWrite) {
				Iterator<Map.Entry<String, LocalCacheEntry>> iterator =
						localCache.entrySet().iterator();
				while (iterator.hasNext()) {
					Map.Entry<String, LocalCacheEntry> entry = iterator.next();
					if (entry.getValue().dirty) {
						LocalCacheEntry cacheEntry = entry.getValue();
						if (cacheEntry.removed) {
							clusterMap.delete(buildAttributeName(entry.getKey()));
							iterator.remove();
						} else {
							clusterMap.put(buildAttributeName(entry.getKey()), cacheEntry.value);
							cacheEntry.dirty = false;
						}
					}
				}
			}
		}

		private Set<String> selectKeys() {
			Set<String> keys = new HashSet<String>();
			if (!deferredWrite) {
				for (String qualifiedAttributeKey : getClusterMap()
						.keySet(new SessionAttributePredicate(id))) {
					keys.add(extractAttributeKey(qualifiedAttributeKey));
				}
			} else {
				for (Map.Entry<String, LocalCacheEntry> entry : localCache.entrySet()) {
					if (!entry.getValue().removed && entry.getValue() != NULL_ENTRY) {
						keys.add(entry.getKey());
					}
				}
			}
			return keys;
		}

		public void setClusterWideNew(boolean clusterWideNew) {
			this.clusterWideNew = clusterWideNew;
		}
	}
}
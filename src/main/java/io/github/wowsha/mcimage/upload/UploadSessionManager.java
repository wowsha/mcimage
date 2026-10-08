package io.github.wowsha.mcimage.upload;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks multi-part base64 uploads per player.
 * <p>
 * The vanilla client only sends 256 characters per chat command, so players
 * paste an image in several {@code /mcimage <part>} commands and finish with
 * {@code /mcimage done}. Main-thread only.
 */
public final class UploadSessionManager {

    /** One in-progress upload. */
    public static final class Session {
        private final StringBuilder data = new StringBuilder();
        private final long createdAt = System.currentTimeMillis();
        private long lastActivity = createdAt;
        private int parts;

        public void append(String part) {
            data.append(part);
            parts++;
            lastActivity = System.currentTimeMillis();
        }

        public int length() {
            return data.length();
        }

        public int parts() {
            return parts;
        }

        public String data() {
            return data.toString();
        }

        public long lastActivity() {
            return lastActivity;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    /**
     * @param player         player uuid
     * @param timeoutSeconds inactivity timeout
     * @return the active session, or {@code null} if there is none (expired sessions are dropped)
     */
    public Session get(UUID player, int timeoutSeconds) {
        Session session = sessions.get(player);
        if (session == null) {
            return null;
        }
        if (isExpired(session, timeoutSeconds)) {
            sessions.remove(player);
            return null;
        }
        return session;
    }

    /** Starts a fresh session, discarding any previous one. */
    public Session begin(UUID player) {
        Session session = new Session();
        sessions.put(player, session);
        return session;
    }

    public Session end(UUID player) {
        return sessions.remove(player);
    }

    public boolean hasExpiredSession(UUID player, int timeoutSeconds) {
        Session session = sessions.get(player);
        return session != null && isExpired(session, timeoutSeconds);
    }

    /** Drops all expired sessions; call periodically. */
    public void cleanup(int timeoutSeconds) {
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (isExpired(it.next().getValue(), timeoutSeconds)) {
                it.remove();
            }
        }
    }

    public void clear() {
        sessions.clear();
    }

    private static boolean isExpired(Session session, int timeoutSeconds) {
        return System.currentTimeMillis() - session.lastActivity() > timeoutSeconds * 1000L;
    }
}

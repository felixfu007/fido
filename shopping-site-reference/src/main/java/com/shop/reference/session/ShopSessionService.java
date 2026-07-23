package com.shop.reference.session;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 購物網站「自己的」登入 session store（示範用 in-memory 實作）。
 *
 * <p>對齊 CLAUDE.md「FIDO 伺服器不是身分來源」：這個 session 是購物網站在
 * {@link com.shop.reference.authentication.jwt.FidoSessionJwtValidator} 驗證完
 * fido-server 簽發的 session JWT 之後，才建立的購物網站「自己的」登入態，
 * 與 fido-server 完全無關（fido-server 不知道、也不需要知道這個 session 的存在）。
 */
@Service
public class ShopSessionService {

    public static final String COOKIE_NAME = "SHOP_SESSION";

    private final ConcurrentMap<String, ShopSession> sessions = new ConcurrentHashMap<>();

    public ShopSession createSession(String externalUserId, String fidoDeviceId, String fidoCredentialId) {
        String sessionId = "shopsess_" + UUID.randomUUID();
        ShopSession session = new ShopSession(sessionId, externalUserId, fidoDeviceId, fidoCredentialId, Instant.now());
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<ShopSession> find(String sessionId) {
        return Optional.ofNullable(sessionId).map(sessions::get);
    }

    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}

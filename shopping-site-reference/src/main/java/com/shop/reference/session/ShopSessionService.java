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

    /**
     * 取得目前登入的購物網站 session，取不到就直接拋例外（未登入）。
     *
     * <p>這是「從 session 取得 externalUserId」這個安全模式的入口：任何需要身分才能執行
     * 的端點都應該呼叫這個方法，而不是接受呼叫端在請求參數/body 裡自己宣稱的
     * externalUserId（見 {@link com.shop.reference.registration.RegistrationProxyController}
     * 與 {@link com.shop.reference.device.DeviceProxyController}）。
     *
     * @param sessionId 從 {@link #COOKIE_NAME} cookie 讀到的 session id（可能為 {@code null}）
     * @throws NotLoggedInException 找不到有效 session（cookie 缺失、值錯誤，或 session 已過期/被登出）
     */
    public ShopSession requireSession(String sessionId) {
        return find(sessionId).orElseThrow(() -> new NotLoggedInException(
                "尚未登入購物網站（缺少或無效的 " + COOKIE_NAME + " session），請先登入。"));
    }

    /**
     * 解析這次請求「應該使用哪個 externalUserId」，並在呼叫端夾帶的值與登入 session 不符時
     * 明確拒絕（而不是沉默地以 session 為準、忽略呼叫端傳入的值——見
     * {@link ExternalUserIdMismatchException} Javadoc 說明為什麼刻意不採用沉默忽略）。
     *
     * @param session               已驗證的登入 session（來自 {@link #requireSession(String)}）
     * @param claimedExternalUserId 呼叫端在請求參數/body 裡另外宣稱的 externalUserId，可為 {@code null}/空字串
     *                              （表示呼叫端沒有另外宣稱，單純使用 session 內的身分）
     * @return 應該信任、實際用來呼叫 fido-server 的 externalUserId（恆等於 session 內的值）
     * @throws ExternalUserIdMismatchException claimedExternalUserId 非空但與 session 不一致
     */
    public String resolveExternalUserId(ShopSession session, String claimedExternalUserId) {
        if (claimedExternalUserId != null && !claimedExternalUserId.isBlank()
                && !claimedExternalUserId.equals(session.externalUserId())) {
            throw new ExternalUserIdMismatchException(
                    "請求夾帶的 externalUserId=" + claimedExternalUserId
                            + " 與目前登入 session 的 externalUserId=" + session.externalUserId()
                            + " 不一致，拒絕處理。");
        }
        return session.externalUserId();
    }
}

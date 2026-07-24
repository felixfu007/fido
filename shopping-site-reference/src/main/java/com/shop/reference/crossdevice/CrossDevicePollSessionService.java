package com.shop.reference.crossdevice;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 維護「poll secret（{@code XDEV_POLL} cookie 的值） → {@code xdevId}」的伺服器端對映。
 *
 * <p>對應設計文件（{@code docs/decisions/qr-cross-device-login-design.md}）第 6.4 節二選一
 * 做法中的「伺服器端對映」路徑（而非把 {@code xdevId} 自己或其 HMAC 塞進 cookie）：實作起來
 * 更單純、也更容易在測試裡驗證「猜到或拿到 {@code xdevId} 的第三方，光憑這個值本身完全查不到
 * 任何 poll secret」這個安全性質（反過來、由 poll secret 查 {@code xdevId} 才是唯一合法方向）。
 *
 * <p>poll secret 本身是與 {@code xdevId} 一樣等級的高熵亂數（32 bytes、base64url），只透過
 * {@code httpOnly} cookie 傳遞，不會出現在任何回應 JSON body 裡（見
 * {@link CrossDeviceAuthenticationProxyController#start}），前端 JS 因此無法讀取、也無法把它
 * 轉貼給第三方。
 *
 * <p>示範規模下用 in-memory {@link ConcurrentHashMap}，比照
 * {@link com.shop.reference.session.ShopSessionService} 與
 * {@link com.shop.reference.authentication.jwt.FidoSessionJwtValidator} 的既有取捨（正式環境
 * 多實例部署應改用有 TTL 的共享儲存）；xdev session 本身在 fido-server 端 TTL 只有 120 秒，
 * 此處的對映在 {@code CONFIRMED}/{@code DENIED}/{@code EXPIRED} 任一終態出現時即由呼叫端主動
 * {@link #invalidate(String)}，不會無限增長。
 */
@Service
public class CrossDevicePollSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();

    /** 建立一組新的 poll secret → xdevId 對映，回傳 poll secret（要放進 {@code XDEV_POLL} cookie 的值）。 */
    public String createBinding(String xdevId) {
        String pollSecret = generateSecret();
        bindings.put(pollSecret, new Binding(xdevId, Instant.now()));
        return pollSecret;
    }

    /**
     * 依 poll secret 查回對應的 {@code xdevId}；查無（cookie 缺失、值不存在、或已被
     * {@link #invalidate(String)}）一律回傳空值，由呼叫端統一轉譯成
     * {@link CrossDevicePollSessionNotFoundException}，不區分細節原因（見該類別 Javadoc）。
     */
    public Optional<String> resolveXdevId(String pollSecret) {
        if (pollSecret == null || pollSecret.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindings.get(pollSecret)).map(Binding::xdevId);
    }

    /** poll 到達任一終態（{@code CONFIRMED}/{@code DENIED}/{@code EXPIRED}）後呼叫，讓這組 poll secret 立即失效、不可再用。 */
    public void invalidate(String pollSecret) {
        if (pollSecret != null) {
            bindings.remove(pollSecret);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private record Binding(String xdevId, Instant createdAt) {
    }
}

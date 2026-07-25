package com.fido.server.repository;

import com.fido.server.domain.CrossDeviceSession;

import java.time.Instant;
import java.util.Optional;

/**
 * `cross_device_sessions` 表的 persistence 介面（db-schema.md 第 11 節 / DB19）。
 *
 * <p>主要用途：{@code com.fido.server.service.CrossDeviceLoginService} 依 {@code xdevId}
 * capability 反查 session、驅動 {@code PENDING -> SCANNED -> CONFIRMED -> CONSUMED} 狀態機。
 */
public interface CrossDeviceSessionRepository {

    Optional<CrossDeviceSession> findByXdevId(String xdevId);

    CrossDeviceSession save(CrossDeviceSession session);

    /**
     * 端點 D 領取已簽發 session JWT 的守衛式（guarded）條件轉移（db-schema.md 第 11 節 DB20，
     * CLAUDE.md「守衛式條件 UPDATE」段落）。取代原單機記憶體 {@code Map.remove()} 的原子性：
     * 僅當該 {@code xdevId} 目前仍為 {@code CONFIRMED} 狀態，才在同一次原子操作內轉為
     * {@code CONSUMED}、清空 {@code issued_jwt}、寫入 {@code consumed_at}/{@code updated_at}，
     * 並回傳轉移前的 JWT 字串；否則（狀態已非 {@code CONFIRMED}——已被另一併發呼叫搶先領走，
     * 或本來就不是 {@code CONFIRMED}）回傳 {@link Optional#empty()}。
     *
     * <p>呼叫端（{@code CrossDeviceLoginService}）需將 empty 結果對應到既有的
     * {@code 409 XDEV_SESSION_INVALID_STATE} 錯誤路徑，語意上等同「已被消費」。
     *
     * <p><b>保證</b>：同一 {@code xdevId} 併發呼叫下，至多一次呼叫回傳非 empty 結果
     * （一次性領取），涵蓋多實例（不同 pod 對同一資料庫）與單實例多執行緒併發輪詢兩種情境。
     * JPA 實作以 {@code @Modifying} JPQL UPDATE 直接對資料庫做條件式更新並檢查受影響列數；
     * in-memory 實作以 {@code synchronized} 達成等價的「檢查再更新」原子操作。
     *
     * @param xdevId     目標 session 的對外識別
     * @param consumedAt 領取（轉 CONSUMED）時間，寫入 {@code consumed_at}/{@code updated_at}
     * @return 若成功領取，回傳轉移前的完整 JWT 字串（可能為 {@code null}，理論上不應發生但不視為
     *         系統錯誤）；若領取失敗（狀態已非 CONFIRMED），回傳 {@link Optional#empty()}
     */
    Optional<String> consumeConfirmedJwt(String xdevId, Instant consumedAt);
}

package com.fido.server.repository.jpa.springdata;

import com.fido.server.domain.enums.CrossDeviceSessionStatus;
import com.fido.server.repository.jpa.entity.CrossDeviceSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaCrossDeviceSessionRepository} 內部使用。
 */
public interface SpringDataCrossDeviceSessionRepository extends JpaRepository<CrossDeviceSessionEntity, Long> {

    Optional<CrossDeviceSessionEntity> findByXdevId(String xdevId);

    /**
     * db-schema.md DB20「端點 D 一次性保證（守衛式 UPDATE）」：僅當目前 {@code status} 仍為
     * {@code confirmedStatus}（呼叫端固定傳入 {@code CONFIRMED}），才原子轉為
     * {@code consumedStatus}（{@code CONSUMED}）、清空 {@code issued_jwt}、寫入
     * {@code consumed_at}/{@code updated_at}。回傳受影響列數：{@code 1} 表示本次呼叫是贏家，
     * {@code 0} 表示已被另一併發呼叫（同實例的另一執行緒，或多實例部署下的另一個 pod）搶先領走。
     *
     * <p>{@code @Modifying(clearAutomatically = true)}：本方法繞過 JPA 一階快取直接對資料庫
     * 送出 UPDATE，清除持久層快取避免同一交易內後續讀取到過期的 managed entity 狀態。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CrossDeviceSessionEntity c "
            + "SET c.status = :consumedStatus, c.issuedJwt = null, c.consumedAt = :consumedAt, "
            + "    c.updatedAt = :consumedAt "
            + "WHERE c.xdevId = :xdevId AND c.status = :confirmedStatus")
    int consumeConfirmedJwt(@Param("xdevId") String xdevId,
                             @Param("confirmedStatus") CrossDeviceSessionStatus confirmedStatus,
                             @Param("consumedStatus") CrossDeviceSessionStatus consumedStatus,
                             @Param("consumedAt") Instant consumedAt);
}

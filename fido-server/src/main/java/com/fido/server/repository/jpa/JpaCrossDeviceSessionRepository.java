package com.fido.server.repository.jpa;

import com.fido.server.domain.CrossDeviceSession;
import com.fido.server.domain.enums.CrossDeviceSessionStatus;
import com.fido.server.repository.CrossDeviceSessionRepository;
import com.fido.server.repository.jpa.entity.CrossDeviceSessionEntity;
import com.fido.server.repository.jpa.springdata.SpringDataCrossDeviceSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * {@link CrossDeviceSessionRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaCrossDeviceSessionRepository implements CrossDeviceSessionRepository {

    private final SpringDataCrossDeviceSessionRepository delegate;

    public JpaCrossDeviceSessionRepository(SpringDataCrossDeviceSessionRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CrossDeviceSession> findByXdevId(String xdevId) {
        return delegate.findByXdevId(xdevId).map(JpaCrossDeviceSessionRepository::toDomain);
    }

    @Override
    @Transactional
    public CrossDeviceSession save(CrossDeviceSession session) {
        CrossDeviceSessionEntity entity = toEntity(session);
        CrossDeviceSessionEntity saved = delegate.save(entity);
        session.setXdevPk(saved.getXdevPk());
        return session;
    }

    /**
     * 見 {@link CrossDeviceSessionRepository#consumeConfirmedJwt} 的完整語意說明。實作分兩步：
     * (1) 先 SELECT 讀出目前的 {@code issued_jwt}（因為守衛式 UPDATE 成功時會把它清成 NULL，
     * 必須在那之前讀出待回傳的值）；(2) 呼叫 {@link SpringDataCrossDeviceSessionRepository}
     * 的 {@code @Modifying} 條件式 UPDATE，只有回傳受影響列數 1 才代表本次呼叫是真正的贏家，
     * 此時才回傳步驟 (1) 讀到的 JWT。兩步驟之間即使有其他併發呼叫插入，也不影響正確性：
     * {@code issued_jwt} 只在端點 C（設一次）與本方法成功領取（清空一次）被寫入，同一個
     * {@code xdev_id} 在轉為 {@code CONFIRMED} 後、被消費前，其值不會被第三方改動，因此「贏家」
     * 讀到的值必然與其自己成功轉移那一刻資料庫裡的值一致；「輸家」（UPDATE 受影響列數為 0）則
     * 直接丟棄步驟 (1) 讀到的值、回傳 empty，不會有任何 JWT 被回傳兩次的風險。
     */
    @Override
    @Transactional
    public Optional<String> consumeConfirmedJwt(String xdevId, Instant consumedAt) {
        Optional<CrossDeviceSessionEntity> existing = delegate.findByXdevId(xdevId);
        if (existing.isEmpty() || existing.get().getStatus() != CrossDeviceSessionStatus.CONFIRMED) {
            return Optional.empty();
        }
        String issuedJwt = existing.get().getIssuedJwt();

        int updatedRows = delegate.consumeConfirmedJwt(xdevId, CrossDeviceSessionStatus.CONFIRMED,
                CrossDeviceSessionStatus.CONSUMED, consumedAt);

        return updatedRows == 1 ? Optional.ofNullable(issuedJwt) : Optional.empty();
    }

    private static CrossDeviceSessionEntity toEntity(CrossDeviceSession session) {
        CrossDeviceSessionEntity entity = new CrossDeviceSessionEntity();
        entity.setXdevPk(session.getXdevPk());
        entity.setXdevId(session.getXdevId());
        entity.setTenantId(session.getTenantId());
        entity.setChallengePk(session.getChallengePk());
        entity.setStatus(session.getStatus());
        entity.setVerificationCode(session.getVerificationCode());
        entity.setDesktopIp(session.getDesktopIp());
        entity.setPhoneIp(session.getPhoneIp());
        entity.setProximityMismatch(session.getProximityMismatch());
        entity.setUserRefId(session.getUserRefId());
        entity.setCredentialPk(session.getCredentialPk());
        entity.setIssuedJti(session.getIssuedJti());
        entity.setIssuedJwt(session.getIssuedJwt());
        entity.setExpiresAt(session.getExpiresAt());
        entity.setScannedAt(session.getScannedAt());
        entity.setConfirmedAt(session.getConfirmedAt());
        entity.setConsumedAt(session.getConsumedAt());
        entity.setCreatedAt(session.getCreatedAt());
        entity.setUpdatedAt(session.getUpdatedAt());
        return entity;
    }

    private static CrossDeviceSession toDomain(CrossDeviceSessionEntity entity) {
        CrossDeviceSession session = new CrossDeviceSession();
        session.setXdevPk(entity.getXdevPk());
        session.setXdevId(entity.getXdevId());
        session.setTenantId(entity.getTenantId());
        session.setChallengePk(entity.getChallengePk());
        session.setStatus(entity.getStatus());
        session.setVerificationCode(entity.getVerificationCode());
        session.setDesktopIp(entity.getDesktopIp());
        session.setPhoneIp(entity.getPhoneIp());
        session.setProximityMismatch(entity.getProximityMismatch());
        session.setUserRefId(entity.getUserRefId());
        session.setCredentialPk(entity.getCredentialPk());
        session.setIssuedJti(entity.getIssuedJti());
        session.setIssuedJwt(entity.getIssuedJwt());
        session.setExpiresAt(entity.getExpiresAt());
        session.setScannedAt(entity.getScannedAt());
        session.setConfirmedAt(entity.getConfirmedAt());
        session.setConsumedAt(entity.getConsumedAt());
        session.setCreatedAt(entity.getCreatedAt());
        session.setUpdatedAt(entity.getUpdatedAt());
        return session;
    }
}

package com.fido.server.repository.jpa;

import com.fido.server.domain.CrossDeviceSession;
import com.fido.server.repository.CrossDeviceSessionRepository;
import com.fido.server.repository.jpa.entity.CrossDeviceSessionEntity;
import com.fido.server.repository.jpa.springdata.SpringDataCrossDeviceSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
        session.setExpiresAt(entity.getExpiresAt());
        session.setScannedAt(entity.getScannedAt());
        session.setConfirmedAt(entity.getConfirmedAt());
        session.setConsumedAt(entity.getConsumedAt());
        session.setCreatedAt(entity.getCreatedAt());
        session.setUpdatedAt(entity.getUpdatedAt());
        return session;
    }
}

package com.fido.server.repository.jpa;

import com.fido.server.domain.SigningKey;
import com.fido.server.domain.enums.SigningKeyStatus;
import com.fido.server.repository.SigningKeyRepository;
import com.fido.server.repository.jpa.entity.SigningKeyEntity;
import com.fido.server.repository.jpa.springdata.SpringDataSigningKeyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link SigningKeyRepository} 的 JPA 實作，見 {@link JpaTenantRepository} 說明。
 *
 * <p>{@link #save} 刻意使用 {@code saveAndFlush} 而非 {@code save}：確保新插入一把
 * {@code status=ACTIVE} 列與既有 ACTIVE 列衝突時，{@code UX_signkey_one_active} filtered
 * unique index 違反會在本方法呼叫當下（而非延後到交易 commit 時）就同步拋出
 * {@link org.springframework.dao.DataIntegrityViolationException}，讓呼叫端
 * （{@code JwtService}）能在同一次呼叫的 try/catch 內可靠地攔截並重新查詢既有 ACTIVE 列。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "jpa", matchIfMissing = true)
public class JpaSigningKeyRepository implements SigningKeyRepository {

    private final SpringDataSigningKeyRepository delegate;

    public JpaSigningKeyRepository(SpringDataSigningKeyRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SigningKey> findActive() {
        return delegate.findByStatus(SigningKeyStatus.ACTIVE).map(JpaSigningKeyRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SigningKey> findAll() {
        return delegate.findAll().stream()
                .map(JpaSigningKeyRepository::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SigningKey save(SigningKey signingKey) {
        SigningKeyEntity entity = toEntity(signingKey);
        SigningKeyEntity saved = delegate.saveAndFlush(entity);
        signingKey.setKeyPk(saved.getKeyPk());
        return signingKey;
    }

    private static SigningKeyEntity toEntity(SigningKey key) {
        SigningKeyEntity entity = new SigningKeyEntity();
        entity.setKeyPk(key.getKeyPk());
        entity.setKid(key.getKid());
        entity.setAlgorithm(key.getAlgorithm());
        entity.setCurve(key.getCurve());
        entity.setPrivateKey(key.getPrivateKey());
        entity.setPublicKey(key.getPublicKey());
        entity.setStatus(key.getStatus());
        entity.setCreatedAt(key.getCreatedAt());
        entity.setRetiredAt(key.getRetiredAt());
        return entity;
    }

    private static SigningKey toDomain(SigningKeyEntity entity) {
        SigningKey key = new SigningKey();
        key.setKeyPk(entity.getKeyPk());
        key.setKid(entity.getKid());
        key.setAlgorithm(entity.getAlgorithm());
        key.setCurve(entity.getCurve());
        key.setPrivateKey(entity.getPrivateKey());
        key.setPublicKey(entity.getPublicKey());
        key.setStatus(entity.getStatus());
        key.setCreatedAt(entity.getCreatedAt());
        key.setRetiredAt(entity.getRetiredAt());
        return key;
    }
}

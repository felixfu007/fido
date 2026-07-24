package com.fido.server.repository.jpa.springdata;

import com.fido.server.domain.enums.SigningKeyStatus;
import com.fido.server.repository.jpa.entity.SigningKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaSigningKeyRepository} 內部使用。
 */
public interface SpringDataSigningKeyRepository extends JpaRepository<SigningKeyEntity, Long> {

    /**
     * 正常情況下 {@code status='ACTIVE'} 最多一筆（{@code UX_signkey_one_active} filtered
     * unique index 保證）。若違反（理論上不應發生）Spring Data 會拋出
     * {@code IncorrectResultSizeDataAccessException}，屬於需要人工介入排查的資料異常。
     */
    Optional<SigningKeyEntity> findByStatus(SigningKeyStatus status);
}

package com.fido.server.repository.jpa.springdata;

import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.jpa.entity.BoundDeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaBoundDeviceRepository} 內部使用。
 */
public interface SpringDataBoundDeviceRepository extends JpaRepository<BoundDeviceEntity, Long> {

    Optional<BoundDeviceEntity> findByDeviceId(UUID deviceId);

    Optional<BoundDeviceEntity> findByCredentialPk(Long credentialPk);

    List<BoundDeviceEntity> findByUserRefId(Long userRefId);

    List<BoundDeviceEntity> findByUserRefIdAndStatus(Long userRefId, RecordStatus status);
}

package com.fido.server.repository.jpa.springdata;

import com.fido.server.repository.jpa.entity.CrossDeviceSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaCrossDeviceSessionRepository} 內部使用。
 */
public interface SpringDataCrossDeviceSessionRepository extends JpaRepository<CrossDeviceSessionEntity, Long> {

    Optional<CrossDeviceSessionEntity> findByXdevId(String xdevId);
}

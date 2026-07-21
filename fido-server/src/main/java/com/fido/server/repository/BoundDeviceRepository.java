package com.fido.server.repository;

import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.enums.RecordStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * `bound_devices` 表的 persistence 介面。目前實作見
 * {@code com.fido.server.repository.inmemory.InMemoryBoundDeviceRepository}。
 */
public interface BoundDeviceRepository {

    Optional<BoundDevice> findByDeviceId(UUID deviceId);

    Optional<BoundDevice> findByCredentialPk(Long credentialPk);

    List<BoundDevice> findByUserRefId(Long userRefId);

    List<BoundDevice> findByUserRefIdAndStatus(Long userRefId, RecordStatus status);

    BoundDevice save(BoundDevice device);
}

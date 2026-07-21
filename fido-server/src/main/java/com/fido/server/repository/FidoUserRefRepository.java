package com.fido.server.repository;

import com.fido.server.domain.FidoUserRef;

import java.util.Optional;

/**
 * `fido_user_ref` 表的 persistence 介面。目前實作見
 * {@code com.fido.server.repository.inmemory.InMemoryFidoUserRefRepository}。
 */
public interface FidoUserRefRepository {

    Optional<FidoUserRef> findById(Long userRefId);

    Optional<FidoUserRef> findByTenantIdAndExternalUserId(Long tenantId, String externalUserId);

    FidoUserRef save(FidoUserRef userRef);
}

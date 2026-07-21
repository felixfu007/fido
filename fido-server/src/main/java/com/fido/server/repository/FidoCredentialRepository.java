package com.fido.server.repository;

import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.enums.RecordStatus;

import java.util.List;
import java.util.Optional;

/**
 * `fido_credentials` 表的 persistence 介面。目前實作見
 * {@code com.fido.server.repository.inmemory.InMemoryFidoCredentialRepository}。
 */
public interface FidoCredentialRepository {

    Optional<FidoCredential> findByCredentialPk(Long credentialPk);

    Optional<FidoCredential> findByTenantIdAndCredentialIdSha256(Long tenantId, byte[] credentialIdSha256);

    List<FidoCredential> findByUserRefId(Long userRefId);

    List<FidoCredential> findByUserRefIdAndStatus(Long userRefId, RecordStatus status);

    long countByUserRefIdAndStatus(Long userRefId, RecordStatus status);

    FidoCredential save(FidoCredential credential);
}

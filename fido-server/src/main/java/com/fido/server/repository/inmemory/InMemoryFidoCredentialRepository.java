package com.fido.server.repository.inmemory;

import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.repository.FidoCredentialRepository;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** In-memory 骨架實作，見 {@link InMemoryTenantRepository} 說明。 */
@Repository
public class InMemoryFidoCredentialRepository implements FidoCredentialRepository {

    private final Map<Long, FidoCredential> byPk = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public synchronized Optional<FidoCredential> findByCredentialPk(Long credentialPk) {
        return Optional.ofNullable(byPk.get(credentialPk));
    }

    @Override
    public synchronized Optional<FidoCredential> findByTenantIdAndCredentialIdSha256(Long tenantId, byte[] credentialIdSha256) {
        return byPk.values().stream()
                .filter(c -> c.getTenantId().equals(tenantId) && Arrays.equals(c.getCredentialIdSha256(), credentialIdSha256))
                .findFirst();
    }

    @Override
    public synchronized List<FidoCredential> findByUserRefId(Long userRefId) {
        return byPk.values().stream()
                .filter(c -> c.getUserRefId().equals(userRefId))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized List<FidoCredential> findByUserRefIdAndStatus(Long userRefId, RecordStatus status) {
        return byPk.values().stream()
                .filter(c -> c.getUserRefId().equals(userRefId) && c.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized long countByUserRefIdAndStatus(Long userRefId, RecordStatus status) {
        return byPk.values().stream()
                .filter(c -> c.getUserRefId().equals(userRefId) && c.getStatus() == status)
                .count();
    }

    @Override
    public synchronized FidoCredential save(FidoCredential credential) {
        if (credential.getCredentialPk() == null) {
            credential.setCredentialPk(idSeq.getAndIncrement());
        }
        byPk.put(credential.getCredentialPk(), credential);
        return credential;
    }
}

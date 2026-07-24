package com.fido.server.repository.inmemory;

import com.fido.server.domain.SigningKey;
import com.fido.server.domain.enums.SigningKeyStatus;
import com.fido.server.repository.SigningKeyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory 骨架實作，見 {@link InMemoryTenantRepository} 說明。
 *
 * <p>為了讓 {@code fido.persistence.mode=memory}（骨架/測試預設）下的行為與真實資料庫的
 * {@code UX_signkey_one_active} filtered unique index 一致，本實作也會在 {@link #save} 插入
 * 一把新的 {@code status=ACTIVE} 列、且已存在另一把（不同 PK 的）ACTIVE 列時，拋出
 * {@link DataIntegrityViolationException}，模擬真實資料庫的並發首啟競態防護行為。
 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "memory")
public class InMemorySigningKeyRepository implements SigningKeyRepository {

    private final Map<Long, SigningKey> byPk = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public synchronized Optional<SigningKey> findActive() {
        return byPk.values().stream().filter(k -> k.getStatus() == SigningKeyStatus.ACTIVE).findFirst();
    }

    @Override
    public synchronized List<SigningKey> findAll() {
        return byPk.values().stream().collect(Collectors.toList());
    }

    @Override
    public synchronized SigningKey save(SigningKey signingKey) {
        if (signingKey.getStatus() == SigningKeyStatus.ACTIVE) {
            boolean anotherActiveExists = byPk.values().stream()
                    .anyMatch(k -> k.getStatus() == SigningKeyStatus.ACTIVE
                            && !k.getKeyPk().equals(signingKey.getKeyPk()));
            if (anotherActiveExists) {
                throw new DataIntegrityViolationException(
                        "UX_signkey_one_active violated（in-memory 模擬）：已存在另一把 ACTIVE signing key");
            }
        }
        if (signingKey.getKeyPk() == null) {
            signingKey.setKeyPk(idSeq.getAndIncrement());
        }
        byPk.put(signingKey.getKeyPk(), signingKey);
        return signingKey;
    }
}

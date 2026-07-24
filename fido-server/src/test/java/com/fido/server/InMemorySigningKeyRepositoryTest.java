package com.fido.server;

import com.fido.server.domain.SigningKey;
import com.fido.server.domain.enums.SigningKeyStatus;
import com.fido.server.repository.SigningKeyRepository;
import com.fido.server.repository.inmemory.InMemorySigningKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code fido.persistence.mode=memory} 骨架下也要重現 {@code UX_signkey_one_active}
 * filtered unique index 的語意（見 {@link InMemorySigningKeyRepository} 說明），
 * 否則 memory 模式與 jpa 模式在「同時最多一把 ACTIVE」這個關鍵約束上行為會不一致。
 */
class InMemorySigningKeyRepositoryTest {

    private SigningKeyRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemorySigningKeyRepository();
    }

    private static SigningKey newKey(String kid, SigningKeyStatus status) {
        SigningKey key = new SigningKey();
        key.setKid(kid);
        key.setAlgorithm("ES256");
        key.setCurve("P-256");
        key.setPrivateKey(new byte[]{1, 2, 3});
        key.setPublicKey(new byte[]{4, 5, 6});
        key.setStatus(status);
        return key;
    }

    @Test
    void firstActiveKeyInsertsSuccessfully() {
        SigningKey saved = repository.save(newKey("kid-1", SigningKeyStatus.ACTIVE));
        assertThat(saved.getKeyPk()).isNotNull();

        Optional<SigningKey> active = repository.findActive();
        assertThat(active).isPresent();
        assertThat(active.get().getKid()).isEqualTo("kid-1");
    }

    @Test
    void insertingSecondActiveKeyThrowsDataIntegrityViolation() {
        repository.save(newKey("kid-1", SigningKeyStatus.ACTIVE));

        assertThatThrownBy(() -> repository.save(newKey("kid-2", SigningKeyStatus.ACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 失敗的插入不應污染既有狀態。
        assertThat(repository.findActive()).isPresent();
        assertThat(repository.findActive().get().getKid()).isEqualTo("kid-1");
    }

    @Test
    void updatingExistingActiveKeyToRetiredThenInsertingNewActiveSucceeds() {
        SigningKey active = repository.save(newKey("kid-1", SigningKeyStatus.ACTIVE));

        active.setStatus(SigningKeyStatus.RETIRED);
        repository.save(active);

        SigningKey newActive = repository.save(newKey("kid-2", SigningKeyStatus.ACTIVE));
        assertThat(newActive.getKeyPk()).isNotNull();

        assertThat(repository.findActive()).isPresent();
        assertThat(repository.findActive().get().getKid()).isEqualTo("kid-2");
        assertThat(repository.findAll()).hasSize(2);
    }
}

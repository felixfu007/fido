package com.fido.server.repository.jpa.springdata;

import com.fido.server.repository.jpa.entity.AuthChallengeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA 底層介面，僅供
 * {@link com.fido.server.repository.jpa.JpaAuthChallengeRepository} 內部使用。
 */
public interface SpringDataAuthChallengeRepository extends JpaRepository<AuthChallengeEntity, Long> {

    Optional<AuthChallengeEntity> findByCeremonyId(String ceremonyId);
}

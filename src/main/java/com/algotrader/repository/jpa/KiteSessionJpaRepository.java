package com.algotrader.repository.jpa;

import com.algotrader.entity.KiteSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the kite_session table.
 * H2 fallback for Kite access tokens when Redis data is lost.
 * On startup, KiteAuthService checks Redis first, then falls back to this repository.
 */
@Repository
public interface KiteSessionJpaRepository extends JpaRepository<KiteSessionEntity, String> {

    Optional<KiteSessionEntity> findByUserId(String userId);
}

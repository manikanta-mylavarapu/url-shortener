package com.example.shortener.repo;

import com.example.shortener.domain.ShortLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    Optional<ShortLink> findByCode(String code);

    boolean existsByCode(String code);

    Optional<ShortLink> findByIdempotencyKey(String idempotencyKey);

    /** Atomic in-database increment: no read-modify-write race between concurrent redirects. */
    @Modifying
    @Transactional
    @Query("update ShortLink s set s.clickCount = s.clickCount + 1 where s.code = :code")
    int incrementClicks(@Param("code") String code);

    /**
     * Atomic soft delete. Deliberately a targeted UPDATE rather than load-modify-save: Hibernate rewrites every
     * column on save(), so a click counted between the read and the write would be overwritten with a stale value.
     * The {@code active = true} guard makes repeating it a harmless no-op.
     */
    @Modifying
    @Transactional
    @Query("update ShortLink s set s.active = false where s.code = :code and s.active = true")
    int deactivateByCode(@Param("code") String code);

    /**
     * Atomically sets (or, with null, clears) the expiry of a link that is still active. A targeted UPDATE for the same reason
     * as {@link #deactivateByCode}: it must not rewrite {@code click_count}. Returns 0 if the link was deactivated meanwhile.
     */
    @Modifying
    @Transactional
    @Query("update ShortLink s set s.expiresAt = :expiresAt where s.code = :code and s.active = true")
    int updateExpiry(@Param("code") String code, @Param("expiresAt") Instant expiresAt);
}

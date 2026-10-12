package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.domain.ShortLink;
import com.example.shortener.error.AliasTakenException;
import com.example.shortener.error.CodeAllocationException;
import com.example.shortener.error.InvalidAliasException;
import com.example.shortener.error.InvalidUrlException;
import com.example.shortener.error.LinkGoneException;
import com.example.shortener.error.LinkNotFoundException;
import com.example.shortener.repo.ShortLinkRepository;
import com.example.shortener.support.MutableClock;
import com.example.shortener.error.IdempotencyKeyReuseException;
import com.example.shortener.error.InvalidIdempotencyKeyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private static final ClickContext CTX = new ClickContext("203.0.113.9", "JUnit", null);

    @Mock ShortLinkRepository repo;
    @Mock AnalyticsService analytics;

    private MutableClock clock;
    private SimpleMeterRegistry meters;
    private LinkService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        AppProperties props = new AppProperties("http://localhost:8080", false, 7, 3, "salt",
                new AppProperties.RateLimit(true, 30, 0.5, false, 10_000));
        meters = new SimpleMeterRegistry();
        service = new LinkService(repo, analytics, new CodeGenerator(7, new Random(1)),
                new UrlValidator(false), clock, props, meters);
    }

    private void saveReturnsArgument() {
        when(repo.saveAndFlush(any(ShortLink.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- create --------------------------------------------------------------------------

    @Test
    void createGeneratesCodeAndComputesExpiryFromInjectedClock() {
        saveReturnsArgument();

        ShortLink link = service.create("https://example.com/a", null, Duration.ofMinutes(10));

        assertThat(link.getCode()).matches("[0-9A-Za-z]{7}");
        assertThat(link.getOriginalUrl()).isEqualTo("https://example.com/a");
        assertThat(link.getCreatedAt()).isEqualTo(T0);
        assertThat(link.getExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    void createWithoutTtlNeverExpires() {
        saveReturnsArgument();
        assertThat(service.create("https://example.com", null, null).getExpiresAt()).isNull();
    }

    @Test
    void createRetriesOnCodeCollisionThenSucceeds() {
        when(repo.saveAndFlush(any(ShortLink.class)))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenAnswer(inv -> inv.getArgument(0));

        ShortLink link = service.create("https://example.com", null, null);

        assertThat(link.getCode()).isNotBlank();
        verify(repo, times(3)).saveAndFlush(any(ShortLink.class));
    }

    @Test
    void createGivesUpAfterBoundedAttempts() {
        when(repo.saveAndFlush(any(ShortLink.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create("https://example.com", null, null))
                .isInstanceOf(CodeAllocationException.class);
        verify(repo, times(3)).saveAndFlush(any(ShortLink.class)); // maxCodeAttempts = 3, no infinite loop
    }

    @Test
    void createRejectsInvalidUrlBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.create("ftp://example.com", null, null)).isInstanceOf(InvalidUrlException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    // ---- aliases -------------------------------------------------------------------------

    @Test
    void customAliasIsUsedVerbatim() {
        when(repo.existsByCode("my-link")).thenReturn(false);
        saveReturnsArgument();

        assertThat(service.create("https://example.com", "my-link", null).getCode()).isEqualTo("my-link");
    }

    @Test
    void malformedAliasIsRejected() {
        assertThatThrownBy(() -> service.create("https://example.com", "a!", null)).isInstanceOf(InvalidAliasException.class);
        assertThatThrownBy(() -> service.create("https://example.com", "ab", null)).isInstanceOf(InvalidAliasException.class);
        assertThatThrownBy(() -> service.create("https://example.com", "x".repeat(33), null)).isInstanceOf(InvalidAliasException.class);
    }

    @Test
    void reservedAliasIsRejectedCaseInsensitively() {
        assertThatThrownBy(() -> service.create("https://example.com", "API", null)).isInstanceOf(AliasTakenException.class);
        assertThatThrownBy(() -> service.create("https://example.com", "actuator", null)).isInstanceOf(AliasTakenException.class);
    }

    @Test
    void existingAliasIsRejected() {
        when(repo.existsByCode("taken")).thenReturn(true);
        assertThatThrownBy(() -> service.create("https://example.com", "taken", null)).isInstanceOf(AliasTakenException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void aliasRaceLostToConcurrentRequestIsReportedAsConflict() {
        when(repo.existsByCode("race")).thenReturn(false);
        when(repo.saveAndFlush(any(ShortLink.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create("https://example.com", "race", null)).isInstanceOf(AliasTakenException.class);
    }

    // ---- resolve -------------------------------------------------------------------------

    private ShortLink stored(Instant expiresAt) {
        ShortLink link = ShortLink.create("abc1234", "https://example.com/target", T0, expiresAt);
        when(repo.findByCode("abc1234")).thenReturn(Optional.of(link));
        return link;
    }

    @Test
    void resolveReturnsTargetAndRecordsClick() {
        stored(null);

        assertThat(service.resolveForRedirect("abc1234", CTX)).isEqualTo("https://example.com/target");
        verify(analytics).recordClick("abc1234", CTX);
    }

    @Test
    void resolveUnknownCodeIsNotFound() {
        when(repo.findByCode(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveForRedirect("zzz", CTX)).isInstanceOf(LinkNotFoundException.class);
        verify(analytics, never()).recordClick(anyString(), any());
    }

    @Test
    void resolveExpiredLinkIsGoneAndNotCounted() {
        stored(T0.plusSeconds(60));
        clock.advance(Duration.ofSeconds(60)); // boundary: expiry instant itself is already expired

        assertThatThrownBy(() -> service.resolveForRedirect("abc1234", CTX)).isInstanceOf(LinkGoneException.class);
        verify(analytics, never()).recordClick(anyString(), any());
    }

    @Test
    void resolveBeforeExpiryStillWorks() {
        stored(T0.plusSeconds(60));
        clock.advance(Duration.ofSeconds(59));
        assertThat(service.resolveForRedirect("abc1234", CTX)).isEqualTo("https://example.com/target");
    }

    @Test
    void resolveDeactivatedLinkIsGone() {
        stored(null).deactivate();
        assertThatThrownBy(() -> service.resolveForRedirect("abc1234", CTX)).isInstanceOf(LinkGoneException.class);
    }

    @Test
    void analyticsFailureNeverBreaksARedirect_butIsCounted() {
        stored(null);
        doThrow(new IllegalStateException("analytics store down")).when(analytics).recordClick(anyString(), any());

        assertThat(service.resolveForRedirect("abc1234", CTX)).isEqualTo("https://example.com/target");
        assertThat(meters.counter("shortener.analytics.failures").count()).isEqualTo(1.0);
    }

    // ---- metrics -------------------------------------------------------------------------

    @Test
    void collisionsAndCreationsAreCounted() {
        when(repo.saveAndFlush(any(ShortLink.class)))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.create("https://example.com", null, null);

        assertThat(meters.counter("shortener.code.collisions").count()).isEqualTo(1.0);
        assertThat(meters.counter("shortener.links.created").count()).isEqualTo(1.0);
    }

    // ---- idempotent create ---------------------------------------------------------------

    @Test
    void withoutKeyIdempotentCreateBehavesLikePlainCreate() {
        saveReturnsArgument();

        CreateResult result = service.createIdempotent(null, "https://example.com/a", null, null);

        assertThat(result.replayed()).isFalse();
        verify(repo, never()).findByIdempotencyKey(anyString());
    }

    @Test
    void firstRequestWithKeyCreatesAndStoresKeyAndFingerprint() {
        when(repo.findByIdempotencyKey("k-1")).thenReturn(Optional.empty());
        saveReturnsArgument();

        CreateResult result = service.createIdempotent("k-1", "https://example.com/a", null, Duration.ofMinutes(5));

        assertThat(result.replayed()).isFalse();
        assertThat(result.link().getIdempotencyKey()).isEqualTo("k-1");
        assertThat(result.link().getRequestFingerprint())
                .isEqualTo(LinkService.fingerprint("https://example.com/a", null, Duration.ofMinutes(5)));
    }

    @Test
    void retryWithSameKeyAndSameRequestReplaysTheOriginalLinkWithoutInserting() {
        ShortLink original = ShortLink.create("orig123", "https://example.com/a", T0, null, "k-1",
                LinkService.fingerprint("https://example.com/a", null, null));
        when(repo.findByIdempotencyKey("k-1")).thenReturn(Optional.of(original));

        CreateResult result = service.createIdempotent("k-1", "https://example.com/a", null, null);

        assertThat(result.replayed()).isTrue();
        assertThat(result.link()).isSameAs(original);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        ShortLink original = ShortLink.create("orig123", "https://example.com/a", T0, null, "k-1",
                LinkService.fingerprint("https://example.com/a", null, null));
        when(repo.findByIdempotencyKey("k-1")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.createIdempotent("k-1", "https://example.com/DIFFERENT", null, null))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThatThrownBy(() -> service.createIdempotent("k-1", "https://example.com/a", null, Duration.ofMinutes(5)))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void malformedKeyIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.createIdempotent("bad key!", "https://example.com", null, null))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> service.createIdempotent("", "https://example.com", null, null))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> service.createIdempotent("k".repeat(65), "https://example.com", null, null))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        verify(repo, never()).findByIdempotencyKey(anyString());
    }

    @Test
    void losingARaceOnTheKeyReplaysTheWinnersResult() {
        String fp = LinkService.fingerprint("https://example.com/a", null, null);
        ShortLink winner = ShortLink.create("win1234", "https://example.com/a", T0, null, "k-1", fp);
        // not visible at first (race), visible after our own insert fails on the key's unique constraint
        when(repo.findByIdempotencyKey("k-1")).thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(repo.saveAndFlush(any(ShortLink.class))).thenThrow(new DataIntegrityViolationException("dup key"));

        CreateResult result = service.createIdempotent("k-1", "https://example.com/a", null, null);

        assertThat(result.replayed()).isTrue();
        assertThat(result.link()).isSameAs(winner);
        // the key (not the code) was the violated constraint: no pointless code retries, no phantom collisions
        verify(repo, times(1)).saveAndFlush(any(ShortLink.class));
        assertThat(meters.counter("shortener.code.collisions").count()).isEqualTo(0.0);
    }

    @Test
    void aliasConflictWithoutAMatchingKeyIsStillAConflict() {
        when(repo.findByIdempotencyKey("k-2")).thenReturn(Optional.empty());
        when(repo.existsByCode("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.createIdempotent("k-2", "https://example.com", "taken", null))
                .isInstanceOf(AliasTakenException.class);
    }

    // ---- update expiry (BF-2) ------------------------------------------------------------

    @Test
    void updateExpiryCountsTheNewTtlFromNow_evenForAnExpiredLink() {
        stored(T0.plusSeconds(60));
        clock.advance(Duration.ofSeconds(120)); // expired
        Instant expected = clock.instant().plus(Duration.ofHours(1));
        when(repo.updateExpiry("abc1234", expected)).thenReturn(1);

        service.updateExpiry("abc1234", Duration.ofHours(1));

        verify(repo).updateExpiry("abc1234", expected);
    }

    @Test
    void updateExpiryWithNullTtlRemovesTheExpiry() {
        stored(T0.plusSeconds(60));
        when(repo.updateExpiry("abc1234", null)).thenReturn(1);

        service.updateExpiry("abc1234", null);

        verify(repo).updateExpiry("abc1234", null);
    }

    @Test
    void updateExpiryOfADeactivatedLinkIsGoneAndWritesNothing() {
        stored(null).deactivate();

        assertThatThrownBy(() -> service.updateExpiry("abc1234", Duration.ofHours(1)))
                .isInstanceOf(LinkGoneException.class);
        verify(repo, never()).updateExpiry(anyString(), any());
    }

    @Test
    void updateExpiryLosingARaceWithDeactivationIsGone() {
        stored(null);
        when(repo.updateExpiry(anyString(), any())).thenReturn(0); // deactivated between our read and the UPDATE

        assertThatThrownBy(() -> service.updateExpiry("abc1234", Duration.ofHours(1)))
                .isInstanceOf(LinkGoneException.class);
    }

    @Test
    void updateExpiryOfAnUnknownCodeIsNotFound() {
        when(repo.findByCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateExpiry("nope", Duration.ofHours(1)))
                .isInstanceOf(LinkNotFoundException.class);
    }

    // ---- deactivate ----------------------------------------------------------------------

    @Test
    void deactivateUsesAnAtomicUpdate_neverALoadModifySaveThatCouldOverwriteConcurrentClicks() {
        stored(null);

        service.deactivate("abc1234");
        service.deactivate("abc1234"); // repeating is safe: the SQL guard (active = true) makes it a no-op

        verify(repo, times(2)).deactivateByCode("abc1234");
        verify(repo, never()).save(any(ShortLink.class));
    }

    @Test
    void headStyleResolveHonoursExpiryAndNotFoundButRecordsNoClick() {
        stored(T0.plusSeconds(60));
        assertThat(service.resolveWithoutClick("abc1234")).isEqualTo("https://example.com/target");
        verify(analytics, never()).recordClick(anyString(), any());

        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(() -> service.resolveWithoutClick("abc1234")).isInstanceOf(LinkGoneException.class);
    }

    @Test
    void deactivateUnknownCodeIsNotFound() {
        when(repo.findByCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivate("nope")).isInstanceOf(LinkNotFoundException.class);
    }
}

package com.example.shortener.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientIpResolverTest {

    @Mock HttpServletRequest request;

    private static ClientIpResolver resolver(boolean trustForwardedFor) {
        return new ClientIpResolver(new AppProperties("http://localhost:8080", false, 7, 3, "salt",
                new AppProperties.RateLimit(true, 30, 0.5, trustForwardedFor, 10_000)));
    }

    @Test
    void forwardedHeaderIsIgnoredByDefault_soClientsCannotChooseTheirOwnIdentity() {
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

        assertThat(resolver(false).resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void whenTrustedTheFirstForwardedAddressIsUsed() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(request.getHeader("X-Forwarded-For")).thenReturn("  203.0.113.9 , 10.0.0.1");

        assertThat(resolver(true).resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void whenTrustedButHeaderMissingOrBlankItFallsBackToTheSocketAddress() {
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");

        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        assertThat(resolver(true).resolve(request)).isEqualTo("198.51.100.7");

        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        assertThat(resolver(true).resolve(request)).isEqualTo("198.51.100.7");
    }
}

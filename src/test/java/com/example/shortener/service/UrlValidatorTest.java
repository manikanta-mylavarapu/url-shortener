package com.example.shortener.service;

import com.example.shortener.error.InvalidUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    /** Literal IPs only: parsing a literal never touches DNS, so the test is hermetic. */
    private static InetAddress[] literal(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private final UrlValidator strict = new UrlValidator(true, UrlValidatorTest::literal);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/", "http://10.1.2.3/x", "http://172.16.0.5/", "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",   // cloud metadata endpoint
            "http://100.64.0.1/",                         // carrier-grade NAT
            "http://0.0.0.0/", "http://224.0.0.1/",
            "http://198.18.0.1/", "http://198.19.255.254/", "http://192.0.0.8/",   // benchmarking / IETF-reserved
            "http://240.0.0.1/", "http://255.255.255.255/",                         // reserved / broadcast
            "http://2130706433/", "http://0x7f.1/",                                 // legacy numeric spellings of 127.0.0.1
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:10.0.0.1]/",              // IPv4-mapped IPv6
            "http://[::1]/", "http://[fd00::1]/", "http://[fe80::1]/"
    })
    void blocksPrivateAndInternalTargets(String url) {
        assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://93.184.216.34/f", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi",
            "//93.184.216.34/x", "not a url", "http://93.184.216.34/a b",
            "http://user:secret@93.184.216.34/"
    })
    void blocksNonHttpMalformedAndCredentialUrls(String url) {
        assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blocksBlank(String url) {
        assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void blocksOverlongUrls() {
        String tooLong = "https://93.184.216.34/" + "a".repeat(UrlValidator.MAX_URL_LENGTH);
        assertThatThrownBy(() -> strict.validate(tooLong)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void allowsPublicHttpAndHttpsAndKeepsUrlIntact() {
        assertThat(strict.validate("https://93.184.216.34/path?q=1#frag")).isEqualTo("https://93.184.216.34/path?q=1#frag");
        assertThat(strict.validate("  HTTP://93.184.216.34  ")).isEqualTo("HTTP://93.184.216.34");
        assertThatCode(() -> strict.validate("http://[2606:2800:220:1:248:1893:25c8:1946]/")).doesNotThrowAnyException();
    }

    @Test
    void rejectsHostWhenAnyResolvedAddressIsPrivate() throws Exception {
        // Resolve outside the lambda: getByName throws a checked exception, and Function cannot.
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        InetAddress privateAddress = InetAddress.getByName("10.0.0.1");
        UrlValidator rebind = new UrlValidator(true, host -> new InetAddress[]{publicAddress, privateAddress});
        assertThatThrownBy(() -> rebind.validate("http://mixed.example/")).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsUnresolvableHostWhenGuardEnabled() {
        UrlValidator nx = new UrlValidator(true, host -> {
            throw new IllegalStateException("NXDOMAIN");
        });
        assertThatThrownBy(() -> nx.validate("http://nope.example/")).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void guardDisabledSkipsResolutionButStillChecksScheme() {
        UrlValidator open = new UrlValidator(false, host -> {
            throw new AssertionError("must not resolve when the guard is off");
        });
        assertThatCode(() -> open.validate("http://10.0.0.1/")).doesNotThrowAnyException();
        assertThatThrownBy(() -> open.validate("ftp://example.com/")).isInstanceOf(InvalidUrlException.class);
    }
}

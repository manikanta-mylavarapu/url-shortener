package com.example.shortener.service;

import com.example.shortener.error.InvalidUrlException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Function;

/**
 * Validates and normalizes user-submitted URLs.
 *
 * <p>Guardrails (all rejections become HTTP 400):
 * <ul>
 *   <li>only {@code http} / {@code https}</li>
 *   <li>max 2048 characters, no control characters or whitespace</li>
 *   <li>no embedded credentials ({@code user:pass@host}) - common in phishing links</li>
 *   <li>host must be present; when {@code blockPrivateNetworks} is on, the host must resolve and
 *       none of its addresses may be loopback, private, link-local, CGNAT, multicast or
 *       unspecified (SSRF / internal-network guard)</li>
 * </ul>
 *
 * <p>Limitation: resolution happens once at creation time. A hostile DNS server can change its
 * answer later (DNS rebinding). The service never fetches the target itself, so this is a
 * defense-in-depth control; any future server-side fetch (link previews) must re-validate at
 * connect time.
 */
public final class UrlValidator {

    public static final int MAX_URL_LENGTH = 2048;

    private final boolean blockPrivateNetworks;
    private final Function<String, InetAddress[]> resolver;

    public UrlValidator(boolean blockPrivateNetworks) {
        this(blockPrivateNetworks, UrlValidator::resolveWithJdk);
    }

    /** Resolver is injectable so tests never depend on real DNS. */
    public UrlValidator(boolean blockPrivateNetworks, Function<String, InetAddress[]> resolver) {
        this.blockPrivateNetworks = blockPrivateNetworks;
        this.resolver = resolver;
    }

    /** @return the normalized URL string, or throws {@link InvalidUrlException}. */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("url must not be blank");
        }
        String candidate = raw.strip();
        if (candidate.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("url exceeds " + MAX_URL_LENGTH + " characters");
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new InvalidUrlException("url must not contain whitespace or control characters");
            }
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("url is not syntactically valid");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidUrlException("only http and https urls are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidUrlException("urls with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("url must contain a host");
        }

        if (blockPrivateNetworks) {
            assertPublicHost(host);
        }
        return candidate;
    }

    private void assertPublicHost(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(bare);
        } catch (RuntimeException e) {
            throw new InvalidUrlException("host could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new InvalidUrlException("host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new InvalidUrlException("urls pointing to private or internal networks are not allowed");
            }
        }
    }

    static boolean isNonPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 100.64.0.0/10 carrier-grade NAT (not covered by isSiteLocalAddress)
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 198.18.0.0/15 benchmarking, 192.0.0.0/24 IETF protocol assignments: never legitimate public targets
            if ((first == 198 && (second == 18 || second == 19))
                    || (first == 192 && second == 0 && (b[2] & 0xFF) == 0)) {
                return true;
            }
            // 0.0.0.0/8 "this network"; 240.0.0.0/4 reserved (includes the 255.255.255.255 broadcast address)
            return first == 0 || first >= 240;
        }
        // IPv6 unique local fc00::/7 (not covered by isSiteLocalAddress, which only knows fec0::/10)
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    private static InetAddress[] resolveWithJdk(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}

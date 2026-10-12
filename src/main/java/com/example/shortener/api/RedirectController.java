package com.example.shortener.api;

import com.example.shortener.config.ClientIpResolver;
import com.example.shortener.service.ClickContext;
import com.example.shortener.service.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final LinkService links;
    private final ClientIpResolver clientIps;

    public RedirectController(LinkService links, ClientIpResolver clientIps) {
        this.links = links;
        this.clientIps = clientIps;
    }

    /**
     * 302 + no-store (not 301): a cacheable permanent redirect would let browsers and proxies skip
     * us, silently breaking click analytics, expiry and deactivation.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        ClickContext context = new ClickContext(
                clientIps.resolve(request),
                request.getHeader(HttpHeaders.USER_AGENT),
                request.getHeader(HttpHeaders.REFERER));
        // HEAD shares this handler; it must not count as a visit (crawlers/probes), but gets identical 404/410 behaviour.
        String target = "HEAD".equalsIgnoreCase(request.getMethod())
                ? links.resolveWithoutClick(code)
                : links.resolveForRedirect(code, context);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .build();
    }
}

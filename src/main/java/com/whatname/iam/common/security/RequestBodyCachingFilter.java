package com.whatname.iam.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Keeps the exact bytes consumed by MVC available to trusted-service authentication. */
@Component
public final class RequestBodyCachingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request instanceof ContentCachingRequestWrapper
                ? request : new ContentCachingRequestWrapper(request), response);
    }
}

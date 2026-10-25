package com.whatname.iam.common.config;

import com.whatname.iam.service.AccessService;
import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** Enforce the registered IAM menu/API mapping for every management controller method. */
@Component
public class ManagementInterceptor implements HandlerInterceptor, WebMvcConfigurer {
  private final AccessService access;
  private static final Set<String> IDENTITY =
      Set.of(
          "/api/v1/auth/login",
          "/api/v1/auth/me",
          "/api/v1/auth/menus",
          "/api/v1/auth/logout",
          "/api/v1/auth/password",
          "/api/v1/auth/check",
          "/api/v1/system/ping",
          "/api/v1/system/database");

  public ManagementInterceptor(AccessService access) {
    this.access = access;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/v1/**");
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod)) return true;
    String path = request.getRequestURI();
    if (IDENTITY.contains(path)
        || path.startsWith("/api/v1/integrations/")
        || path.startsWith("/api/v1/demo/")) return true;
    String route = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    var actor = access.current(request.getHeader("Authorization"));
    access.requireApi(actor, request.getMethod(), route);
    return true;
  }
}

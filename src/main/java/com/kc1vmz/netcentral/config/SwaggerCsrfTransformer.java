package com.kc1vmz.netcentral.config;

import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.stereotype.Component;

/** Keep the existing session CSRF repository, including its masked token handling. */
@Component
public class SwaggerCsrfTransformer extends SwaggerIndexPageTransformer {
    public SwaggerCsrfTransformer(SwaggerUiConfigProperties ui, SwaggerUiOAuthProperties oauth,
            SwaggerWelcomeCommon welcome, ObjectMapperProvider mapper) {
        super(ui, oauth, welcome, mapper);
    }

    @Override
    protected String addCSRF(String html) {
        return html.replace("SwaggerUIBundle({", "SwaggerUIBundle({" + """
            requestInterceptor: async function(request) {
              const target = new URL(request.url, window.location.href);
              const base = window.location.pathname.substring(0, window.location.pathname.indexOf('/swagger-ui/'));
              if (target.origin === window.location.origin && target.pathname.startsWith(base + '/api/v1/')) {
                request.credentials = 'same-origin';
                if (!['GET', 'HEAD', 'OPTIONS'].includes((request.method || 'GET').toUpperCase())) {
                  const response = await fetch(base + '/api/v1/csrf', {credentials: 'same-origin', cache: 'no-store'});
                  if (!response.ok) throw new Error('Could not obtain CSRF token; sign in again.');
                  const csrf = await response.json();
                  request.headers = request.headers || {};
                  request.headers[csrf.headerName] = csrf.token;
                }
              }
              return request;
            },
            """);
    }
}

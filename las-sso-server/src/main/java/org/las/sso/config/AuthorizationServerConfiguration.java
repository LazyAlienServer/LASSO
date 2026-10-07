package org.las.sso.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.las.sso.identity.JdbcLasUserDetailsService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.oidc.OidcPasswordAuthenticationFilter;
import org.las.sso.oidc.OidcReauthenticationService;
import org.las.sso.oidc.RefreshTokenFamilyAuthenticationProvider;
import org.las.sso.oidc.RefreshTokenFamilyService;
import org.las.sso.oidc.SecurityAwareAuthorizationService;
import org.las.sso.security.AccountSessionValidationFilter;
import org.las.sso.security.ClientAuthenticationFailureHandler;
import org.las.sso.security.ClientAuthenticationRateLimitConverter;
import org.las.sso.security.ClientNetworkKey;
import org.las.sso.security.FirstPartyClientAuthenticationFilter;
import org.las.sso.security.LasAuthenticationFailureHandler;
import org.las.sso.security.LasAuthenticationSuccessHandler;
import org.las.sso.security.LoginRiskAuthenticationFilter;
import org.las.sso.security.PasswordChangeRequiredFilter;
import org.las.sso.security.PostgresRateLimiter;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.session.SsoGlobalLogoutHandler;
import org.las.sso.session.UserSessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
public class AuthorizationServerConfiguration {

    @Bean
    @Order(1)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            AuthorizationServerSettings authorizationServerSettings,
            RequestCache loginRequestCache,
            RecentAuthenticationService recentAuthentication,
            OidcReauthenticationService oidcReauthentication,
            AccountSessionValidationFilter accountSessionValidationFilter,
            PasswordChangeRequiredFilter passwordChangeRequiredFilter,
            RefreshTokenFamilyService tokenFamilies,
            ClientAuthenticationFailureHandler clientAuthenticationFailureHandler,
            ClientAuthenticationRateLimitConverter clientAuthenticationRateLimitConverter,
            PlatformTransactionManager transactionManager,
            SsoGlobalLogoutHandler globalLogoutHandler,
            org.las.sso.observability.SsoMetrics metrics)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(
                        authorizationServerConfigurer,
                        authorizationServer ->
                                authorizationServer
                                        .authorizationEndpoint(
                                                endpoint ->
                                                        endpoint.authenticationProviders(
                                                                providers -> {
                                                                    for (var provider : providers) {
                                                                        if (provider
                                                                                instanceof
                                                                                OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                                                        codeProvider) {
                                                                            codeProvider
                                                                                    .setAuthenticationValidator(
                                                                                            new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                                                                                                    .andThen(
                                                                                                            OidcReauthenticationService
                                                                                                                    ::validateMaxAge));
                                                                        }
                                                                    }
                                                                }))
                                        .clientAuthentication(
                                                client ->
                                                        client.authenticationConverters(
                                                                        converters ->
                                                                                converters.addFirst(
                                                                                        clientAuthenticationRateLimitConverter))
                                                                .errorResponseHandler(
                                                                        clientAuthenticationFailureHandler))
                                        .tokenEndpoint(
                                                tokenEndpoint ->
                                                        tokenEndpoint.authenticationProviders(
                                                                providers -> {
                                                                    boolean refreshProviderWrapped =
                                                                            false;
                                                                    for (int index = 0;
                                                                            index
                                                                                    < providers
                                                                                            .size();
                                                                            index++) {
                                                                        if (providers.get(index)
                                                                                instanceof
                                                                                OAuth2RefreshTokenAuthenticationProvider
                                                                                        provider) {
                                                                            providers.set(
                                                                                    index,
                                                                                    new RefreshTokenFamilyAuthenticationProvider(
                                                                                            provider,
                                                                                            tokenFamilies,
                                                                                            new TransactionTemplate(
                                                                                                    transactionManager),
                                                                                            metrics));
                                                                            refreshProviderWrapped =
                                                                                    true;
                                                                            break;
                                                                        }
                                                                    }
                                                                    if (!refreshProviderWrapped) {
                                                                        throw new IllegalStateException(
                                                                                "Spring Authorization Server refresh provider was not found");
                                                                    }
                                                                }))
                                        .oidc(
                                                oidc -> {
                                                    var defaultLogoutHandler =
                                                            new OidcLogoutAuthenticationSuccessHandler();
                                                    oidc.logoutEndpoint(
                                                            logout ->
                                                                    logout.logoutResponseHandler(
                                                                            (request,
                                                                                    response,
                                                                                    authentication) -> {
                                                                                globalLogoutHandler
                                                                                        .logout(
                                                                                                request,
                                                                                                response,
                                                                                                authentication);
                                                                                defaultLogoutHandler
                                                                                        .onAuthenticationSuccess(
                                                                                                request,
                                                                                                response,
                                                                                                authentication);
                                                                            }));
                                                }))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // OIDC UserInfo is a server-side Bearer request, not an SSO browser-cookie login.
                .oauth2ResourceServer(
                        resourceServer ->
                                resourceServer
                                        // The client-secret budget converter is an application
                                        // bean, but is not a Bearer parser.
                                        .authenticationConverter(
                                                new BearerTokenAuthenticationConverter())
                                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .defaultAuthenticationEntryPointFor(
                                                new BearerTokenAuthenticationEntryPoint(),
                                                PathPatternRequestMatcher.withDefaults()
                                                        .matcher(
                                                                authorizationServerSettings
                                                                        .getOidcUserInfoEndpoint()))
                                        .defaultAuthenticationEntryPointFor(
                                                new LoginUrlAuthenticationEntryPoint("/login"),
                                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .requestCache(requests -> requests.requestCache(loginRequestCache))
                .addFilterAfter(accountSessionValidationFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(
                        new OidcPasswordAuthenticationFilter(
                                oidcReauthentication, recentAuthentication, loginRequestCache),
                        AnonymousAuthenticationFilter.class)
                .addFilterAfter(passwordChangeRequiredFilter, AccountSessionValidationFilter.class);
        return http.build();
    }

    @Bean
    ClientAuthenticationRateLimitConverter clientAuthenticationRateLimitConverter(
            PostgresRateLimiter rateLimiter, ClientNetworkKey clientNetworkKey) {
        return new ClientAuthenticationRateLimitConverter(rateLimiter, clientNetworkKey);
    }

    @Bean
    FirstPartyClientAuthenticationFilter firstPartyClientAuthenticationFilter(
            RegisteredClientRepository registeredClients,
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            org.las.sso.security.FirstPartyAuthenticationBudget budget,
            ClientNetworkKey clientNetworkKey,
            org.las.sso.identity.UuidV7Generator requestIds,
            org.las.sso.observability.SsoMetrics metrics) {
        return new FirstPartyClientAuthenticationFilter(
                registeredClients,
                jdbcClient,
                passwordEncoder,
                budget,
                clientNetworkKey,
                requestIds,
                metrics);
    }

    @Bean
    @Order(2)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            DaoAuthenticationProvider authenticationProvider,
            LasAuthenticationSuccessHandler successHandler,
            LasAuthenticationFailureHandler failureHandler,
            RequestCache loginRequestCache,
            LoginRiskAuthenticationFilter loginRiskAuthenticationFilter,
            FirstPartyClientAuthenticationFilter firstPartyClientAuthenticationFilter,
            AccountSessionValidationFilter accountSessionValidationFilter,
            PasswordChangeRequiredFilter passwordChangeRequiredFilter,
            SsoGlobalLogoutHandler globalLogoutHandler,
            SpringSessionRememberMeServices rememberMeServices)
            throws Exception {
        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .requestMatchers(
                                                "/login",
                                                "/register",
                                                "/password-recovery",
                                                "/error",
                                                "/.well-known/appspecific/com.chrome.devtools.json",
                                                "/assets/**",
                                                "/webjars/**",
                                                "/api/v1/csrf",
                                                "/api/v1/captcha/challenges",
                                                "/api/v1/registrations",
                                                "/api/v1/recovery/sessions",
                                                "/api/v1/recovery/password")
                                        .permitAll()
                                        .requestMatchers("/internal/v1/principals/*/service-access")
                                        .hasRole("FIRST_PARTY_SERVICE")
                                        .requestMatchers("/admin/**", "/api/v1/admin/**")
                                        .hasRole("SSO_ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .authenticationProvider(authenticationProvider)
                .formLogin(
                        form ->
                                form.loginPage("/login")
                                        .permitAll()
                                        .successHandler(successHandler)
                                        .failureHandler(failureHandler))
                .logout(logout -> logout.addLogoutHandler(globalLogoutHandler))
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                .requestCache(requests -> requests.requestCache(loginRequestCache))
                .addFilterBefore(
                        loginRiskAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        firstPartyClientAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(accountSessionValidationFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(passwordChangeRequiredFilter, AccountSessionValidationFilter.class);
        return http.build();
    }

    @Bean
    RequestCache loginRequestCache() {
        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(
                request -> {
                    if (!"GET".equals(request.getMethod())) {
                        return false;
                    }
                    String path = request.getRequestURI();
                    String contextPath = request.getContextPath();
                    if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
                        path = path.substring(contextPath.length());
                    }
                    return switch (path) {
                        case "/",
                                "/account",
                                "/account/security",
                                "/account/sessions",
                                "/account/recovery-codes",
                                "/account/authorizations",
                                "/account/delete",
                                "/password-change",
                                "/password-change-required",
                                "/admin/users",
                                "/admin/audit",
                                "/admin/deletion-requests",
                                "/admin/clients",
                                "/oauth2/authorize" ->
                                true;
                        default -> false;
                    };
                });
        return requestCache;
    }

    @Bean
    SpringSessionRememberMeServices rememberMeServices() {
        var services = new SpringSessionRememberMeServices();
        services.setRememberMeParameterName("rememberDevice");
        services.setValiditySeconds(Math.toIntExact(Duration.ofDays(14).toSeconds()));
        return services;
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(
            JdbcLasUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    PasswordEncoder passwordEncoder(org.las.sso.observability.SsoMetrics metrics) {
        var encoders = new HashMap<String, PasswordEncoder>();
        encoders.put("argon2id", new Argon2PasswordEncoder(16, 32, 1, 19_456, 2));
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        return new org.las.sso.observability.MeteredPasswordEncoder(
                new DelegatingPasswordEncoder("argon2id", encoders), metrics);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService loginFailureJitterScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("login-failure-jitter-").factory());
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcOperations jdbcOperations,
            RegisteredClientRepository registeredClientRepository,
            JdbcClient jdbcClient,
            UserSessionService userSessions,
            RecentAuthenticationService recentAuthentication,
            UuidV7Generator uuidGenerator,
            RefreshTokenFamilyService tokenFamilies,
            PlatformTransactionManager transactionManager,
            org.las.sso.security.TransientSecurityKeyHasher keyHasher) {
        var polymorphicTypes =
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType(LasUserPrincipal.class)
                        .allowIfSubType(Long.class)
                        .allowIfSubType(Collection.class);
        var objectMapper =
                JsonMapper.builder()
                        .addModules(
                                SecurityJacksonModules.getModules(
                                        JdbcOAuth2AuthorizationService.class.getClassLoader(),
                                        polymorphicTypes))
                        .addMixIn(LasUserPrincipal.class, LasUserPrincipalMixin.class)
                        .build();

        var rowMapper =
                new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(
                        registeredClientRepository, objectMapper);

        var service =
                new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
        service.setAuthorizationRowMapper(rowMapper);
        service.setAuthorizationParametersMapper(
                new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(
                        objectMapper));
        return new SecurityAwareAuthorizationService(
                service,
                jdbcClient,
                userSessions,
                recentAuthentication,
                uuidGenerator,
                tokenFamilies,
                new TransactionTemplate(transactionManager),
                keyHasher);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(
                jdbcOperations, registeredClientRepository);
    }

    @Bean
    JdbcTemplate jdbcOperations(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(LasSsoProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer().toString()).build();
    }

    @Bean
    TokenSettings firstPartyTokenSettings() {
        return TokenSettings.builder()
                .authorizationCodeTimeToLive(Duration.ofMinutes(2))
                .accessTokenTimeToLive(Duration.ofMinutes(15))
                .refreshTokenTimeToLive(Duration.ofDays(14))
                .reuseRefreshTokens(false)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> identityClaimsCustomizer(JdbcClient jdbcClient) {
        return context -> {
            if (context.getPrincipal().getPrincipal() instanceof LasUserPrincipal principal) {
                context.getClaims().subject(principal.getName());
                if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
                    context.getClaims().claim("acr", "urn:las:loa:1").claim("amr", List.of("pwd"));
                    // Freeze password evidence at authorization; refresh must never renew it.
                    context.getClaims().claims(claims -> claims.remove("auth_time"));
                    if (context.getAuthorization() != null
                            && context.getAuthorization()
                                            .getAttribute(
                                                    SecurityAwareAuthorizationService
                                                            .PASSWORD_AUTH_TIME_ATTRIBUTE)
                                    instanceof Long at) {
                        context.getClaims()
                                .claim("auth_time", Date.from(Instant.ofEpochSecond(at)));
                    }
                    if (context.getAuthorizedScopes().contains("profile")) {
                        context.getClaims().claim("preferred_username", principal.loginUsername());
                    }
                }
            }
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                List<String> audiences =
                        jdbcClient
                                .sql(
                                        """
                        SELECT unnest(resource_audiences) AS audience
                          FROM registered_client_metadata
                         WHERE registered_client_id = :registeredClientId
                        """)
                                .param("registeredClientId", context.getRegisteredClient().getId())
                                .query(String.class)
                                .list();
                if (audiences.isEmpty()) {
                    throw new IllegalStateException(
                            "No resource audience configured for registered client");
                }
                context.getClaims().audience(audiences);
            }
            if (context.getAuthorization() != null) {
                jdbcClient
                        .sql(
                                """
                        SELECT sid FROM oauth2_authorization_security
                         WHERE authorization_id = :authorizationId AND sid IS NOT NULL
                        """)
                        .param("authorizationId", context.getAuthorization().getId())
                        .query(String.class)
                        .optional()
                        .ifPresent(sid -> context.getClaims().claim("sid", sid));
            }
        };
    }

    @JsonAutoDetect(
            fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private abstract static class LasUserPrincipalMixin {

        @JsonCreator
        LasUserPrincipalMixin(
                @JsonProperty("userId") UUID userId,
                @JsonProperty("loginUsername") String loginUsername,
                @JsonProperty("passwordHash") String passwordHash,
                @JsonProperty("credentialsVersion") long credentialsVersion,
                @JsonProperty("enabled") boolean enabled,
                @JsonProperty("passwordChangeRequired") boolean passwordChangeRequired,
                @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities) {}
    }
}

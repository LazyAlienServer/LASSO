package org.las.sso.client;

import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Public ownership boundary for OAuth2 authorized clients.
 *
 * <p>Implementations must scope save/load/remove to the local business login
 * session and registration, never to the human subject alone. The default
 * starter implementation is HttpSession-backed; a consuming BFF may replace
 * it with its own protected persistent adapter.</p>
 */
public interface LasSsoAuthorizedClientRepository extends OAuth2AuthorizedClientRepository {
}

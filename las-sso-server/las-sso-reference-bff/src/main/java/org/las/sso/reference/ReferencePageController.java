package org.las.sso.reference;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class ReferencePageController {

    @GetMapping("/")
    String home(Authentication authentication, Model model) {
        boolean authenticated = authentication instanceof OAuth2AuthenticationToken;
        model.addAttribute("authenticated", authenticated);
        if (authenticated && authentication.getPrincipal() instanceof OidcUser user) {
            model.addAttribute("username", user.getPreferredUsername());
            model.addAttribute("subject", user.getSubject());
        }
        return "index";
    }

    @GetMapping("/protected")
    String protectedPage(Authentication authentication, Model model) {
        OidcUser user = (OidcUser) authentication.getPrincipal();
        model.addAttribute("username", user.getPreferredUsername());
        model.addAttribute("subject", user.getSubject());
        return "protected";
    }
}

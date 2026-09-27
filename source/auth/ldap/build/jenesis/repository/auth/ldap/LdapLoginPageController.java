package build.jenesis.repository.auth.ldap;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import build.jenesis.repository.ui.ConsoleScreen;

/** The directory sign-in form. */
@Controller
@ConsoleScreen
public class LdapLoginPageController {

    private final LdapProperties properties;

    LdapLoginPageController(LdapProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/ui/login/ldap")
    public String form(Model model) {
        model.addAttribute("directory", properties.getName().trim());
        return "ldap/form";
    }
}

package build.jenesis.repository.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The installed-providers screen: every SPI this deployment carries and what answers it.
 *
 * <p>A read with no store access and no configuration of its own - the module graph is the whole source - so it is
 * safe on the request path and costs the same however large the repository is.
 */
@Controller
public class SpiCatalogScreenController {

    @GetMapping("/catalog")
    public String catalog(Model model) {
        model.addAttribute("catalog", SpiCatalog.current());
        return "console/catalog";
    }
}

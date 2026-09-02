package build.jenesis.repository.ui;

import module java.base;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The installed-providers screen: every SPI this deployment carries, what answers it, and - where the deployment can
 * say - whether each answer is switched on and what settings its module reads.
 *
 * <p>A read with no store access of its own, so it is safe on the request path and costs the same however large the
 * repository is. What it renders comes from the {@link SpiCatalogSource} the deployment contributed; the default
 * reports the module graph undecorated.
 */
@Controller
public class SpiCatalogScreenController {

    private final SpiCatalogSource source;

    public SpiCatalogScreenController(SpiCatalogSource source) {
        this.source = source;
    }

    @GetMapping("/catalog")
    public String catalog(Model model) throws IOException {
        model.addAttribute("spis", source.catalog());
        return "console/catalog";
    }
}

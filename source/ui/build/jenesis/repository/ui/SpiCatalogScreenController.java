package build.jenesis.repository.ui;

import module java.base;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The installed-providers screen: every SPI this deployment carries, what answers it, and where known whether each is
 * on and which settings its module reads, from the deployment's {@link SpiCatalogSource}. It reads no store of its
 * own.
 */
@Controller
@ConsoleScreen
public class SpiCatalogScreenController {

    private final SpiCatalogSource source;

    public SpiCatalogScreenController(SpiCatalogSource source) {
        this.source = source;
    }

    @GetMapping("/ui/settings/modules/contracts")
    public String catalog(Model model) throws IOException {
        model.addAttribute("spis", source.catalog());
        return "console/catalog";
    }
}

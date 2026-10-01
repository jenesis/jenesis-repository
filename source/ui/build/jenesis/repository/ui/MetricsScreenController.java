package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.observation.ObservabilityReport;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The metrics overview: every metric, health state and background-task status this deployment reports, with its
 * description, from the same collected report as the observability API and the Actuator read. Read-only and
 * searchable; an empty report shows an empty state.
 */
@Controller
@ConsoleScreen
public class MetricsScreenController {

    private final ConfigurableListableBeanFactory beans;

    public MetricsScreenController(ConfigurableListableBeanFactory beans) {
        this.beans = beans;
    }

    /** The report of this context: the discovered sources and every source among the singletons it has built. */
    @GetMapping("/ui/metrics")
    public String metrics(Model model) throws IOException {
        model.addAttribute("report", ObservabilityReport.of(Arrays.stream(beans.getSingletonNames()).map(beans::getSingleton).filter(Objects::nonNull).toList()));
        return "console/metrics";
    }
}

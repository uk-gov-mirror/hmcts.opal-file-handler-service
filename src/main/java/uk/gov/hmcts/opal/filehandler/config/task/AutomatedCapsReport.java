package uk.gov.hmcts.opal.filehandler.config.task;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.opal.filehandler.config.CapsReportBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.service.CapsReportBaisFileProcessorService;

@Component
@ConditionalOnProperty(name = "opal.automated-task", havingValue = "CAPSReport")
@Slf4j
@RequiredArgsConstructor
public class AutomatedCapsReport implements ApplicationRunner {

    private final CapsReportBaisFileProcessorService processorService;
    private final CapsReportBaisFileProcessorConfiguration processorConfiguration;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        log.info("Starting automated CAPS report");

        processorService.run(processorConfiguration);

        log.info("Completed automated CAPS report");
    }
}

package uk.gov.hmcts.opal.filehandler.config.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.opal.filehandler.config.BTEckohBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.service.BTEckohBaisFileProcessorService;

@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
    "'${opal.automated-task}'.equals('BTEckohFileTransferJob') or ${opal.testing-support-endpoints.enabled}"
)
@Slf4j
public class AutomatedBTEckohFileTransferJob implements TaskConfiguration {

    private final BTEckohBaisFileProcessorService fileProcessorService;
    private final BTEckohBaisFileProcessorConfiguration configuration;

    @Override
    public void run() {
        log.info("Starting BTEckoh File Transfer Job");

        fileProcessorService.run(configuration);

        log.info("Completed BTEckoh File Transfer Job");
    }
}

package uk.gov.hmcts.opal.filehandler.config.task;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.opal.filehandler.config.BTEckohBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.service.BTEckohBaisFileProcessorService;

@ExtendWith(MockitoExtension.class)
@TestPropertySource(properties = {
    "opal.automated-task=BTEckohFileTransferJob"
})
public class AutomatedTaskBTEckohFileTransferJobTest {

    @Mock
    private BTEckohBaisFileProcessorService service;

    @Mock
    private BTEckohBaisFileProcessorConfiguration config;

    @InjectMocks
    private AutomatedBTEckohFileTransferJob job;

    @Test
    void jobCallsServiceRun() {
        job.run();
        verify(service).run(config);
    }
}

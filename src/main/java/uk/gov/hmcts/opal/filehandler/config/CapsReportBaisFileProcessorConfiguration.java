package uk.gov.hmcts.opal.filehandler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component("capsReportBaisFileProcessorConfig")
@ConfigurationProperties("opal.file-handler-service.file-types.caps-report")
public class CapsReportBaisFileProcessorConfiguration extends AbstractBaisFileProcessorConfiguration {
}

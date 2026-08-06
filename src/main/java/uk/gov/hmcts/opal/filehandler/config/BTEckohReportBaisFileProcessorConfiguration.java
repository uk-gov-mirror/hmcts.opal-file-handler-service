package uk.gov.hmcts.opal.filehandler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component("BTEckohReportBaisFileProcessorConfig")
@ConfigurationProperties("opal.file-handler-service.file-types.bteckoh-report")
public class BTEckohReportBaisFileProcessorConfiguration extends AbstractBaisFileProcessorConfiguration {
}

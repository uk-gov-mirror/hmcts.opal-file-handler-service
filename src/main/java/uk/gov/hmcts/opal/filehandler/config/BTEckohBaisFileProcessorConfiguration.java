package uk.gov.hmcts.opal.filehandler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component("BTEckohBaisFileProcessorConfig")
@ConfigurationProperties("opal.file-handler-service.file-types.bteckoh-transfer")
public class BTEckohBaisFileProcessorConfiguration extends AbstractBaisFileProcessorConfiguration {
}

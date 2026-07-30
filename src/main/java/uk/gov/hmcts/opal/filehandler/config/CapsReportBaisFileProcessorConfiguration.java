package uk.gov.hmcts.opal.filehandler.config;

import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uk.gov.hmcts.opal.filehandler.entity.Interface;

@Getter
@Setter
@ConfigurationProperties("opal.file-handler-service.file-types.caps-report")
public class CapsReportBaisFileProcessorConfiguration implements BaisFileProcessorConfiguration {

    private String containerName;

    private String featureFlag;

    private Pattern fileNameRegex;

    private Interface source;

    private Interface target;

    private String sftpUsername;

}

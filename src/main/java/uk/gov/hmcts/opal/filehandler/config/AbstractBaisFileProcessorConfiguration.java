package uk.gov.hmcts.opal.filehandler.config;

import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import uk.gov.hmcts.opal.filehandler.entity.Interface;

@Getter
@Setter
public abstract class AbstractBaisFileProcessorConfiguration implements BaisFileProcessorConfiguration {

    private String containerName;

    private String featureFlag;

    private Pattern fileNameRegex;

    private Interface source;

    private Interface target;

    private String sftpUsername;

}

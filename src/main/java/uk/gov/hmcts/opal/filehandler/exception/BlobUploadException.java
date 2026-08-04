package uk.gov.hmcts.opal.filehandler.exception;

import java.util.UUID;
import lombok.Getter;

@Getter
public class BlobUploadException extends RuntimeException {

    private final UUID filestoreUuid;
    private final String containerName;

    public BlobUploadException(UUID filestoreUuid, String containerName, RuntimeException cause) {
        super(cause.getMessage(), cause);

        this.filestoreUuid = filestoreUuid;
        this.containerName = containerName;
    }
}

package uk.gov.hmcts.opal.filehandler.service.blobstore;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.opal.filehandler.exception.BlobChecksumValidationException;
import uk.gov.hmcts.opal.filehandler.exception.BlobNotFoundException;
import uk.gov.hmcts.opal.filehandler.exception.BlobStorageContainerNotFoundException;
import uk.gov.hmcts.opal.filehandler.exception.BlobUploadException;

@Service
@Slf4j(topic = "opal.InterfaceFilesBlobStoreService")
@AllArgsConstructor
public class InterfaceFileBlobStoreService {

    private final BlobServiceClient blobServiceClient;

    public void uploadBaisFile(UUID fileUuid, String containerName, InputStream stream, String expectedChecksum) {
        BlobClient blobClient = blobServiceClient
            .getBlobContainerClient(containerName)
            .getBlobClient(fileUuid.toString());

        try {
            blobClient.upload(stream);
        } catch (RuntimeException exception) {
            deleteFailedUpload(blobClient, exception);
            throw new BlobUploadException(fileUuid, containerName, exception);
        }

        try {
            validateChecksum(blobClient, fileUuid, expectedChecksum);
        } catch (BlobChecksumValidationException exception) {
            deleteFailedUpload(blobClient, exception);
            throw exception;
        }
    }

    private void validateChecksum(BlobClient blobClient, UUID filestoreUuid, String expectedChecksum) {
        byte[] actualMd5 = blobClient.getProperties().getContentMd5();
        String actualChecksum = actualMd5 == null ? null : HexFormat.of().formatHex(actualMd5);

        if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            throw new BlobChecksumValidationException(
                filestoreUuid, expectedChecksum, actualChecksum);
        }
    }

    private void deleteFailedUpload(BlobClient blobClient, RuntimeException uploadException) {
        try {
            blobClient.deleteIfExists();
        } catch (RuntimeException deletionException) {
            uploadException.addSuppressed(deletionException);
        }
    }

    protected BlobContainerClient getBlobContainerClient(String containerName) {
        BlobContainerClient container = blobServiceClient.getBlobContainerClient(containerName);
        if (!container.exists()) {
            throw new BlobStorageContainerNotFoundException(
                String.format("Blob container \"%s\" does not exist", containerName)
            );
        }
        return container;
    }

    protected BlobClient getBlobClient(BlobContainerClient blobContainerClient, String file) {
        BlobClient blob = blobContainerClient.getBlobClient(file);
        if (Boolean.FALSE.equals(blob.exists())) {
            return null;
        }
        return blob;
    }

    protected BinaryData getFileContents(BlobClient blob) {
        return blob.downloadContent();
    }

    public BinaryData fetchInterfaceFile(long interfaceFileId,UUID fileUUID, String containerName) {
        BlobContainerClient container = getBlobContainerClient(containerName);
        BlobClient blob = getBlobClient(container, fileUUID.toString());
        if (blob == null) {
            throw new BlobNotFoundException(String.format("Expected interface file id: %d to exist in blobstore "
                    + "container: \"%s\" with name \"%s\" but this could not be located.",
                interfaceFileId, containerName, fileUUID));
        }

        return getFileContents(blob);
    }
}

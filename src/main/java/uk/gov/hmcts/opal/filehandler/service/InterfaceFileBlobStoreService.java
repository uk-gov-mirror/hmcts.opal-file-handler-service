package uk.gov.hmcts.opal.filehandler.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.opal.filehandler.exception.BlobChecksumValidationException;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceFileBlobStoreService {

    private final BlobServiceClient blobServiceClient;

    public void uploadBaisFile(UUID fileUuid, String containerName, InputStream stream, String expectedChecksum) {
        BlobClient blobClient = blobServiceClient
            .getBlobContainerClient(containerName)
            .getBlobClient(fileUuid.toString());

        blobClient.upload(stream);

        try {
            validateChecksum(blobClient, fileUuid, expectedChecksum);
        } catch (BlobChecksumValidationException exception) {
            log.error("Checksum validation failed file {}", fileUuid, exception);
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
}

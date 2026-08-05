package uk.gov.hmcts.opal.filehandler.service.blobstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.opal.filehandler.exception.BlobChecksumValidationException;
import uk.gov.hmcts.opal.filehandler.exception.BlobNotFoundException;
import uk.gov.hmcts.opal.filehandler.exception.BlobStorageContainerNotFoundException;
import uk.gov.hmcts.opal.filehandler.exception.BlobUploadException;

@ExtendWith(MockitoExtension.class)
class InterfaceFileBlobStoreServiceTest {

    private static final long INTERFACE_FILE_ID = 1L;
    private static final String BLOB_CONTAINER_NAME = "caps-report";
    private static final String EXPECTED_CHECKSUM = "1fa7130130167122bb83decf6cb3bdb1";
    private static final String MISMATCHED_CHECKSUM = "00000000000000000000000000000000";
    private static final String UPLOAD_FAILURE_MESSAGE = "Blob upload failed";
    private static final byte[] BAIS_FILE_CONTENT = "CAPS report".getBytes(StandardCharsets.UTF_8);
    private static final UUID FILESTORE_UUID = UUID.fromString("eea2bfd9-4eed-4e54-81e9-2bcc9b6b1810");
    private static final String BLOB_NAME = FILESTORE_UUID.toString();

    @Mock
    private BlobServiceClient blobServiceClient;

    @Mock
    private BlobContainerClient blobContainerClient;

    @Mock
    private BlobClient blobClient;

    @Mock
    private BlobProperties blobProperties;

    @Mock
    private BinaryData downloadedFileContents;

    @InjectMocks
    private InterfaceFileBlobStoreService blobStoreService;

    private InputStream baisFileStream;

    @BeforeEach
    void setUp() {
        baisFileStream = new ByteArrayInputStream(BAIS_FILE_CONTENT);
    }

    @Test
    void uploadBaisFile_uploadsFileWhenChecksumMatches() {
        mockBlobLocation();

        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getContentMd5()).thenReturn(HexFormat.of().parseHex(EXPECTED_CHECKSUM));

        blobStoreService.uploadBaisFile(FILESTORE_UUID, BLOB_CONTAINER_NAME, baisFileStream, EXPECTED_CHECKSUM);

        verify(blobContainerClient).getBlobClient(BLOB_NAME);
        verify(blobClient).upload(baisFileStream);
        verify(blobClient, never()).deleteIfExists();
    }

    @Test
    void uploadBaisFile_deletesBlobAndThrowsTypedExceptionWhenChecksumDiffers() {
        mockBlobLocation();

        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getContentMd5()).thenReturn(HexFormat.of().parseHex(MISMATCHED_CHECKSUM));

        BlobChecksumValidationException exception = assertThrows(
            BlobChecksumValidationException.class,
            () -> blobStoreService.uploadBaisFile(
                FILESTORE_UUID, BLOB_CONTAINER_NAME, baisFileStream, EXPECTED_CHECKSUM));

        assertThat(exception.getFilestoreUuid()).isEqualTo(FILESTORE_UUID);
        assertThat(exception.getExpectedChecksum()).isEqualTo(EXPECTED_CHECKSUM);
        assertThat(exception.getActualChecksum()).isEqualTo(MISMATCHED_CHECKSUM);
        assertThat(exception)
            .hasMessage("Blob checksum validation failed for filestore UUID '%s': expected '%s' but was '%s'"
                .formatted(FILESTORE_UUID, EXPECTED_CHECKSUM, MISMATCHED_CHECKSUM));
        verify(blobClient).deleteIfExists();
    }

    @Test
    void uploadBaisFile_deletesPartialBlobAndWrapsUploadFailure() {
        mockBlobLocation();

        RuntimeException uploadFailure = new RuntimeException(UPLOAD_FAILURE_MESSAGE);
        doThrow(uploadFailure).when(blobClient).upload(any(InputStream.class));

        BlobUploadException exception = assertThrows(
            BlobUploadException.class,
            () -> blobStoreService.uploadBaisFile(
                FILESTORE_UUID, BLOB_CONTAINER_NAME, baisFileStream, EXPECTED_CHECKSUM));

        assertThat(exception.getFilestoreUuid()).isEqualTo(FILESTORE_UUID);
        assertThat(exception.getContainerName()).isEqualTo(BLOB_CONTAINER_NAME);
        assertThat(exception).hasMessage(UPLOAD_FAILURE_MESSAGE).hasCause(uploadFailure);
        verify(blobClient).deleteIfExists();
    }

    @Test
    void fetchInterfaceFile_returnsDownloadedFile() {
        mockExistingBlob();

        when(blobClient.downloadContent()).thenReturn(downloadedFileContents);

        BinaryData response = blobStoreService.fetchInterfaceFile(
            INTERFACE_FILE_ID, FILESTORE_UUID, BLOB_CONTAINER_NAME);

        assertThat(response).isSameAs(downloadedFileContents);
        verify(blobServiceClient).getBlobContainerClient(BLOB_CONTAINER_NAME);
        verify(blobContainerClient).getBlobClient(BLOB_NAME);
        verify(blobClient).downloadContent();
    }

    @Test
    void fetchInterfaceFile_throwsExceptionWhenContainerDoesNotExist() {
        when(blobServiceClient.getBlobContainerClient(BLOB_CONTAINER_NAME)).thenReturn(blobContainerClient);
        when(blobContainerClient.exists()).thenReturn(false);

        BlobStorageContainerNotFoundException exception = assertThrows(
            BlobStorageContainerNotFoundException.class,
            () -> blobStoreService.fetchInterfaceFile(
                INTERFACE_FILE_ID, FILESTORE_UUID, BLOB_CONTAINER_NAME));

        assertThat(exception)
            .hasMessage("500 INTERNAL_SERVER_ERROR \"Blob container \"%s\" does not exist\""
                .formatted(BLOB_CONTAINER_NAME));
    }

    @Test
    void fetchInterfaceFile_throwsExceptionWhenBlobDoesNotExist() {
        mockBlobLocation();

        when(blobContainerClient.exists()).thenReturn(true);
        when(blobClient.exists()).thenReturn(false);

        BlobNotFoundException exception = assertThrows(
            BlobNotFoundException.class,
            () -> blobStoreService.fetchInterfaceFile(
                INTERFACE_FILE_ID, FILESTORE_UUID, BLOB_CONTAINER_NAME));

        assertThat(exception).hasMessage(
            "500 INTERNAL_SERVER_ERROR \"Expected interface file id: %d to exist in blobstore container: "
                + "\"%s\" with name \"%s\" but this could not be located.\"",
            INTERFACE_FILE_ID, BLOB_CONTAINER_NAME, BLOB_NAME);
        verify(blobClient, never()).downloadContent();
    }

    @Test
    void getBlobContainerClient_returnsContainerWhenItExists() {
        when(blobServiceClient.getBlobContainerClient(BLOB_CONTAINER_NAME)).thenReturn(blobContainerClient);
        when(blobContainerClient.exists()).thenReturn(true);

        BlobContainerClient result = blobStoreService.getBlobContainerClient(BLOB_CONTAINER_NAME);

        assertThat(result).isSameAs(blobContainerClient);
    }

    @Test
    void getBlobContainerClient_throwsExceptionWhenContainerDoesNotExist() {
        when(blobServiceClient.getBlobContainerClient(BLOB_CONTAINER_NAME)).thenReturn(blobContainerClient);
        when(blobContainerClient.exists()).thenReturn(false);

        BlobStorageContainerNotFoundException exception = assertThrows(
            BlobStorageContainerNotFoundException.class,
            () -> blobStoreService.getBlobContainerClient(BLOB_CONTAINER_NAME));

        assertThat(exception)
            .hasMessage("500 INTERNAL_SERVER_ERROR \"Blob container \"%s\" does not exist\""
                .formatted(BLOB_CONTAINER_NAME));
    }

    @Test
    void getBlobClient_returnsBlobWhenItExists() {
        when(blobContainerClient.getBlobClient(BLOB_NAME)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);

        BlobClient result = blobStoreService.getBlobClient(blobContainerClient, BLOB_NAME);

        assertThat(result).isSameAs(blobClient);
    }

    @Test
    void getBlobClient_returnsNullWhenBlobDoesNotExist() {
        when(blobContainerClient.getBlobClient(BLOB_NAME)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        BlobClient result = blobStoreService.getBlobClient(blobContainerClient, BLOB_NAME);

        assertThat(result).isNull();
    }

    @Test
    void getFileContents_returnsDownloadedFile() {
        when(blobClient.downloadContent()).thenReturn(downloadedFileContents);

        BinaryData result = blobStoreService.getFileContents(blobClient);

        assertThat(result).isSameAs(downloadedFileContents);
    }

    private void mockExistingBlob() {
        mockBlobLocation();

        when(blobContainerClient.exists()).thenReturn(true);
        when(blobClient.exists()).thenReturn(true);
    }

    private void mockBlobLocation() {
        when(blobServiceClient.getBlobContainerClient(BLOB_CONTAINER_NAME)).thenReturn(blobContainerClient);
        when(blobContainerClient.getBlobClient(BLOB_NAME)).thenReturn(blobClient);
    }

}

package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import uk.gov.hmcts.opal.filehandler.exception.BlobUploadException;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;

@ExtendWith(MockitoExtension.class)
class InterfaceFileBlobStoreServiceTest {

    private static final String CONTAINER_NAME = "caps-report";
    private static final String CHECKSUM = "1fa7130130167122bb83decf6cb3bdb1";
    private static final String DIFFERENT_CHECKSUM = "00000000000000000000000000000000";
    private static final byte[] FILE_CONTENT = "CAPS report".getBytes(StandardCharsets.UTF_8);
    private static final UUID FILE_UUID =  UUID.randomUUID();

    @Mock
    private BlobServiceClient blobServiceClient;

    @Mock
    private BlobContainerClient blobContainerClient;

    @Mock
    private BlobClient blobClient;

    @Mock
    private BlobProperties blobProperties;

    @InjectMocks
    private InterfaceFileBlobStoreService service;

    private InputStream stream;

    @BeforeEach
    void setUp() {
        when(blobServiceClient.getBlobContainerClient(CONTAINER_NAME)).thenReturn(blobContainerClient);
        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);

        stream = new ByteArrayInputStream(FILE_CONTENT);
    }

    @Test
    void uploadStoresOriginalFileWithMd5AndReturnsUuid() {
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getContentMd5()).thenReturn(HexFormat.of().parseHex(CHECKSUM));

        service.uploadBaisFile(FILE_UUID, CONTAINER_NAME, stream, CHECKSUM);

        verify(blobContainerClient).getBlobClient(FILE_UUID.toString());
        verify(blobClient).upload(stream);
        verify(blobClient, never()).deleteIfExists();
    }

    @Test
    void uploadDeletesBlobAndThrowsTypedExceptionWhenChecksumDiffers() {
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getContentMd5()).thenReturn(HexFormat.of().parseHex(DIFFERENT_CHECKSUM));

        BlobChecksumValidationException exception = assertThrows(
            BlobChecksumValidationException.class,
            () -> service.uploadBaisFile(FILE_UUID, CONTAINER_NAME, stream, CHECKSUM));

        assertThat(exception.getFilestoreUuid()).isNotNull();
        assertThat(exception.getExpectedChecksum()).isEqualTo(CHECKSUM);
        assertThat(exception.getActualChecksum()).isEqualTo(DIFFERENT_CHECKSUM);
        assertThat(exception)
            .hasMessage("Blob checksum validation failed for filestore UUID '%s': expected '%s' but was '%s'"
                .formatted(exception.getFilestoreUuid(), CHECKSUM, DIFFERENT_CHECKSUM));
        verify(blobClient).deleteIfExists();
    }

    @Test
    void uploadDeletesPartialBlobAndPropagatesUploadFailure() {
        BlobUploadException uploadFailure = new BlobUploadException(
            FILE_UUID, CONTAINER_NAME, new RuntimeException(
                String.format("Blob upload failed for filestore UUID '%s': failed", FILE_UUID)));

        doThrow(uploadFailure).when(blobClient)
            .upload(any(InputStream.class));

        BlobUploadException exception = assertThrows(BlobUploadException.class, () ->
            service.uploadBaisFile(FILE_UUID, CONTAINER_NAME, stream, CHECKSUM));

        assertThat(exception)
            .hasMessage("Blob upload failed for filestore UUID '%s': failed", FILE_UUID);

        verify(blobClient).deleteIfExists();
    }

}

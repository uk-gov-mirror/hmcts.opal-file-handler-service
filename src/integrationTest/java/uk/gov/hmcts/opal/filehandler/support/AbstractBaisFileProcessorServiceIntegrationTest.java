package uk.gov.hmcts.opal.filehandler.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobProperties;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.DigestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;

@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "launchdarkly.default-flag-values.release-1c-banking-interfaces=true"
})
@Testcontainers
public class AbstractBaisFileProcessorServiceIntegrationTest extends AbstractIntegrationTest{

    @Autowired
    protected InterfaceFilesRepository repository;

    @Autowired
    protected BaisSftpClient sftpClient;

    @Autowired
    protected BlobServiceClient blobServiceClient;

    @BeforeEach
    public void setUp() {
        repository.deleteAll();
    }

    public final void uploadResourceToSftp(String resourcePath, String containerPath) {
        TestContainerConfig.SFTP_CONTAINER.copyFileToContainer(
            MountableFile.forClasspathResource(resourcePath), containerPath);
    }

    public final void expectedNumberOfSftpFiles(String username, int expected) {
        List<String> sftpFiles = sftpClient.listRegularFiles(username);

        if (sftpFiles.size() != expected) {
            throw new AssertionError("Expected " + expected + " sftp files but found " + sftpFiles.size());
        }
    }

    public final void lastInterfaceEntityIsSuccess(String fileName, String checksum) {
        List<InterfaceFileEntity> entities = repository.findAllByFileNameAndChecksumAndStatus(
            fileName, checksum, Status.SUCCESS);

        assertThat(entities).hasSize(1);

        InterfaceFileEntity entity = entities.getFirst();

        assertThat(entity.getFileName()).isEqualTo(fileName);
        assertThat(entity.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(entity.getChecksum()).isEqualTo(checksum);
        assertThat(entity.getType()).isEqualTo(Type.SOURCE);
        assertThat(entity.getOpalDomain()).isEqualTo(Domain.MAINTENANCE);
        assertThat(entity.getSource()).isEqualTo(Interface.CAPS_REPORT);
        assertThat(entity.getTarget()).isEqualTo(Interface.OPAL);

    }

    public final void compareBlobChecksum(String fileName, String fileChecksum, String containerName) {
        List<InterfaceFileEntity> entities = repository.findAllByFileNameAndChecksumAndStatus(
            fileName, fileChecksum, Status.SUCCESS);

        assertThat(entities).hasSize(1);

        InterfaceFileEntity entity = entities.getFirst();

        BlobClient client = blobServiceClient
            .getBlobContainerClient(containerName)
            .getBlobClient(entity.getFilestoreUuid().toString());

        assertThat(client.exists()).isTrue();

        final byte[] content = client.downloadContent().toBytes();
        BlobProperties properties = client.getProperties();

        assertThat(DigestUtils.md5DigestAsHex(content)).isEqualTo(fileChecksum);
        assertThat(HexFormat.of().formatHex(properties.getContentMd5())).isEqualTo(fileChecksum);
    }

}

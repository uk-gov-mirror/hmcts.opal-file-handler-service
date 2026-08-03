package uk.gov.hmcts.opal.filehandler.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.DigestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.CapsReportBaisFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;

@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "launchdarkly.default-flag-values.release-1c-banking-interfaces=true"
})
@Slf4j
@Testcontainers
public class AbstractBaisFileProcessorServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Clock clock;

    @Autowired
    protected InterfaceFilesRepository repository;

    @Autowired
    protected BaisSftpClient sftpClient;

    @Autowired
    protected BlobServiceClient blobServiceClient;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("opal.file-handler-service.file-store.connection-string",
            TestContainerConfig::azuriteConnectionString);

        ByteArrayOutputStream privateKeyStreamOut;

        try (InputStream privateKeyStream = CapsReportBaisFileProcessorServiceIntegrationTest.class.getClassLoader()
            .getResourceAsStream("bais-emulator/keys/bais-sftp-key")) {

            privateKeyStreamOut = new ByteArrayOutputStream();
            privateKeyStream.transferTo(privateKeyStreamOut);
        }

        String privateKey = privateKeyStreamOut.toString();

        registry.add("opal.file-handler-service.sftp.bais.private-key", () -> privateKey);
    }

    public final void uploadResourceToSftp(String resourcePath, String containerPath) {
        TestContainerConfig.SFTP_CONTAINER.copyFileToContainer(
            MountableFile.forClasspathResource(resourcePath), containerPath);
    }

    public final void assertNumberOfSftpFiles(String username, int expected) {
        assertThat(sftpClient.listRegularFiles(username)).hasSize(expected);
    }

    public final void assertEntitiesWithStatus(String fileName, String checksum, Status status) {
        List<InterfaceFileEntity> entities = repository.findAllByFileNameAndChecksumAndStatus(
            fileName, checksum, status);

        assertThat(entities.size()).isGreaterThan(0);
    }

    public final void assertMostRecentEntityHasStatus(String fileName, String checksum, Status status) {
        List<InterfaceFileEntity> allEntities = repository.findAll(Sort.by(Sort.Direction.ASC, "createdDatetime"));
        assertThat(allEntities.size()).isGreaterThan(0);

        InterfaceFileEntity mostRecent = allEntities.getLast();

        assertThat(mostRecent.getFileName()).isEqualTo(fileName);
        assertThat(mostRecent.getStatus()).isEqualTo(status);
        assertThat(mostRecent.getChecksum()).isEqualTo(checksum);
        assertThat(mostRecent.getType()).isEqualTo(Type.SOURCE);
        assertThat(mostRecent.getOpalDomain()).isEqualTo(Domain.MAINTENANCE);
        assertThat(mostRecent.getSource()).isEqualTo(Interface.CAPS_REPORT);
        assertThat(mostRecent.getTarget()).isEqualTo(Interface.OPAL);

        if (status.equals(Status.SUCCESS)) {
            assertThat(mostRecent.getErrors()).isNull();
        } else {
            assertThat(mostRecent.getErrors()).isNotNull();
        }
    }

    public final void assertBlobChecksum(String fileName, String fileChecksum, String containerName) {
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

    public final InterfaceFileEntity createFailedInterfaceFile(String fileName, String checksum) {
        InterfaceFileEntity entity = InterfaceFileEntity.builder()
            .fileName(fileName)
            .checksum(checksum)
            .type(Type.SOURCE)
            .source(Interface.CAPS_REPORT)
            .target(Interface.OPAL)
            .opalDomain(Domain.MAINTENANCE)
            .createdDatetime(LocalDateTime.now(clock))
            .status(Status.FAILED)
            .errors("{\"message\": \"something went wrong\"}")
            .build();

        return repository.save(entity);
    }

    public final InterfaceFileEntity createSuccessfulInterfaceFile(String fileName, String checksum) {
        InterfaceFileEntity entity = InterfaceFileEntity.builder()
            .fileName(fileName)
            .checksum(checksum)
            .type(Type.SOURCE)
            .source(Interface.CAPS_REPORT)
            .target(Interface.OPAL)
            .opalDomain(Domain.MAINTENANCE)
            .createdDatetime(LocalDateTime.now(clock))
            .status(Status.SUCCESS)
            .build();

        return repository.save(entity);
    }

}

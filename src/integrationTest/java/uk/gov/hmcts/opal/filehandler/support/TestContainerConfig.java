package uk.gov.hmcts.opal.filehandler.support;

import com.redis.testcontainers.RedisContainer;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TestContainerConfig {

    private static final String DEFAULT_POSTGRES_IMAGE = "postgres:17.5";
    private static final String DEFAULT_AZURITE_IMAGE = "mcr.microsoft.com/azure-storage/azurite:latest";
    private static final String POSTGRES_IMAGE =
        System.getenv().getOrDefault("OPAL_POSTGRES_IMAGE", DEFAULT_POSTGRES_IMAGE);
    public static final PostgreSQLContainer POSTGRES_CONTAINER;
    public static final RedisContainer REDIS_CONTAINER;
    public static final GenericContainer<?> SFTP_CONTAINER;
    public static final GenericContainer<?> AZURITE_CONTAINER;
    private static final int AZURITE_BLOB_PORT = 10000;
    private static final int EXECUTABLE_FILE_MODE = 0755;
    private static final String AZURITE_ACCOUNT_NAME = "devstoreaccount1";
    // This is the default account key for azurite, used exclusively for local dev on our docker images. It is not
    // a secret key, therefore safe to store here as config.
    private static final String AZURITE_ACCOUNT_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private static final List<String> SFTP_USERS = List.of("CAPS-report");

    static {
        POSTGRES_CONTAINER = new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres -c max_connections=200 -c log_connections=on -c log_disconnections=on");

        // Uncomment the following to enable connection to the Test Containers DB whilst debugging.
        // POSTGRES_CONTAINER.setPortBindings(List.of("5432:5432"));

        POSTGRES_CONTAINER.start();

        REDIS_CONTAINER = new RedisContainer(DockerImageName.parse("redis:6.2.6"))
            .withExposedPorts(6379);
        REDIS_CONTAINER.start();

        // Jenkins runs the tests in a container, so its classpath cannot be bind-mounted by the host Docker daemon.
        // Copy only the public key to keep the chroot root-owned; file operations use the user-owned upload directory.

        GenericContainer<?> sftpContainerBuilder = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
            .withExposedPorts(22)
            .withCopyToContainer(MountableFile.forClasspathResource(
                "bais-emulator/configure-sftp", EXECUTABLE_FILE_MODE), "/etc/sftp.d/configure-sftp");

        var uid = 1001;
        for (String username :  SFTP_USERS) {
            sftpContainerBuilder = sftpContainerBuilder
                .withCommand(String.format("%s::%d", username, uid))
                .withCopyToContainer(MountableFile.forClasspathResource(
                    "bais-emulator/keys/bais-sftp-key.pub"),
                    String.format("/home/%s/.ssh/keys/bais-sftp-key.pub", username));

            uid++;
        }

        sftpContainerBuilder.setPortBindings(List.of("2222:22"));

        SFTP_CONTAINER = sftpContainerBuilder;
        SFTP_CONTAINER.start();

        AZURITE_CONTAINER = new GenericContainer<>(DockerImageName.parse(DEFAULT_AZURITE_IMAGE))
            .withCommand(
                "azurite-blob --blobHost 0.0.0.0 --blobPort " + AZURITE_BLOB_PORT + " --skipApiVersionCheck")
            .withExposedPorts(AZURITE_BLOB_PORT);
        AZURITE_CONTAINER.start();
    }

    public static String azuriteConnectionString() {
        return "DefaultEndpointsProtocol=http;"
            + "AccountName=" + AZURITE_ACCOUNT_NAME + ";"
            + "AccountKey=" + AZURITE_ACCOUNT_KEY + ";"
            + "BlobEndpoint=http://127.0.0.1:" + AZURITE_CONTAINER.getMappedPort(AZURITE_BLOB_PORT)
            + "/" + AZURITE_ACCOUNT_NAME + ";";
    }
}

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
    private static final String AZURITE_ACCOUNT_NAME = "devstoreaccount1";
    // This is the default account key for azurite, used exclusively for local dev on our docker images. It is not
    // a secret key, therefore safe to store here as config.
    private static final String AZURITE_ACCOUNT_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

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

        SFTP_CONTAINER = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:latest"))
            .withExposedPorts(22)
            .withClasspathResourceMapping(
                "bais-emulator/data/",
                "/home",
                BindMode.READ_WRITE)
            .withCommand("BTEckoh-report::1001", "CAPS-report::1002");
        SFTP_CONTAINER.setPortBindings(List.of("2222:22"));
        SFTP_CONTAINER.start();

        AZURITE_CONTAINER = new GenericContainer<>(DockerImageName.parse(DEFAULT_AZURITE_IMAGE))
            .withCommand(
                "azurite-blob --blobHost 0.0.0.0 --blobPort " + AZURITE_BLOB_PORT + " --skipApiVersionCheck")
            .withClasspathResourceMapping(
                "azurite-data/",
                "/data",
                BindMode.READ_WRITE)
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

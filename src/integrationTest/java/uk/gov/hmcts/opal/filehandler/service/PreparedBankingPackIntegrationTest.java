package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.opal.filehandler.config.AbstractBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.BusinessUnitBankAccountEntity;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.repository.BusinessUnitBankAccountRepository;
import uk.gov.hmcts.opal.filehandler.service.queue.FinesInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.service.queue.InterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.service.queue.MaintenanceInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.support.AbstractInterfaceFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.support.TestContainerConfig;
import uk.hmcts.zephyr.automation.junit5.annotations.JiraEpic;
import uk.hmcts.zephyr.automation.junit5.annotations.JiraStory;

/** Processes the persistent DWP/Bailiff fixtures; APR references are asserted before downstream resolution. */
@TestPropertySource(properties = {
    "launchdarkly.enabled=false",
    "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
    "launchdarkly.default-flag-values.dwp-file-transfer-job=true",
    "launchdarkly.default-flag-values.bailiffs-jacobs-file-transfer-job=true",
    "launchdarkly.default-flag-values.bailiffs-cder-file-transfer-job=true",
    "launchdarkly.default-flag-values.bailiffs-marston-file-transfer-job=true"
})
public class PreparedBankingPackIntegrationTest extends AbstractInterfaceFileProcessorServiceIntegrationTest {
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private BusinessUnitBankAccountRepository bankAccounts;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private FinesInterfaceFilePreprocessQueueService finesQueue;
    @MockitoBean
    private MaintenanceInterfaceFilePreprocessQueueService maintenanceQueue;
    private PackCase active;
    private AbstractBaisFileProcessorConfiguration config;
    private String previousUser;
    private String name;
    private boolean mappingOwned;
    private boolean fileOwned;

    static Stream<PackCase> packCases() {
        return Stream.of(
            new Supplier("dwp", "DWP", "dwpBaisFileProcessorConfig", "DWP", (short) 7240, Domain.MAINTENANCE),
            new Supplier("jacobs", "jacobs", "JacobsBaisFileProcessorConfig", "Jacobs", (short) 7241, Domain.FINES),
            new Supplier("cder", "cder", "cderBaisFileProcessorConfig", "CDER", (short) 7242, Domain.FINES),
            new Supplier("marston", "marston", "marstonBaisFileBaisFileProcessorConfig", "MARSTON",
                (short) 7243, Domain.FINES)
        ).flatMap(supplier -> Stream.of("valid-payment", "duplicate-replay", "missing-court-mapping",
            "malformed-xml", "apr-positive", "apr-unknown").map(testCase -> new PackCase(supplier, testCase)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("packCases")
    @JiraStory("PO-7244")
    @JiraEpic("PO-3497")
    void processesPreparedInput(PackCase packCase) throws Exception {
        byte[] input = prepare(packCase);
        processor().run();
        if (active.testCase().equals("missing-court-mapping") || active.testCase().equals("malformed-xml")) {
            assertFailedSource();
        } else {
            assertSuccessfulExtract(input);
        }
    }

    private byte[] prepare(PackCase packCase) throws IOException {
        active = packCase;
        Supplier supplier = active.supplier();
        config = applicationContext.getBean(supplier.configBean(), AbstractBaisFileProcessorConfiguration.class);
        previousUser = config.getSftpUsername();
        config.setSftpUsername(supplier.user());
        name = active.fileName();
        final byte[] input = new ClassPathResource("bais-emulator/po7244/" + supplier.folder()
            + "/" + active.testCase() + "/" + name).getContentAsByteArray();
        assertThat(records()).isEmpty();
        assertThat(sftpClient.listRegularFiles(supplier.user())).doesNotContain(name);
        fileOwned = true;
        assertThat(bankAccounts.existsById(supplier.id())).isFalse();
        bankAccounts.saveAndFlush(BusinessUnitBankAccountEntity.builder().id(supplier.id())
            .businessUnitCode("ED" + (supplier.id() - 7200)).domain(supplier.domain())
            .bankSortCode("7244" + (supplier.id() - 7200)).bankAccountNumber("724400" + (supplier.id() - 7200))
            .dwpCourtCode("72440000" + (supplier.id() - 7200)).build());
        mappingOwned = true;
        blobServiceClient.createBlobContainerIfNotExists(config.getContainerName());
        upload(input);
        return input;
    }

    private void assertFailedSource() {
        assertThat(records()).singleElement().satisfies(source -> {
            assertThat(source.getType()).isEqualTo(Type.SOURCE);
            assertThat(source.getStatus()).isEqualTo(Status.FAILED);
            assertThat(source.getErrors()).isNotBlank();
            if (active.testCase().equals("missing-court-mapping")) {
                assertThat(source.getErrors()).contains(name.substring(0, 10), "could not be located");
            } else {
                assertThat(source.getErrors()).contains("was not valid");
            }
            assertThat(blobServiceClient.getBlobContainerClient(config.getContainerName())
                .getBlobClient(source.getFilestoreUuid().toString()).exists()).isTrue();
        });
        assertThat(sftpClient.listRegularFiles(active.supplier().user())).contains(name);
        verifyNoInteractions(finesQueue, maintenanceQueue);
    }

    private void assertSuccessfulExtract(byte[] input) {
        Supplier supplier = active.supplier();
        assertThat(records()).hasSize(2).allMatch(row -> row.getStatus() == Status.SUCCESS);
        InterfaceFileEntity json = records().stream().filter(row -> row.getType() == Type.SOURCE_JSON)
            .findFirst().orElseThrow();
        assertThat(json.getBusinessUnitId()).containsExactly(supplier.id());
        assertThat(json.getOpalDomain()).isEqualTo(supplier.domain());
        byte[] extracted = blobServiceClient.getBlobContainerClient(config.getContainerName())
            .getBlobClient(json.getFilestoreUuid().toString()).downloadContent().toBytes();
        var tree = objectMapper.readTree(extracted);
        assertThat(tree.get("file_name").asString()).isEqualTo(name);
        assertThat(tree.get("payment_type").asString()).isEqualTo("CASH");
        assertThat(tree.get("transactions").size()).isEqualTo(2);
        assertThat(tree.at("/destination_details/bank_details/sort_code").asString())
            .isEqualTo("7244" + (supplier.id() - 7200));
        assertThat(tree.at("/destination_details/bank_details/account_number").asString())
            .isEqualTo("724400" + (supplier.id() - 7200));
        assertThat(tree.at("/transactions/0/amount").asInt()).isEqualTo(1);
        assertThat(tree.at("/transactions/1/amount").asInt()).isEqualTo(1);
        assertThat(tree.at("/transactions/0/date_entry_applied").asString()).isEqualTo("04/11/2026");
        assertThat(tree.at("/transactions/1/date_entry_applied").asString()).isEqualTo("03/11/2026");
        assertThat(tree.at("/transactions/0/transaction_code").asString()).isEqualTo("99");
        assertThat(tree.at("/transactions/1/transaction_code").asString()).isEqualTo("99");
        assertThat(tree.at("/transactions/1/originator_details/account_reference").asString())
            .isEqualTo("23000113P");
        String expectedReference = switch (active.testCase()) {
            case "apr-positive" -> "ED" + supplier.id() + "APR";
            case "apr-unknown" -> "ED" + supplier.id() + "UNKNOWN";
            default -> "23000106E";
        };
        assertThat(tree.at("/transactions/0/originator_details/account_reference").asString())
            .isEqualTo(expectedReference);
        InterfaceFilePreprocessQueueService expectedQueue = supplier.domain() == Domain.FINES
            ? finesQueue : maintenanceQueue;
        verify(expectedQueue).send(json.getInterfaceFileId());
        verifyNoMoreInteractions(finesQueue, maintenanceQueue);
        assertThat(sftpClient.listRegularFiles(supplier.user())).doesNotContain(name);
        if (active.testCase().equals("duplicate-replay")) {
            final var before = storedBlobs(config.getContainerName());
            upload(input);
            processor().run();
            assertThat(records()).hasSize(3);
            assertThat(records()).filteredOn(row -> row.getStatus() == Status.DUPLICATE).hasSize(1);
            assertThat(storedBlobs(config.getContainerName())).isEqualTo(before);
            verifyNoMoreInteractions(finesQueue, maintenanceQueue);
            assertThat(sftpClient.listRegularFiles(supplier.user())).doesNotContain(name);
        }
    }

    private InterfaceFileProcessorService processor() {
        return applicationContext.getBean(active.supplier().processor() + "InterfaceFileProcessorService",
            InterfaceFileProcessorService.class);
    }

    private void upload(byte[] bytes) {
        TestContainerConfig.SFTP_CONTAINER.copyFileToContainer(Transferable.of(bytes),
            "/home/" + active.supplier().user() + "/" + name);
    }

    private List<InterfaceFileEntity> records() {
        return repository.findAll().stream().filter(row -> row.getSource() == config.getSource())
            .filter(row -> name.equals(row.getFileName())).toList();
    }

    @AfterEach
    void cleanUpPackCase() {
        try {
            if (fileOwned) {
                sftpClient.deleteFile(active.supplier().user(), name);
                var owned = records();
                owned.forEach(row -> blobServiceClient.getBlobContainerClient(config.getContainerName())
                    .getBlobClient(row.getFilestoreUuid().toString()).deleteIfExists());
                repository.deleteAll(owned.stream().filter(row -> row.getType() != Type.SOURCE).toList());
                repository.deleteAll(owned.stream().filter(row -> row.getType() == Type.SOURCE).toList());
                owned.forEach(row -> assertThat(blobServiceClient.getBlobContainerClient(config.getContainerName())
                    .getBlobClient(row.getFilestoreUuid().toString()).exists()).isFalse());
                assertThat(records()).isEmpty();
                assertThat(sftpClient.listRegularFiles(active.supplier().user())).doesNotContain(name);
            }
        } finally {
            if (config != null) {
                config.setSftpUsername(previousUser);
            }
            if (mappingOwned) {
                bankAccounts.deleteById(active.supplier().id());
                assertThat(bankAccounts.existsById(active.supplier().id())).isFalse();
            }
        }
    }

    record Supplier(String folder, String processor, String configBean, String user, short id, Domain domain) { }

    record PackCase(Supplier supplier, String testCase) {
        String fileName() {
            int sequence = switch (testCase) {
                case "valid-payment", "duplicate-replay" -> 1;
                case "missing-court-mapping" -> 3;
                case "malformed-xml" -> 4;
                case "apr-positive" -> 5;
                case "apr-unknown" -> 6;
                default -> throw new IllegalArgumentException("Unknown pack case: " + testCase);
            };
            int offset = supplier.id() - 7240;
            String court = testCase.equals("missing-court-mapping")
                ? "7244999%03d".formatted(offset) : "72440000" + (supplier.id() - 7200);
            return "%s_dat_%04d%06d_20261105_12%02d%02d.txt"
                .formatted(court, supplier.id(), sequence, offset, sequence);
        }

        @Override
        public String toString() {
            return supplier.folder() + ": " + testCase
                + (testCase.startsWith("apr-") ? " (reference extraction only)" : "");
        }
    }
}

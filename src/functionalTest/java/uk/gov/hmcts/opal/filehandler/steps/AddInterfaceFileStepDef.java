package uk.gov.hmcts.opal.filehandler.steps;

import static net.serenitybdd.rest.SerenityRest.lastResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.io.Resources;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import uk.gov.hmcts.opal.filehandler.blob.BlobStorageClient;
import uk.gov.hmcts.opal.filehandler.db.DatabaseClient;
import uk.gov.hmcts.opal.filehandler.db.InterfaceFileTestDatabaseClient;
import uk.gov.hmcts.opal.filehandler.db.InterfaceFileTestDatabaseClient.InterfaceFileRecord;

/**
 * Exercises multipart interface-file uploads using the existing auth, database and blob clients.
 */
public class AddInterfaceFileStepDef extends BaseStepDef {

    private static final String WORKBOOK = "test-data/bteckoh-report/bteckoh-test-file.xlsx";
    private String fileName;
    private byte[] content;
    private long originalId;
    private String originalBlob;

    @Before("@AddInterfaceFileFixture")
    public void setUpUpload() throws IOException {
        fileName = "po-6453-" + UUID.randomUUID() + ".xlsx";
        content = Resources.toByteArray(Resources.getResource(WORKBOOK));
    }

    @After("@AddInterfaceFileFixture")
    public void cleanUpUpload() {
        if (fileName == null) {
            return;
        }
        // Only this scenario's UUID filename and its blobs are owned by this fixture.
        try (InterfaceFileTestDatabaseClient database = new InterfaceFileTestDatabaseClient()) {
            BlobStorageClient blobs = new BlobStorageClient();
            for (String ownedName : List.of(fileName, "metadata-" + fileName)) {
                database.findByFileName(ownedName).stream()
                    .map(InterfaceFileRecord::filestoreUuid)
                    .filter(uuid -> uuid != null)
                    .distinct()
                    .map(UUID::toString)
                    .forEach(blobs::deleteIfExists);
                database.deleteByFileName(ownedName);
            }
        }
    }

    @When("I upload the BTEckoh interface file")
    public void uploadWorkbook() {
        upload(BearerTokenStepDef.getToken(), fileName, "FINES");
    }

    @When("I upload the BTEckoh interface file for domain {string}")
    public void uploadWorkbookForDomain(String domain) {
        upload(BearerTokenStepDef.getToken(), fileName, domain);
    }

    @When("I upload the BTEckoh interface file without a token")
    public void uploadWithoutToken() {
        upload(null, fileName, "FINES");
    }

    @When("I upload the BTEckoh interface file with a different metadata filename")
    public void uploadWithDifferentMetadataFilename() {
        upload(BearerTokenStepDef.getToken(), "metadata-" + fileName, "FINES");
    }

    @When("I upload invalid JSON as a BTEckoh interface file")
    public void uploadInvalidJson() {
        content = "{}".getBytes(StandardCharsets.UTF_8);
        uploadWorkbook();
    }

    @Given("an ingested upload exists for this scenario")
    public void ingestedUploadExists() {
        uploadWorkbook();
        assertEquals(201, lastResponse().statusCode());
        uploadedFileIsPersisted("SUCCESS");
        uploadedBlobMatchesWorkbook();
        originalId = lastResponse().jsonPath().getLong("interface_file_id");
        originalBlob = lastResponse().jsonPath().getString("filestore_uuid");
    }

    @Given("a successfully processed upload exists for this scenario")
    public void successfulUploadExists() {
        ingestedUploadExists();
        // Explicit duplicate-lookup precondition; this does not test downstream processing.
        try (DatabaseClient database = new DatabaseClient()) {
            assertEquals(1, database.update(
                "UPDATE public.interface_files SET status = 'SUCCESS' WHERE interface_file_id = ? AND file_name = ?",
                originalId, fileName));
        }
    }

    @Then("the uploaded interface file is persisted with status {string}")
    public void uploadedFileIsPersisted(String status) {
        long id = lastResponse().jsonPath().getLong("interface_file_id");
        assertTrue(id > 0, "Expected an allocated interface-file ID");
        assertEquals(status, lastResponse().jsonPath().getString("status"));
        assertEquals(fileName, lastResponse().jsonPath().getString("file_name"));
        assertEquals(DigestUtils.md5Hex(content), lastResponse().jsonPath().getString("checksum"));
        assertNotNull(lastResponse().jsonPath().getString("created_datetime"));
        try (InterfaceFileTestDatabaseClient database = new InterfaceFileTestDatabaseClient()) {
            List<InterfaceFileRecord> records = database.findByFileName(fileName);
            assertEquals(originalId == 0 ? 1 : 2, records.size(), "Unexpected number of upload records");
            InterfaceFileRecord record = records.stream()
                .filter(row -> row.id() == id).findFirst().orElseThrow();
            assertEquals(status, record.status());
            assertEquals("BTECKOH_REPORT", record.source());
            assertEquals("OPAL", record.target());
            assertEquals("SOURCE", record.type());
            assertEquals("FINES", record.domain());
            assertEquals(DigestUtils.md5Hex(content), record.checksum());
            assertEquals(expectedHumanUserId(), record.createdBy(),
                "The uploaded interface file should be attributed to the human test user");
            assertEquals(record.filestoreUuid() == null ? null : record.filestoreUuid().toString(),
                lastResponse().jsonPath().getString("filestore_uuid"));
        }
    }

    @Then("the uploaded blob matches the BTEckoh workbook")
    public void uploadedBlobMatchesWorkbook() {
        String blob = lastResponse().jsonPath().getString("filestore_uuid");
        assertNotNull(blob, "Expected a stored blob reference");
        assertTrue(new BlobStorageClient().contentMatchesResource(blob, WORKBOOK));
    }

    @Then("the duplicate references the original file and reuses its blob")
    public void duplicateReusesOriginalBlob() {
        assertNotEquals(originalId, lastResponse().jsonPath().getLong("interface_file_id"));
        assertEquals(originalBlob, lastResponse().jsonPath().getString("filestore_uuid"));
        assertTrue(lastResponse().jsonPath().getString("errors").contains("is a duplicate of " + originalId));
        assertTwoRecords("SUCCESS", "DUPLICATE");
        uploadedBlobMatchesWorkbook();
    }

    @Then("the repeat upload has a different record and blob")
    public void repeatHasSeparateBlob() {
        assertNotEquals(originalId, lastResponse().jsonPath().getLong("interface_file_id"));
        assertNotEquals(originalBlob, lastResponse().jsonPath().getString("filestore_uuid"));
        assertTwoRecords("SUCCESS", "SUCCESS");
        uploadedBlobMatchesWorkbook();
        assertTrue(new BlobStorageClient().contentMatchesResource(originalBlob, WORKBOOK));
    }

    @Then("the rejected upload has validation errors and no blob reference")
    public void invalidUploadHasNoBlob() {
        String errors = lastResponse().jsonPath().getString("errors");
        assertTrue(errors != null && errors.contains("BTEckoh report was not a valid XLSX workbook"),
            "Expected the BTEckoh XLSX validation error");
        assertNull(lastResponse().jsonPath().getString("filestore_uuid"));
    }

    @Then("no interface file is persisted for this upload")
    public void noUploadIsPersisted() {
        try (InterfaceFileTestDatabaseClient database = new InterfaceFileTestDatabaseClient()) {
            assertTrue(database.findByFileName(fileName).isEmpty());
        }
    }

    private void assertTwoRecords(String originalStatus, String latestStatus) {
        try (InterfaceFileTestDatabaseClient database = new InterfaceFileTestDatabaseClient()) {
            List<InterfaceFileRecord> records = database.findByFileName(fileName);
            assertEquals(2, records.size());
            assertEquals(originalStatus, records.stream().filter(row -> row.id() == originalId)
                .findFirst().orElseThrow().status());
            assertEquals(latestStatus, records.stream().filter(row -> row.id() != originalId)
                .findFirst().orElseThrow().status());
        }
    }

    private void upload(String token, String metadataFilename, String domain) {
        String metadata = """
            {"source":"BTECKOH_REPORT","target":"OPAL","type":"SOURCE","domain":"%s",
             "file_name":"%s","payment_type":"CASH","should_pre_process_file":false}
            """.formatted(domain, metadataFilename);
        jsonRequestWithOptionalToken(token)
            .contentType("multipart/form-data")
            .multiPart("file", fileName, content, "application/octet-stream")
            .multiPart("metadata", "metadata.json", metadata.getBytes(StandardCharsets.UTF_8), "application/json")
            .when().post(getTestUrl() + "/interface-files");
    }

    private long expectedHumanUserId() {
        return switch (scenarioContext().getCurrentUserOrDefault(BearerTokenStepDef.DEFAULT_USER)) {
            case "opal-test-3@dev.platform.hmcts.net" -> 500000002L;
            case "opal-test@dev.platform.hmcts.net" -> 500000000L;
            default -> throw new IllegalStateException("No functional-test user ID configured for the current user");
        };
    }
}

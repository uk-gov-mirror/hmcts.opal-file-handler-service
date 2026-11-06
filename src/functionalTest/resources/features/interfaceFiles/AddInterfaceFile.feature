@Opal @JIRA-LABEL:file-handler-service @JIRA-STORY:PO-6453 @JIRA-EPIC:PO-3497 @AddInterfaceFileFixture
Feature: Add Interface File

  # AC2/AC4: 201 matches the OpenAPI contract and developer clarification; the ticket E2E text says 200.
  # Requires the banking-interface feature, user service, database and BTEckoh blob container.
  Background:
    Given I am testing as the "opal-test@dev.platform.hmcts.net" user

  @AC2
  Scenario: E2E.01 - Upload a valid interface file to the database and blob store
    When I upload the BTEckoh interface file
    Then the response status code is 201
    And the uploaded interface file is persisted with status "SUCCESS"
    And the uploaded blob matches the BTEckoh workbook
    And the response is as expected:
      | source | BTECKOH_REPORT |
      | target | OPAL           |
      | type   | SOURCE         |
      | domain | FINES          |
      | errors | null           |

  @AC4
  Scenario: E2E.03 - Record a duplicate of a successfully processed interface file
    Given a successfully processed upload exists for this scenario
    When I upload the BTEckoh interface file
    Then the response status code is 201
    And the uploaded interface file is persisted with status "DUPLICATE"
    And the duplicate references the original file and reuses its blob

  @AC4
  Scenario: Repeating an ingested file creates a separate upload
    Given an ingested upload exists for this scenario
    When I upload the BTEckoh interface file
    Then the response status code is 201
    And the uploaded interface file is persisted with status "INGESTED"
    And the repeat upload has a different record and blob

  @AC2
  Scenario: Invalid BTEckoh content is recorded as failed without a blob
    When I upload invalid JSON as a BTEckoh interface file
    Then the response status code is 201
    And the uploaded interface file is persisted with status "FAILED"
    And the rejected upload has validation errors and no blob reference

  @AC2
  Scenario: A different metadata filename does not prevent upload
    When I upload the BTEckoh interface file with a different metadata filename
    Then the response status code is 201
    And the uploaded interface file is persisted with status "INGESTED"
    And the uploaded blob matches the BTEckoh workbook

  @AC2
  Scenario: Reject an upload without a bearer token
    When I upload the BTEckoh interface file without a token
    Then the response status code is 401
    And no interface file is persisted for this upload

  @AC2
  Scenario: Reject an upload by a user without interface file permission
    Given I am testing as the "opal-test-2@dev.platform.hmcts.net" user
    When I upload the BTEckoh interface file
    Then the response status code is 403
    And no interface file is persisted for this upload

@Opal @JIRA-STORY:PO-8686 @JIRA-EPIC:PO-3952 @JIRA-LABEL:file-handler-service
@InterfaceFileDbFixture @InterfaceFileContentBlobFixture @AddInterfaceFileFixture
Feature: Human authentication and domain verification for Interface File API

  # The interface-file fixture contains one Fines file, one Maintenance file and one unassigned
  # file in the 2026-10-01 time window. opal-test-3 has File-Handler permissions in Fines only;
  # opal-test-2 has no business-unit permissions.
  Background:
    Given I am testing as the "opal-test@dev.platform.hmcts.net" user

  @AC2
  Scenario: E2E.01 - a permitted human user receives all visible interface files
    When I request interface files from date "2026-10-01T00:00" to date "2026-10-01T23:59:59"
    Then the response status code is 200
    And exactly 3 interface files are returned
    And the returned interface file IDs are "9000000000000014,9000000000000015,9000000000000016"

  @AC3
  Scenario: E2E.02 - a human user without View Interface Files is forbidden
    Given I am testing as the "opal-test-2@dev.platform.hmcts.net" user
    When I request all interface files
    Then the response status code is 403
    And the response is a forbidden problem detail

  @AC5
  Scenario: E2E.03 - a permitted human user receives interface file content
    When I request the content for interface file 9000000000000014
    Then the response status code is 200
    And the interface file content type is application octet stream

  @AC6
  Scenario: E2E.04 - interface file content is forbidden without View Interface Files
    Given I am testing as the "opal-test-2@dev.platform.hmcts.net" user
    When I request the content for interface file 9000000000000014
    Then the response status code is 403
    And the response is a forbidden problem detail

  @AC8
  Scenario: E2E.05 - a permitted human user can add an interface file
    When I upload the BTEckoh interface file
    Then the response status code is 201
    And the uploaded interface file is persisted with status "SUCCESS"

  @AC8 @AC18
  Scenario: E2E.06 - a permitted human user can add an interface file for Fines
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I upload the BTEckoh interface file for domain "FINES"
    Then the response status code is 201
    And the uploaded interface file is persisted with status "SUCCESS"

  @AC9
  Scenario: E2E.07 - adding an interface file is forbidden without Create Interface Files
    Given I am testing as the "opal-test-2@dev.platform.hmcts.net" user
    When I upload the BTEckoh interface file
    Then the response status code is 403
    And the response is a forbidden problem detail

  @AC10 @AC12
  Scenario: E2E.08 - a human user sees only files in permitted domains and unassigned files
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I request interface files from date "2026-10-01T00:00" to date "2026-10-01T23:59:59"
    Then the response status code is 200
    And exactly 2 interface files are returned
    And the returned interface file IDs are "9000000000000014,9000000000000016"

  @AC11 @AC13
  Scenario: E2E.09 - a human user with one domain permission also sees unassigned files
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I request interface files from date "2026-10-01T12:00" to date "2026-10-01T12:00"
    Then the response status code is 200
    And exactly 1 interface files are returned
    And the returned interface file IDs are "9000000000000016"

  @AC14
  Scenario: E2E.10 - file content is available for a permitted domain
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I request the content for interface file 9000000000000014
    Then the response status code is 200
    And the interface file content type is application octet stream

  @AC15
  Scenario: E2E.11 - file content is available for an unassigned file with one domain permission
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I request the content for interface file 9000000000000016
    Then the response status code is 200
    And the interface file content type is application octet stream

  @AC16
  Scenario: E2E.12 - a file and its content are forbidden outside the permitted domain
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I call GET "/interface-files/9000000000000015"
    Then the response status code is 403
    And the response is a forbidden problem detail

  @AC16
  Scenario: E2E.12.02 - content is forbidden outside the permitted domain
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I request the content for interface file 9000000000000015
    Then the response status code is 403
    And the response is a forbidden problem detail

  @AC17
  Scenario: E2E.13 - an unassigned file is forbidden without any domain permission
    Given I am testing as the "opal-test-2@dev.platform.hmcts.net" user
    When I call GET "/interface-files/9000000000000016"
    Then the response status code is 403
    And the response is a forbidden problem detail

  @AC18
  Scenario: A human user cannot add an interface file for an unpermitted domain
    Given I am testing as the "opal-test-3@dev.platform.hmcts.net" user
    When I upload the BTEckoh interface file for domain "MAINTENANCE"
    Then the response status code is 403
    And the response is a forbidden problem detail

  Scenario: E2E.14 - an interface-file request without a bearer token returns a problem detail
    When I call GET "/interface-files" without a token
    Then the response status code is 401
    And the response is as expected:
      | detail    | You are not authorized to access this resource |
      | type      | https://hmcts.gov.uk/problems/unauthorized    |
      | title     | Unauthorized                                  |
      | status    | 401                                           |

@Opal @JIRA-LABEL:file-handler-service @InterfaceFileDbFixture
Feature: Get Interface Files

  Background:
    Given I am testing as the "opal-test@dev.platform.hmcts.net" user

  @JIRA-STORY:PO-3947 @JIRA-STORY:PO-8669 @JIRA-EPIC:PO-3495
  Scenario: Returns interface files correctly
    When I request all interface files
    Then the response status code is 200
    And at least 4 interface files are returned
    And the interface file with filestore UUID "f0000000-0000-0000-0000-000000000003" has details:
      | source           | BTECKOH_REPORT                  |
      | target           | OPAL                            |
      | type             | SOURCE                          |
      | domain           | FINES                           |
      | status           | FAILED                          |
      | file_name        | 2500-Payments-Report-Daily.xlsx |
      | errors           | {"error":"malformed xlsx"}      |
      | created_datetime | 2026-01-04T12:30:00             |
      | checksum         | null                            |

  @JIRA-STORY:PO-8669 @JIRA-EPIC:PO-3495
  Scenario: Returns business unit codes for each interface file
    When I request all interface files
    Then the response status code is 200
    And the interface file with filestore UUID "f0000000-0000-0000-0000-000000000001" has details:
      | business_unit_ids | [1001] |

  @JIRA-STORY:PO-8669 @JIRA-EPIC:PO-3495
  Scenario: Filters interface files by business unit code
    When I request interface files with filters:
      | business_unit_id | 2001 |
    Then the response status code is 200
    And at least 1 interface files are returned
    And every returned interface file has at least one business unit id
    And every returned interface file contains business unit id "2001"

  @JIRA-STORY:PO-8669 @JIRA-EPIC:PO-3495
  Scenario: Filters interface files by multiple types
    When I request interface files with filters:
      | business_unit_id | 2001                         |
      | type               | SOURCE_JSON,TRANSFORMED_JSON |
    Then the response status code is 200
    And at least 1 interface files are returned
    And every returned interface file has a type in "SOURCE_JSON,TRANSFORMED_JSON"
    And every returned interface file contains business unit id "2001"

  @JIRA-STORY:PO-8669 @JIRA-EPIC:PO-3495
  Scenario: Excludes interface files with multiple statuses
    When I request interface files with filters:
      | business_unit_id | 2001                       |
      | not_status         | FAILED,FAILED_SUPERSEDED |
    Then the response status code is 200
    And at least 1 interface files are returned
    And no returned interface file has a status in "FAILED,FAILED_SUPERSEDED"
    And every returned interface file contains business unit id "2001"

  @JIRA-STORY:PO-8669 @JIRA-EPIC:PO-3495
  Scenario: Excludes interface files with the requested target
    When I request interface files with filters:
      | business_unit_id | 2001          |
      | not_target         | BTECKOH_REPORT |
    Then the response status code is 200
    And at least 1 interface files are returned
    And no returned interface file has target "BTECKOH_REPORT"
    And every returned interface file contains business unit id "2001"

  @JIRA-STORY:PO-8669 @JIRA-EPIC:PO-3495
  Scenario: Applies multiple types and exclusion filters together
    When I request interface files with filters:
      | type               | SOURCE_JSON,TRANSFORMED_JSON |
      | not_status         | FAILED                       |
      | not_target         | BTECKOH_REPORT               |
      | business_unit_id | 2001                         |
    Then the response status code is 200
    And exactly 2 interface files are returned
    And every returned interface file has a type in "SOURCE_JSON,TRANSFORMED_JSON"
    And no returned interface file has a status in "FAILED"
    And no returned interface file has target "BTECKOH_REPORT"
    And every returned interface file contains business unit id "2001"

  @JIRA-STORY:PO-3947 @JIRA-EPIC:PO-3495
  Scenario: Applies all interface file filters together
    When I request interface files with filters:
      | source    | CAPS_REPORT      |
      | target    | OPAL             |
      | type      | SOURCE           |
      | domain    | MAINTENANCE      |
      | status    | SUCCESS         |
      | from_date | 2099-01-01T20:00 |
      | to_date   | 2099-01-01T20:00 |
    Then the response status code is 200
    And exactly 1 interface files are returned
    And every returned interface file has details:
      | source | CAPS_REPORT |
      | target | OPAL        |
      | type   | SOURCE      |
      | domain | MAINTENANCE |
      | status | SUCCESS    |

  @JIRA-STORY:PO-3947 @JIRA-EPIC:PO-3495
  Scenario: Returns interface files in created datetime order
    When I request interface files from date "2099-01-01T10:00" to date "2099-01-02T10:00"
    Then the response status code is 200
    And exactly 3 interface files are returned
    And the returned interface files are ordered by created datetime ascending

  @JIRA-STORY:PO-3947 @JIRA-EPIC:PO-3495
  Scenario: Returns not acceptable for a non-existent status
    When I request interface files with source "CAPS_REPORT" and status "INVALID_STATUS"
    Then the response status code is 406

  @JIRA-STORY:PO-3947 @JIRA-EPIC:PO-3495
  Scenario: Returns an empty response when no interface files match
    When I request interface files from date "2999-01-01T00:00" to date "2999-01-01T00:01"
    Then the response status code is 200
    And exactly 0 interface files are returned

  @JIRA-STORY:PO-3947 @JIRA-EPIC:PO-3495
  Scenario: Results returned includes interface files created on the date boundaries
    When I request interface files from date "2099-01-01T10:00" to date "2099-01-02T10:00"
    Then the response status code is 200
    And exactly 3 interface files are returned
    And the interface file with filestore UUID "f0000000-0000-0000-0000-000000000006" has details:
      | created_datetime | 2099-01-01T10:00:00 |
    And the interface file with filestore UUID "f0000000-0000-0000-0000-000000000008" has details:
      | created_datetime | 2099-01-02T10:00:00 |

  @JIRA-STORY:PO-3947 @JIRA-EPIC:PO-3495
  Scenario Outline: Results returned when using a single-sided date filter
    When I request interface files <direction> date "<boundary>"
    Then the response status code is 200
    And at least 1 interface files are returned
    And every returned interface file was created <comparison> date "<boundary>"
    And the interface file with filestore UUID "<filestore_uuid>" has details:
      | created_datetime | <created_datetime> |

    Examples:
      | direction | comparison   | boundary         | filestore_uuid                       | created_datetime    |
      | from      | on or after  | 2099-01-01T10:00 | f0000000-0000-0000-0000-000000000006 | 2099-01-01T10:00:00 |
      | to        | on or before | 2099-01-02T10:00 | f0000000-0000-0000-0000-000000000008 | 2099-01-02T10:00:00 |

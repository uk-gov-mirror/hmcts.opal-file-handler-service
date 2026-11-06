package uk.gov.hmcts.opal.filehandler.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.xml.bind.UnmarshalException;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.opal.filehandler.generated.pacs.PacsTppSchedule;
import uk.gov.hmcts.opal.filehandler.testutil.StreamTestUtil;

class XmlSchemaUnmarshalServiceTest {

    private static final String PACS_SCHEMA = "xsd/pacs-tpp-schedule-v0.0d.xsd";

    private final XmlSchemaUnmarshalService service = new XmlSchemaUnmarshalService();

    @Test
    void shouldUnmarshalValidXmlUsingSchema() {
        PacsTppSchedule schedule = service.unmarshal(
            StreamTestUtil.resourceStream("/fixtures/pacs-ttp/typical.xml"),
            PacsTppSchedule.class,
            PACS_SCHEMA,
            "PACS TTP file"
        );

        assertThat(schedule.getDocumentHeader().getCreditorID()).isEqualTo("0000031714");
        assertThat(schedule.getDocumentDetail()).hasSize(2);
    }

    @Test
    void shouldRejectInvalidXml() {
        assertThatThrownBy(() -> service.unmarshal(
            StreamTestUtil.stream("<PacsTppSchedule>"),
            PacsTppSchedule.class,
            PACS_SCHEMA,
            "PACS TTP file"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PACS TTP file was not valid");
    }

    @Test
    void shouldRejectUnreadableXmlStream() {
        assertThatThrownBy(() -> service.unmarshal(
            StreamTestUtil.unreadableStream(),
            PacsTppSchedule.class,
            PACS_SCHEMA,
            "PACS TTP file"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasCauseInstanceOf(UnmarshalException.class)
            .hasRootCauseMessage("Failed to read stream");
    }

    @Test
    void shouldRejectMissingSchemaResource() {
        assertThatThrownBy(() -> service.unmarshal(
            StreamTestUtil.resourceStream("/fixtures/pacs-ttp/typical.xml"),
            PacsTppSchedule.class,
            "xsd/missing-pacs-schema.xsd",
            "PACS TTP file"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XML schema resource could not be found: xsd/missing-pacs-schema.xsd");
    }

    @Test
    void shouldRejectXmlContainingDoctype() {
        assertThatThrownBy(() -> service.unmarshal(
            StreamTestUtil.stream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE PacsTppSchedule [<!ENTITY test "test">]>
                <PacsTppSchedule xmlns="http://www.dwp.gsi.gov.uk/pacs"/>
                """),
            PacsTppSchedule.class,
            PACS_SCHEMA,
            "PACS TTP file"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PACS TTP file was not valid");
    }

    @Test
    void shouldRejectXmlThatDoesNotMatchSchema() {
        assertThatThrownBy(() -> service.unmarshal(
            StreamTestUtil.stream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <NotPacs xmlns="http://www.dwp.gsi.gov.uk/pacs"/>
                """),
            PacsTppSchedule.class,
            PACS_SCHEMA,
            "PACS TTP file"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PACS TTP file was not valid");
    }
}
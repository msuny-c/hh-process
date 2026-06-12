package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaFormErrorContractTest {

    @Test
    void everyCamundaFormShowsServerSideValidationMessageAndFields() throws IOException {
        Path formsDir = Path.of("src/main/resources/camunda/forms");
        List<Path> formFiles;
        try (var stream = Files.list(formsDir)) {
            formFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".form"))
                    .sorted()
                    .toList();
        }

        assertFalse(formFiles.isEmpty(), "Camunda forms directory must contain deployed forms");
        for (Path formFile : formFiles) {
            String json = Files.readString(formFile);
            String name = formFile.getFileName().toString();
            assertTrue(json.contains("\"id\": \"errorBanner\""), name + " must define errorBanner");
            assertTrue(json.contains("{{formErrorMessage}}"), name + " must display formErrorMessage");
            assertTrue(json.contains("{{formErrorFields}}"), name + " must display formErrorFields");
            assertTrue(json.contains("formErrorMessage = null or formErrorMessage = \\\"\\\""),
                    name + " must hide errorBanner when no server-side validation error exists");
        }
    }
}

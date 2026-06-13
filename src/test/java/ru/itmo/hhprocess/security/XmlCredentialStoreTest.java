package ru.itmo.hhprocess.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlCredentialStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAndReadsCredentialWithoutJacksonXmlRuntime() throws Exception {
        Path usersXml = tempDir.resolve("users.xml");
        XmlCredentialStore store = new XmlCredentialStore();
        ReflectionTestUtils.setField(store, "usersXmlPath", usersXml.toString());

        store.init();
        store.create("Candidate-Demo@Example.com", "hash");

        var user = store.findByEmail("candidate-demo@example.com").orElseThrow();
        assertEquals("candidate-demo@example.com", user.getEmail());
        assertEquals("hash", user.getPasswordHash());
        assertTrue(Files.readString(usersXml).contains("candidate-demo@example.com"));
    }
}

package ru.itmo.hhprocess.security;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.security.xml.XmlCredentialUser;
import ru.itmo.hhprocess.security.xml.XmlCredentialUsers;

@Service
@RequiredArgsConstructor
public class XmlCredentialStore {
    private final XmlMapper xmlMapper = new XmlMapper();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Value("${app.security.users-xml-path}")
    private String usersXmlPath;

    @PostConstruct
    public void init() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                ClassPathResource resource = new ClassPathResource("security/users.xml");
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize users XML store", e);
        }
    }

    public Optional<XmlCredentialUser> findByEmail(String email) {
        lock.readLock().lock();
        try {
            return readAll().getUsers().stream()
                    .filter(u -> normalize(u.getEmail()).equals(normalize(email)))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void create(String email, String passwordHash) {
        lock.writeLock().lock();
        try {
            XmlCredentialUsers users = readAll();
            String normalized = normalize(email);
            boolean exists = users.getUsers().stream().anyMatch(u -> normalize(u.getEmail()).equals(normalized));
            if (exists) {
                throw new IllegalStateException("Credential already exists for " + normalized);
            }
            users.getUsers().add(XmlCredentialUser.builder().email(normalized).passwordHash(passwordHash).build());
            writeAll(users);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private XmlCredentialUsers readAll() {
        try (InputStream in = Files.newInputStream(path())) {
            XmlCredentialUsers users = xmlMapper.readValue(in, XmlCredentialUsers.class);
            return users == null || users.getUsers() == null ? new XmlCredentialUsers() : users;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read users XML", e);
        }
    }

    private void writeAll(XmlCredentialUsers users) {
        try (OutputStream out = Files.newOutputStream(path())) {
            xmlMapper.writerWithDefaultPrettyPrinter().writeValue(out, users);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write users XML", e);
        }
    }

    private Path path() {
        return Path.of(usersXmlPath);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

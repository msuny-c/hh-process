package ru.itmo.hhprocess.security;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import ru.itmo.hhprocess.security.xml.XmlCredentialUser;
import ru.itmo.hhprocess.security.xml.XmlCredentialUsers;

@Service
@RequiredArgsConstructor
public class XmlCredentialStore {
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
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            Document document = factory.newDocumentBuilder().parse(in);
            XmlCredentialUsers users = new XmlCredentialUsers();
            var nodes = document.getDocumentElement().getElementsByTagName("user");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element element) {
                    users.getUsers().add(XmlCredentialUser.builder()
                            .email(element.getAttribute("email"))
                            .passwordHash(element.getAttribute("passwordHash"))
                            .build());
                }
            }
            return users;
        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("Failed to read users XML", e);
        }
    }

    private void writeAll(XmlCredentialUsers users) {
        Path target = path();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            Document document = factory.newDocumentBuilder().newDocument();
            Element root = document.createElement("users");
            document.appendChild(root);
            for (XmlCredentialUser user : users.getUsers()) {
                Element element = document.createElement("user");
                element.setAttribute("email", user.getEmail() == null ? "" : user.getEmail());
                element.setAttribute("passwordHash", user.getPasswordHash() == null ? "" : user.getPasswordHash());
                root.appendChild(element);
            }
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(document), new StreamResult(out));
        } catch (IOException | ParserConfigurationException | TransformerException e) {
            throw new IllegalStateException("Failed to write users XML", e);
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ioException) {
                throw new IllegalStateException("Failed to replace users XML", ioException);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to replace users XML", e);
        }
    }

    private Path path() {
        return Path.of(usersXmlPath);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}

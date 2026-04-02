package ru.itmo.hhprocess.security.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class XmlCredentialUser {
    @JacksonXmlProperty(isAttribute = true, localName = "email")
    private String email;

    @JacksonXmlProperty(isAttribute = true, localName = "passwordHash")
    private String passwordHash;
}

package com.flowmap.nexcore.openapi;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a NEXCORE {@code *.uio} IO spec (file name = unit name) into input/output
 * field lists, used to give the generated OpenAPI request/response real schemas.
 */
public final class UioParser {

    private UioParser() {
    }

    public static final class Field {
        public final String id;
        public final String name;
        public final String type;
        public final boolean required;

        Field(String id, String name, String type, boolean required) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.required = required;
        }
    }

    public static final class Io {
        public final List<Field> input = new ArrayList<>();
        public final List<Field> output = new ArrayList<>();
    }

    /** Parse the first {@code <method>} block of a .uio file; null on error/empty. */
    public static Io parse(Path uio) {
        try (InputStream in = Files.newInputStream(uio)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try {
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignore) {
            }
            Document doc = f.newDocumentBuilder().parse(in);
            Io io = new Io();
            collect(doc, "input", io.input);
            collect(doc, "output", io.output);
            return io;
        } catch (Exception e) {
            return null;
        }
    }

    private static void collect(Document doc, String section, List<Field> out) {
        NodeList secs = doc.getElementsByTagName(section);
        if (secs.getLength() == 0) return;
        Element sec = (Element) secs.item(0);
        NodeList fields = sec.getElementsByTagName("field");
        for (int i = 0; i < fields.getLength(); i++) {
            Element fe = (Element) fields.item(i);
            out.add(new Field(
                    fe.getAttribute("id"),
                    fe.getAttribute("name"),
                    fe.getAttribute("type").isEmpty() ? "string" : fe.getAttribute("type"),
                    "true".equalsIgnoreCase(fe.getAttribute("required"))));
        }
    }
}

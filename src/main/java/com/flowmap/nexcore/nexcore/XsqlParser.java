package com.flowmap.nexcore.nexcore;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an iBATIS-style {@code *.xsql} SQL map into {@code sqlId → }{@link Stmt}.
 * The file base name equals the owning DataUnit / batch-job class name
 * (e.g. {@code DACU6017.xsql}, {@code BSAC0006.xsql}); a {@code dbXxx(sqlId, ...)}
 * call's first argument selects the element, and the SQL's primary table becomes a
 * {@code db:table:<t>} RESOURCE node.
 */
public final class XsqlParser {

    private XsqlParser() {
    }

    /** One SQL statement: operation kind + primary table. */
    public static final class Stmt {
        public final String op;     // select | insert | update | delete | procedure
        public final String table;  // primary table (lowercased), may be null

        public Stmt(String op, String table) {
            this.op = op;
            this.table = table;
        }
    }

    private static final String[] STMT_TAGS = {"select", "insert", "update", "delete", "procedure"};

    /** Parse one .xsql file; returns empty map on any error. */
    public static Map<String, Stmt> parse(Path xsql) {
        Map<String, Stmt> out = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(xsql)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            // Be lenient: NEXCORE xsql files reference no DTD but disable external access anyway.
            try {
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignore) {
                // feature not supported by this parser — fine
            }
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(in);
            for (String tag : STMT_TAGS) {
                NodeList list = doc.getElementsByTagName(tag);
                for (int i = 0; i < list.getLength(); i++) {
                    Element el = (Element) list.item(i);
                    String id = el.getAttribute("id");
                    if (id == null || id.isEmpty()) continue;
                    String sql = el.getTextContent();
                    out.put(id, new Stmt(tag, extractTable(tag, sql)));
                }
            }
        } catch (Exception e) {
            // tolerate malformed / non-XML xsql — DB nodes simply won't be created
        }
        return out;
    }

    private static final Pattern P_INSERT = Pattern.compile("INSERT\\s+INTO\\s+([A-Za-z0-9_$.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_UPDATE = Pattern.compile("UPDATE\\s+([A-Za-z0-9_$.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DELETE = Pattern.compile("DELETE\\s+FROM\\s+([A-Za-z0-9_$.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FROM = Pattern.compile("FROM\\s+([A-Za-z0-9_$.]+)", Pattern.CASE_INSENSITIVE);

    /** Best-effort primary-table extraction from raw SQL text. */
    public static String extractTable(String op, String sql) {
        if (sql == null) return null;
        String s = sql.replace('\n', ' ').replace('\r', ' ').trim();
        Matcher m;
        switch (op) {
            case "insert":
                m = P_INSERT.matcher(s);
                return m.find() ? norm(m.group(1)) : null;
            case "update":
                m = P_UPDATE.matcher(s);
                return m.find() ? norm(m.group(1)) : null;
            case "delete":
                m = P_DELETE.matcher(s);
                return m.find() ? norm(m.group(1)) : null;
            case "select":
            default:
                m = P_FROM.matcher(s);
                return m.find() ? norm(m.group(1)) : null;
        }
    }

    private static String norm(String t) {
        if (t == null) return null;
        String x = t.trim();
        int dot = x.lastIndexOf('.');
        if (dot >= 0) x = x.substring(dot + 1); // schema.table → table
        return x.toLowerCase();
    }
}

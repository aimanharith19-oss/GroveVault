package org.grove.vault.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.util.Base64;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Plain-text (XML) form of a vault. This is what gets encrypted by {@link VaultCrypto}. */
public final class VaultXml {
    private VaultXml() {}

    public static final int FORMAT = 1;

    public static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        try { f.setXIncludeAware(false); } catch (Exception ignored) {}
        return f.newDocumentBuilder();
    }

    /**
     * Minimal XML writer (elements, attributes, text). Used instead of javax.xml.transform so output is identical
     * on every Android version. Characters that XML 1.0 cannot represent are dropped.
     */
    public static byte[] toBytes(Document d, boolean indent) throws Exception {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        writeNode(b, d.getDocumentElement(), indent, 0);
        return b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeNode(StringBuilder b, Node n, boolean indent, int depth) {
        if (n instanceof Element) {
            Element e = (Element) n;
            if (indent) for (int i = 0; i < depth; i++) b.append('\t');
            b.append('<').append(e.getTagName());
            org.w3c.dom.NamedNodeMap at = e.getAttributes();
            for (int i = 0; i < at.getLength(); i++) {
                Node a = at.item(i);
                b.append(' ').append(a.getNodeName()).append("=\"");
                escape(b, a.getNodeValue(), true);
                b.append('"');
            }
            NodeList kids = e.getChildNodes();
            if (kids.getLength() == 0) {
                b.append("/>");
                if (indent) b.append('\n');
                return;
            }
            boolean elementsOnly = true;
            for (int i = 0; i < kids.getLength(); i++) if (!(kids.item(i) instanceof Element)) elementsOnly = false;
            b.append('>');
            if (elementsOnly) {
                if (indent) b.append('\n');
                for (int i = 0; i < kids.getLength(); i++) writeNode(b, kids.item(i), indent, depth + 1);
                if (indent) for (int i = 0; i < depth; i++) b.append('\t');
            } else {
                for (int i = 0; i < kids.getLength(); i++) {
                    Node k = kids.item(i);
                    if (k instanceof Element) writeNode(b, k, false, 0);
                    else if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) escape(b, k.getNodeValue(), false);
                }
            }
            b.append("</").append(e.getTagName()).append('>');
            if (indent) b.append('\n');
        }
    }

    private static void escape(StringBuilder b, String s, boolean attr) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': if (attr) b.append("&quot;"); else b.append(c); break;
                case '\n': if (attr) b.append("&#10;"); else b.append(c); break;
                case '\r': b.append("&#13;"); break;
                case '\t': if (attr) b.append("&#9;"); else b.append(c); break;
                default:
                    if (c < 0x20 || c == 0xFFFE || c == 0xFFFF) break; // not representable in XML 1.0
                    b.append(c);
            }
        }
    }

    public static byte[] write(Vault v) throws Exception {
        Document d = builder().newDocument();
        Element root = d.createElement("vault");
        root.setAttribute("format", String.valueOf(FORMAT));
        root.setAttribute("modified", String.valueOf(v.modified));
        d.appendChild(root);
        for (Label l : v.labels) {
            Element e = d.createElement("label");
            e.setAttribute("id", l.id);
            e.setAttribute("name", l.name);
            root.appendChild(e);
        }
        for (Card c : v.cards) {
            Element e = d.createElement("card");
            e.setAttribute("id", c.id);
            e.setAttribute("title", c.title);
            e.setAttribute("icon", c.icon);
            e.setAttribute("color", String.valueOf(c.color));
            e.setAttribute("created", String.valueOf(c.created));
            e.setAttribute("modified", String.valueOf(c.modified));
            if (c.template) e.setAttribute("template", "1");
            if (c.favorite) e.setAttribute("favorite", "1");
            if (c.deleted) e.setAttribute("deleted", "1");
            for (Field f : c.fields) {
                Element fe = d.createElement("field");
                fe.setAttribute("name", f.name);
                fe.setAttribute("type", f.type);
                Element ve = d.createElement("v");
                ve.setTextContent(f.value);
                fe.appendChild(ve);
                for (Field.HistoryEntry h : f.history) {
                    Element he = d.createElement("old");
                    he.setAttribute("time", String.valueOf(h.time));
                    he.setTextContent(h.value);
                    fe.appendChild(he);
                }
                e.appendChild(fe);
            }
            for (String id : c.labelIds) {
                Element le = d.createElement("labelref");
                le.setAttribute("id", id);
                e.appendChild(le);
            }
            for (String app : c.apps) {
                Element ae = d.createElement("app");
                ae.setAttribute("package", app);
                if (c.appSigs.containsKey(app)) ae.setAttribute("sig", c.appSigs.get(app));
                e.appendChild(ae);
            }
            for (Attachment a : c.attachments) {
                Element ae = d.createElement("attachment");
                ae.setAttribute("name", a.name);
                ae.setAttribute("mime", a.mime);
                ae.setTextContent(Base64.getEncoder().encodeToString(a.data));
                e.appendChild(ae);
            }
            if (c.notes.length() > 0) {
                Element ne = d.createElement("notes");
                ne.setTextContent(c.notes);
                e.appendChild(ne);
            }
            root.appendChild(e);
        }
        return toBytes(d, false);
    }

    private static String a(Element e, String name, String def) {
        return e.hasAttribute(name) ? e.getAttribute(name) : def;
    }

    private static long l(Element e, String name) {
        try { return Long.parseLong(e.getAttribute(name)); } catch (Exception x) { return System.currentTimeMillis(); }
    }

    public static Vault read(byte[] xml) throws Exception {
        Document d = builder().parse(new ByteArrayInputStream(xml));
        Element root = d.getDocumentElement();
        if (!"vault".equals(root.getTagName())) throw new IllegalArgumentException("Not a vault");
        Vault v = new Vault();
        v.modified = l(root, "modified");
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            if ("label".equals(e.getTagName())) {
                v.labels.add(new Label(e.getAttribute("id"), e.getAttribute("name")));
            } else if ("card".equals(e.getTagName())) {
                Card c = new Card();
                c.id = a(e, "id", Vault.newId());
                c.title = a(e, "title", "");
                c.icon = a(e, "icon", "key");
                try { c.color = Integer.parseInt(a(e, "color", "0")); } catch (Exception ignored) {}
                c.created = l(e, "created");
                c.modified = l(e, "modified");
                c.template = "1".equals(e.getAttribute("template"));
                c.favorite = "1".equals(e.getAttribute("favorite"));
                c.deleted = "1".equals(e.getAttribute("deleted"));
                NodeList ck = e.getChildNodes();
                for (int j = 0; j < ck.getLength(); j++) {
                    Node cn = ck.item(j);
                    if (!(cn instanceof Element)) continue;
                    Element ce = (Element) cn;
                    String tag = ce.getTagName();
                    if ("field".equals(tag)) {
                        NodeList vk = ce.getElementsByTagName("v");
                        String val = vk.getLength() > 0 ? vk.item(0).getTextContent() : ce.getAttribute("value");
                        Field f = new Field(ce.getAttribute("name"), ce.getAttribute("type"), val);
                        NodeList hk = ce.getElementsByTagName("old");
                        for (int h = 0; h < hk.getLength(); h++) {
                            Element he = (Element) hk.item(h);
                            f.history.add(new Field.HistoryEntry(he.getTextContent(), l(he, "time")));
                        }
                        c.fields.add(f);
                    } else if ("labelref".equals(tag)) {
                        c.labelIds.add(ce.getAttribute("id"));
                    } else if ("app".equals(tag)) {
                        c.apps.add(ce.getAttribute("package"));
                        if (ce.hasAttribute("sig")) c.appSigs.put(ce.getAttribute("package"), ce.getAttribute("sig"));
                    } else if ("attachment".equals(tag)) {
                        c.attachments.add(new Attachment(ce.getAttribute("name"), ce.getAttribute("mime"),
                                Base64.getMimeDecoder().decode(ce.getTextContent())));
                    } else if ("notes".equals(tag)) {
                        c.notes = ce.getTextContent();
                    }
                }
                v.cards.add(c);
            }
        }
        return v;
    }
}

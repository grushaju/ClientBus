package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.util.*;

/** R08 HTML is data only; script content, links and image URLs are never executed. */
final class DikidiClientsHtml extends HTMLEditorKit.ParserCallback {
    final List<CrmClient> clients = new ArrayList<>();
    final Set<String> ids = new HashSet<>();
    private String id;
    private int depth, nameDepth=-1, anchorDepth=-1, phoneDepth=-1;
    private boolean invalid;
    private boolean phonePresent;
    private final StringBuilder name = new StringBuilder(), phone = new StringBuilder();
    static List<CrmClient> parse(String html) {
        var parser = new DikidiClientsHtml();
        try { new ParserDelegator().parse(new StringReader("<table>"+html+"</table>"), parser, true); }
        catch (IOException error) { throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
        if (parser.id != null || parser.invalid) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        return List.copyOf(parser.clients);
    }
    @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
        depth++;
        String rowId = String.valueOf(attrs.getAttribute(HTML.Attribute.ID));
        if (tag == HTML.Tag.TR && rowId.startsWith("item-")) {
            if (id != null) invalid();
            id = rowId.substring(5);
            if (!id.matches("[0-9]{1,128}") || !ids.add(id)) invalid();
            // System identity has not been established by the protocol research.
            if ("1".equals(String.valueOf(attrs.getAttribute("data-is-system")))) invalid();
            name.setLength(0); phone.setLength(0); phonePresent=false;
        }
        if (id == null) return;
        var classes = Arrays.asList(String.valueOf(attrs.getAttribute(HTML.Attribute.CLASS)).split("\\s+"));
        if (classes.contains("line-name")) nameDepth=depth;
        if (tag == HTML.Tag.A && nameDepth >= 0) anchorDepth=depth;
        if (classes.contains("line-phone")) { phoneDepth=depth; phonePresent=true; }
    }
    @Override public void handleText(char[] text, int pos) {
        if (id == null) return;
        if (anchorDepth >= 0) name.append(text);
        if (phoneDepth >= 0) phone.append(text);
        if (name.length()>65536 || phone.length()>256) invalid();
    }
    @Override public void handleEndTag(HTML.Tag tag, int pos) {
        if (tag == HTML.Tag.TR && id != null) {
            String displayName=name.toString().strip();
            if (displayName.isBlank()) invalid();
            Set<String> fields = new HashSet<>(Set.of("displayName"));
            if (phonePresent) fields.add("phone");
            clients.add(new CrmClient(id, "PROJECT", displayName,
                    phonePresent ? Map.of("phone",phone.toString().strip()) : Map.of(), Map.of("source","R08"), fields));
            id=null; nameDepth=anchorDepth=phoneDepth=-1;
        }
        if (nameDepth==depth) nameDepth=-1;
        if (anchorDepth==depth) anchorDepth=-1;
        if (phoneDepth==depth) phoneDepth=-1;
        depth--;
    }
    // ParserDelegator can swallow callback exceptions. Remember invalid data and fail after parsing.
    private void invalid() { invalid=true; }
}

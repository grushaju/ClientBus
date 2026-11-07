package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.util.*;

/** R08 HTML is data only; script content, links and image URLs are never executed. */
final class DikidiClientsHtml extends HTMLEditorKit.ParserCallback {
    // Match actual attributes of one terminal row, never attribute-like text or data-* names.
    static boolean emptyPage(String html) {
        if (html.isBlank()) return true;
        // Do not let the parser's implied closing tags turn a truncated response into EOF.
        if (!html.strip().toLowerCase(Locale.ROOT).endsWith("</tr>")) return false;
        var marker=new EmptyPage();
        try { new ParserDelegator().parse(new StringReader("<html><head></head><body><table>"+html+"</table></body></html>"),marker,true); }
        catch (IOException error) { return false; }
        return !marker.invalid && marker.tables==1 && marker.rows==1 && marker.cells==1
                && !marker.rowOpen && !marker.cellOpen && "Ничего не найдено".equals(marker.text.toString().strip());
    }
    private static final class EmptyPage extends HTMLEditorKit.ParserCallback {
        private boolean invalid,rowOpen,cellOpen;
        private int tables,rows,cells;
        private final StringBuilder text=new StringBuilder();
        @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
            if (tag==HTML.Tag.TABLE) { if (++tables>1) invalid=true; }
            else if (tag==HTML.Tag.TR) {
                if (rowOpen || attrs.getAttribute(HTML.Attribute.ID)!=null) invalid=true;
                rowOpen=true; rows++;
            } else if (tag==HTML.Tag.TD) {
                String span=String.valueOf(attrs.getAttribute(HTML.Attribute.COLSPAN));
                if (!rowOpen || cellOpen || !"red text-center".equals(attrs.getAttribute(HTML.Attribute.CLASS))
                        || !span.matches("[0-9]{1,3}") || Integer.parseInt(span)==0) invalid=true;
                cellOpen=true; cells++;
            } else if (tag!=HTML.Tag.HTML && tag!=HTML.Tag.HEAD && tag!=HTML.Tag.BODY) invalid=true;
        }
        @Override public void handleEndTag(HTML.Tag tag,int pos) {
            if (tag==HTML.Tag.TD) { if (!cellOpen) invalid=true; cellOpen=false; }
            if (tag==HTML.Tag.TR) { if (!rowOpen || cellOpen) invalid=true; rowOpen=false; }
        }
        @Override public void handleText(char[] value,int pos) {
            if (cellOpen) { text.append(value); if (text.length()>200) invalid=true; }
            else if (!new String(value).isBlank()) invalid=true;
        }
        @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) { invalid=true; }
        @Override public void handleComment(char[] value,int pos) { invalid=true; }
        @Override public void handleError(String error,int pos) {
            // Swing's HTML 3.2 DTD warns even about class on td; validate attributes above.
            if (!error.startsWith("invalid.tagatt ")) invalid=true;
        }
    }
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

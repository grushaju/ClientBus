package kit.penny.clientbus.crm.dikidi;

import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.util.*;

/** Parse HTML as data. No scripts, URLs, forms or images are executed. */
final class DikidiHtml extends HTMLEditorKit.ParserCallback {
    final Map<String, String> hidden = new LinkedHashMap<>();
    private final StringBuilder heading = new StringBuilder();
    private boolean inHeading, headingSeen;
    static DikidiHtml parse(String html) {
        var result = new DikidiHtml();
        try { new ParserDelegator().parse(new StringReader(html == null ? "" : html), result, true); }
        catch (IOException error) { throw new IllegalArgumentException("Invalid HTML"); }
        return result;
    }
    @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
        if (tag != HTML.Tag.INPUT || !"hidden".equalsIgnoreCase(String.valueOf(attrs.getAttribute(HTML.Attribute.TYPE)))) return;
        Object name = attrs.getAttribute(HTML.Attribute.NAME), value = attrs.getAttribute(HTML.Attribute.VALUE);
        if (name != null && value != null && Set.of("csrf", "telegram_csrf").contains(name.toString()))
            hidden.put(name.toString(), value.toString());
    }
    @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
        if (tag == HTML.Tag.H2 && !headingSeen) { inHeading = true; headingSeen = true; }
    }
    @Override public void handleEndTag(HTML.Tag tag, int pos) { if (tag == HTML.Tag.H2) inHeading = false; }
    @Override public void handleText(char[] data, int pos) { if (inHeading) heading.append(data).append(' '); }
    String heading() { return heading.toString().trim().replaceAll("\\s+", " "); }
}


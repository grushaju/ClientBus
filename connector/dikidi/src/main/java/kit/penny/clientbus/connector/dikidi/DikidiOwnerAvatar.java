package kit.penny.clientbus.connector.dikidi;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;

/** Photo in the authenticated user's menu, not a project or a chat participant. */
final class DikidiOwnerAvatar {
    private DikidiOwnerAvatar() {}

    static String parse(String html) {
        if (html == null || html.isBlank()) return null;
        var candidates = new ArrayList<String>();
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                int liDepth, userDepth, divDepth, iconDepth;
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (tag == HTML.Tag.LI) {
                        liDepth++;
                        if (hasClass(attrs, "dropdown") && hasClass(attrs, "user")) userDepth = liDepth;
                    }
                    if (tag == HTML.Tag.DIV) {
                        divDepth++;
                        if (userDepth > 0 && hasClass(attrs, "icon") && hasClass(attrs, "img-circle")) iconDepth = divDepth;
                    }
                }
                @Override public void handleEndTag(HTML.Tag tag, int pos) {
                    if (tag == HTML.Tag.LI) {
                        if (liDepth == userDepth) userDepth = 0;
                        liDepth--;
                    }
                    if (tag == HTML.Tag.DIV) {
                        if (divDepth == iconDepth) iconDepth = 0;
                        divDepth--;
                    }
                }
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (tag == HTML.Tag.IMG && userDepth > 0 && iconDepth > 0) {
                        Object src = attrs.getAttribute(HTML.Attribute.SRC);
                        if (src != null) candidates.add(src.toString());
                    }
                }
            }, true);
        } catch (java.io.IOException | RuntimeException ignored) { return null; }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static boolean hasClass(MutableAttributeSet attrs, String value) {
        Object classes = attrs.getAttribute(HTML.Attribute.CLASS);
        return classes != null && Arrays.asList(classes.toString().split("\\s+")).contains(value);
    }
}

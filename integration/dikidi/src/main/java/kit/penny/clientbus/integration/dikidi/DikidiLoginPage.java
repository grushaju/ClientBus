package kit.penny.clientbus.integration.dikidi;

import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.util.*;

/**
 * Технический разбор HTML-страниц входа (13.4): скрытые поля форм csrf/
 * telegram_csrf — единственное, что нужно рукопожатию авторизации. HTML —
 * данные; скрипты, URL, формы и изображения не исполняются. CRM-доказательства
 * (заголовок проекта, якорь logout) остаются в адаптере crm/dikidi.
 */
public final class DikidiLoginPage extends HTMLEditorKit.ParserCallback {
    private final Map<String, String> hidden = new LinkedHashMap<>();
    public static DikidiLoginPage parse(String html) {
        var result = new DikidiLoginPage();
        try { new ParserDelegator().parse(new StringReader(html == null ? "" : html), result, true); }
        catch (IOException error) { throw new DikidiFailure(DikidiFailure.Code.INVALID_RESPONSE); }
        return result;
    }
    @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
        if (tag != HTML.Tag.INPUT || !"hidden".equalsIgnoreCase(String.valueOf(attrs.getAttribute(HTML.Attribute.TYPE)))) return;
        Object name = attrs.getAttribute(HTML.Attribute.NAME), value = attrs.getAttribute(HTML.Attribute.VALUE);
        if (name != null && value != null && Set.of("csrf", "telegram_csrf").contains(name.toString()))
            hidden.put(name.toString(), value.toString());
    }
    public Map<String, String> hidden() { return Collections.unmodifiableMap(hidden); }
}

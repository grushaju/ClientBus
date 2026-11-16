package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.CrmFailure;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HTML страницы правок и ajax-формы edit/{id} — только данные (13.6, живые наблюдения
 * 16.11.2026): строки страницы ключены id записи доступа (tr id="item-{digits}"), форма
 * отдаёт select[name=master] с selected-опцией (привязка сотрудника каталога, "0" —
 * выбрано), select[name=role] с selected-опцией (значение совпадает с roles_id из
 * company.accesses — словарь названий ролей), hidden input[name=number] (телефон логина)
 * и input[name=name] (имя доступа). Скрипты, ссылки и изображения не исполняются.
 */
final class DikidiPermissions {
    private DikidiPermissions() { }

    record Form(String masterExternalId, String roleValue, String roleLabel, String phone, String name) { }

    /** Id записей доступа со страницы (уникальные, порядок страницы). Пусто — не страница правок. */
    static List<String> pageAccessIds(String html) {
        Set<String> ids = new LinkedHashSet<>();
        var matcher = java.util.regex.Pattern.compile("<tr[^>]*\\bid=\"item-([0-9]{1,20})\"").matcher(html == null ? "" : html);
        while (matcher.find()) {
            if (!ids.add(matcher.group(1)) || ids.size() > 200)
                throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        }
        // Владелец кабинета есть в проекте всегда; пустой список — страница не правок.
        if (ids.isEmpty()) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        return new ArrayList<>(ids);
    }

    static Form editForm(String html) {
        var parser = new FormCallback();
        try { new ParserDelegator().parse(new StringReader(html == null ? "" : html), parser, true); }
        catch (IOException error) { throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
        if (parser.invalid || parser.roleValue == null || parser.roleLabel == null)
            throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        String phone = parser.phone;
        if (phone != null && !phone.matches("[0-9]{4,20}")) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        String name = parser.name == null ? "" : parser.name.strip();
        if (name.length() > 65536) throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);
        return new Form(parser.master, parser.roleValue, parser.roleLabel, phone, name);
    }

    private static final class FormCallback extends HTMLEditorKit.ParserCallback {
        private boolean invalid;
        private String select;              // "master" | "role" | null
        private String option;              // value текущей опции
        private boolean optionSelected;
        private final StringBuilder optionText = new StringBuilder();
        private String master;              // null — сотрудник не выбран/не observed
        private boolean masterSeen;
        private String roleValue, roleLabel, phone, name;

        @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (tag == HTML.Tag.SELECT) {
                String field = String.valueOf(attrs.getAttribute(HTML.Attribute.NAME));
                select = "master".equals(field) || "role".equals(field) ? field : null;
            } else if (tag == HTML.Tag.OPTION && select != null) {
                option = String.valueOf(attrs.getAttribute(HTML.Attribute.VALUE));
                optionSelected = attrs.getAttribute(HTML.Attribute.SELECTED) != null;
                optionText.setLength(0);
            }
        }
        @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (tag != HTML.Tag.INPUT) return;
            String field = String.valueOf(attrs.getAttribute(HTML.Attribute.NAME));
            String value = String.valueOf(attrs.getAttribute(HTML.Attribute.VALUE));
            if ("number".equals(field)) { if (phone != null) invalid = true; phone = value; }
            else if ("name".equals(field)) { if (name != null) invalid = true; name = value; }
        }
        @Override public void handleText(char[] text, int pos) {
            if (select != null && option != null) {
                optionText.append(text);
                if (optionText.length() > 4096) invalid = true;
            }
        }
        @Override public void handleEndTag(HTML.Tag tag, int pos) {
            if (tag == HTML.Tag.OPTION && select != null && option != null) {
                if (optionSelected) {
                    if ("master".equals(select)) {
                        if (masterSeen) invalid = true;
                        masterSeen = true;
                        if (!"0".equals(option)) {
                            if (!option.matches("[0-9]{1,20}")) invalid = true;
                            else if (master != null) invalid = true; else master = option;
                        }
                    } else if (roleValue != null) invalid = true;
                    else {
                        if (!option.matches("[0-9]{1,20}")) invalid = true;
                        roleValue = option; roleLabel = optionText.toString().strip();
                        if (roleLabel.isEmpty() || roleLabel.length() > 256) invalid = true;
                    }
                }
                option = null;
            } else if (tag == HTML.Tag.SELECT) { select = null; option = null; }
        }
    }
}

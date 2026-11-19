package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.CrmFailure;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Read the reviewed statistics labels as data. Provider callbacks and scripts are never executed. */
final class DikidiClientStatisticsHtml extends HTMLEditorKit.ParserCallback {
    record Statistics(long visits, BigDecimal averageCheck, String currency) {
        BigDecimal totalSpent() { return averageCheck.multiply(BigDecimal.valueOf(visits)); }
    }
    private final Map<String,String> values = new HashMap<>();
    private final StringBuilder label = new StringBuilder(), value = new StringBuilder();
    private int depth, rowDepth=-1, labelDepth=-1, valueDepth=-1, scriptDepth=-1;
    private boolean invalid;

    static Statistics parse(String html) {
        var parser=new DikidiClientStatisticsHtml();
        try { new ParserDelegator().parse(new StringReader(html),parser,true); }
        catch (IOException e) { throw failure(); }
        String count=parser.values.get("Всего записей:"), check=parser.values.get("Средний чек:");
        if (parser.invalid || count==null || check==null) throw failure();
        count=compact(count);
        if (!count.matches("[0-9]{1,9}")) throw failure();
        var money=Pattern.compile("([0-9]{1,17}(?:[.,][0-9]{1,2})?)([A-Z]{3})").matcher(compact(check));
        if (!money.matches()) throw failure();
        var stats=new Statistics(Long.parseLong(count),new BigDecimal(money.group(1).replace(',','.')),money.group(2));
        if (stats.totalSpent().precision()-stats.totalSpent().scale()>17) throw failure();
        return stats;
    }
    @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
        depth++;
        if (tag==HTML.Tag.SCRIPT || tag==HTML.Tag.STYLE) { scriptDepth=depth; return; }
        if (scriptDepth>=0) return;
        var classes=Arrays.asList(String.valueOf(attrs.getAttribute(HTML.Attribute.CLASS)).split("\\s+"));
        if (classes.contains("form-group") && classes.contains("row")) {
            if (rowDepth>=0) invalid=true;
            rowDepth=depth; label.setLength(0); value.setLength(0);
        }
        if (rowDepth>=0 && classes.contains("col-xs-4")) labelDepth=depth;
        if (rowDepth>=0 && classes.contains("col-xs-8")) valueDepth=depth;
    }
    @Override public void handleText(char[] text, int pos) {
        if (scriptDepth>=0) return;
        if (labelDepth>=0) label.append(text);
        if (valueDepth>=0) value.append(text);
        if (label.length()>256 || value.length()>1024) invalid=true;
    }
    @Override public void handleEndTag(HTML.Tag tag, int pos) {
        if (depth==scriptDepth) scriptDepth=-1;
        if (depth==labelDepth) labelDepth=-1;
        if (depth==valueDepth) valueDepth=-1;
        if (depth==rowDepth) {
            String key=label.toString().strip();
            if (values.putIfAbsent(key,value.toString().strip())!=null) invalid=true;
            rowDepth=labelDepth=valueDepth=-1;
        }
        depth--;
    }
    private static String compact(String value) { return value.replaceAll("[\\s\\u00a0\\u202f]", ""); }
    private static CrmFailure failure() { return new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
}

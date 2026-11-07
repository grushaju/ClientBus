package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import static kit.penny.clientbus.crm.api.CrmFailure.Code.*;

/** HTML is data only. Values are associated by their containing row, never zipped by field name. */
final class DikidiFinanceHtml extends HTMLEditorKit.ParserCallback {
    private static final class Node {
        final Node parent;
        final int depth;
        final String tag;
        final Map<String,String> attrs=new HashMap<>();
        final List<Node> children=new ArrayList<>();
        Node(Node parent,String tag,MutableAttributeSet attributes) {
            this.parent=parent; this.tag=tag;
            this.depth=parent==null ? 0 : parent.depth+1;
            if (depth>128) invalid();
            if (parent!=null) parent.children.add(this);
            if (attributes!=null) for (var keys=attributes.getAttributeNames();keys.hasMoreElements();) {
                var key=keys.nextElement(); attrs.put(key.toString(),String.valueOf(attributes.getAttribute(key)));
            }
        }
        List<Node> fields(String name) {
            var result=new ArrayList<Node>();
            if ((tag.equals("input") || tag.equals("select")) && name.equals(attrs.get("name"))) result.add(this);
            for (var child:children) result.addAll(child.fields(name));
            return result;
        }
        String value() {
            if (!tag.equals("select")) return attrs.getOrDefault("value","");
            var options=new ArrayList<Node>(); options(this,options);
            var selected=options.stream().filter(n -> n.attrs.containsKey("selected")).toList();
            if (selected.size()>1) invalid();
            return (selected.isEmpty() ? options.stream().findFirst() : selected.stream().findFirst())
                    .map(n -> n.attrs.getOrDefault("value","")).orElse("");
        }
        private static void options(Node node,List<Node> result) {
            if (node.tag.equals("option")) result.add(node);
            node.children.forEach(child -> options(child,result));
        }
    }
    private final Node root=new Node(null,"root",null);
    private Node current=root;
    private int nodes;
    static DikidiFinanceHtml parse(String html) {
        if (html==null || html.length()>4194304) invalid();
        var result=new DikidiFinanceHtml();
        try { new ParserDelegator().parse(new StringReader(html),result,true); }
        catch (IOException error) { throw new CrmFailure(INVALID_RESPONSE); }
        return result;
    }
    @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
        if (++nodes>30000) invalid();
        current=new Node(current,tag.toString(),attrs);
    }
    @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
        if (++nodes>30000) invalid();
        new Node(current,tag.toString(),attrs);
    }
    @Override public void handleEndTag(HTML.Tag tag,int pos) {
        for (Node node=current;node!=root;node=node.parent) if (node.tag.equals(tag.toString())) { current=node.parent; break; }
    }
    String one(String name) { return one(root,name); }
    private static String one(Node node,String name) {
        var fields=node.fields(name); if (fields.size()!=1) invalid();
        return fields.getFirst().value();
    }
    void requireIdentity(String name,String expected) {
        if (!Objects.equals(expected,one(name))) throw new CrmFailure(CONTEXT_MISMATCH);
    }
    BigDecimal optionalAmount(String name) {
        var fields=root.fields(name); if (fields.size()>1) invalid();
        return fields.isEmpty() ? null : amount(fields.getFirst().value());
    }
    List<CrmFinanceSnapshot.Line> lines() {
        var result=new ArrayList<CrmFinanceSnapshot.Line>(); var ids=new HashSet<String>();
        for (var field:root.fields("services_id[]")) {
            if (field.value().isBlank() || field.value().equals("0")) continue; // Empty form template.
            String id=id(field.value()); if (!ids.add(id) || result.size()>=200) invalid();
            Node row=group(field,"services_id[]",List.of("services_master_id[]","services_service_id[]",
                    "services_price[]","services_discount[]","services_discount_cost[]","services_total[]"));
            result.add(new CrmFinanceSnapshot.Line(id,id(one(row,"services_master_id[]")),id(one(row,"services_service_id[]")),
                    amount(one(row,"services_price[]")),percent(one(row,"services_discount[]")),
                    amount(one(row,"services_discount_cost[]")),amount(one(row,"services_total[]"))));
        }
        // Only the observed service-sale shape is supported. Empty/new/mixed forms are not zero sales.
        if (result.isEmpty()) throw new CrmFailure(CAPABILITY_UNVERIFIED);
        return result;
    }
    List<CrmFinanceSnapshot.Component> components() {
        var result=new ArrayList<CrmFinanceSnapshot.Component>();
        for (var field:root.fields("method[]")) {
            Node row=group(field,"method[]",List.of("price[]","method_id[]"));
            String raw=one(row,"price[]"); if (raw.isBlank()) continue;
            BigDecimal value=amount(raw); if (value.signum()==0) continue;
            String method=field.value();
            if (!Set.of("cash","card","season_ticket","balance","bonus","certificate","qr").contains(method))
                throw new CrmFailure(CAPABILITY_UNVERIFIED);
            if (result.size()>=50) invalid();
            result.add(new CrmFinanceSnapshot.Component(method,id(one(row,"method_id[]")),value));
        }
        if (result.isEmpty()) throw new CrmFailure(CAPABILITY_UNVERIFIED);
        return result;
    }
    private static Node group(Node field,String identity,List<String> names) {
        for (Node node=field.parent;node!=null;node=node.parent) {
            if (node.fields(identity).size()!=1) break;
            Node candidate=node;
            if (names.stream().allMatch(name -> candidate.fields(name).size()==1)) return node;
        }
        throw new CrmFailure(INVALID_RESPONSE);
    }
    Set<String> paymentIds(String project,String sale) {
        var ids=new LinkedHashSet<String>(); links(root,project,sale,ids); return ids;
    }
    private static void links(Node node,String project,String sale,Set<String> ids) {
        if (node.tag.equals("a")) {
            String href=node.attrs.getOrDefault("href",node.attrs.get("data-href"));
            if (href!=null) {
                try {
                    URI uri=URI.create("https://dikidi.ru").resolve(href);
                    if ("/ru/owner/ajax/sales_payments/form/".equals(uri.getPath())) {
                        if (!"https".equals(uri.getScheme()) || !"dikidi.ru".equals(uri.getHost()) || uri.getUserInfo()!=null || uri.getPort()!=-1)
                            throw new CrmFailure(CONTEXT_MISMATCH);
                        var query=new HashMap<String,String>();
                        for (String pair:Objects.toString(uri.getRawQuery(),"").split("&")) {
                            var parts=pair.split("=",2); if (parts.length!=2) invalid();
                            if (query.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8))!=null) invalid();
                        }
                        if (!project.equals(query.get("company")) || !sale.equals(query.get("sale"))) throw new CrmFailure(CONTEXT_MISMATCH);
                        String paymentId=query.get("id");
                        // The list also offers a new-payment form with no existing identity.
                        // It is navigation, not a recorded payment; never follow that link.
                        if (paymentId!=null && !paymentId.isBlank() && !paymentId.equals("0"))
                            if (!ids.add(id(paymentId)) || ids.size()>50) invalid();
                    }
                } catch (IllegalArgumentException error) { throw new CrmFailure(INVALID_RESPONSE); }
            }
        }
        node.children.forEach(child -> links(child,project,sale,ids));
    }
    static String id(String value) {
        if (value==null || !value.matches("[1-9][0-9]{0,17}")) throw new CrmFailure(CONTEXT_MISMATCH);
        return value;
    }
    static BigDecimal amount(String value) {
        if (value==null || value.isBlank()) return null;
        if (!value.matches("[0-9]{1,15}(?:\\.[0-9]{1,4})?")) invalid();
        return new BigDecimal(value).stripTrailingZeros();
    }
    private static BigDecimal percent(String value) {
        BigDecimal result=amount(value); if (result!=null && result.compareTo(BigDecimal.valueOf(100))>0) invalid(); return result;
    }
    private static void invalid() { throw new CrmFailure(INVALID_RESPONSE); }
}

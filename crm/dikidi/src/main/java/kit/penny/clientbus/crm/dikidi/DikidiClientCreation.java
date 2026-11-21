package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import tools.jackson.databind.JsonNode;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.util.*;

/** Owner form is data, never executable JavaScript. Unknown response shapes remain uncertain. */
final class DikidiClientCreation {
    static List<Map.Entry<String,String>> form(String html, String project, CrmClientDraft draft) {
        var fields=new HashSet<String>(); var required=new HashSet<String>(); var actions=new ArrayList<String>();
        boolean[] invalidForm={false};
        try { new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback() {
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet a,int pos) {
                if(tag==HTML.Tag.FORM) {
                    // ParserDelegator swallows callback exceptions; reject after parsing.
                    if(!"post".equalsIgnoreCase(String.valueOf(a.getAttribute(HTML.Attribute.METHOD)))) invalidForm[0]=true;
                    actions.add(String.valueOf(a.getAttribute(HTML.Attribute.ACTION)));
                }
                field(a);
            }
            @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet a,int pos) { field(a); }
            private void field(MutableAttributeSet a) {
                Object name=a.getAttribute(HTML.Attribute.NAME);
                if(name!=null){fields.add(name.toString());if(a.getAttribute("required")!=null)required.add(name.toString());}
            }
        },true); } catch(IOException impossible) { invalid(); }
        if(invalidForm[0] || !actions.equals(List.of("/ru/owner/ajax/clients/save/?company="+project))
                || !fields.containsAll(Set.of("name","surname","number"))
                || !Set.of("name","number").containsAll(required)) invalid();
        // No notification, invitation, chat or consent flags are inferred or sent.
        return List.of(Map.entry("name",draft.firstName()),Map.entry("surname",draft.lastName()),
                Map.entry("number",Objects.requireNonNull(CrmPhone.normalize(draft.phone()))),
                Map.entry("email",""),Map.entry("birthday",""),Map.entry("discount",""),Map.entry("card",""),
                Map.entry("source_id","0"),Map.entry("comment",""),Map.entry("blacklist_comment",""));
    }
    static String rowsHtml(JsonNode response) {
        String html=null;
        if(response.path("html").isString())html=response.path("html").asString();
        if(response.path("script").isArray())for(var script:response.path("script")) {
            if(!script.path("html").isString())continue;
            if(script.has("container")) {
                if(".company-clients .count-items".equals(script.path("container").asString("")) && !script.has("append"))continue;
                invalid();
            }
            if(!".company-clients tbody".equals(script.path("append").asString("")))invalid();
            if(html!=null)invalid();html=script.path("html").asString();
        }
        if(html==null)invalid();return html;
    }
    /** Only a client row returned by THIS save is proof of its identity. Dialog IDs are not client IDs. */
    static CrmClient created(JsonNode response,CrmClientDraft draft) {
        // Observed owner save contract: client_info identifies the created client;
        // html is a complete refreshed directory and may contain unrelated clients.
        if(response.has("client_info")) {
            var client=response.path("client_info");String id=client.path("id").asString("");
            boolean inserted=false;
            if(response.path("callback").isArray())for(var callback:response.path("callback"))
                if("$(\"body\").trigger(\"clients.insert\", data.client_info)".equals(callback.asString("")))inserted=true;
            if(!inserted || response.has("client") || response.has("data") || !id.matches("[1-9][0-9]{0,19}")
                    || !client.path("name").isString() || !client.path("surname").isString() || !client.path("phone").isString()
                    || client.has("is_system") && !"0".equals(client.path("is_system").asString(""))
                    || !draft.firstName().equals(client.path("name").asString()) || !draft.lastName().equals(client.path("surname").asString())
                    || !Objects.equals(CrmPhone.normalize(draft.phone()),CrmPhone.normalize(client.path("phone").asString())))invalid();
            return new CrmClient(id,"PROJECT",(draft.firstName()+" "+draft.lastName()).strip(),Map.of("phone",client.path("phone").asString()),Map.of(),Set.of("displayName","phone"));
        }
        // Structured journal client payload: require all identity attributes, never use a modal's bare id.
        CrmClient confirmed=null;
        for(var candidate:List.of(response,response.path("client"),response.path("data"))) {
            String id=candidate.path("id").asString("");
            if(id.matches("[1-9][0-9]{0,19}") && candidate.path("name").isString() && candidate.path("surname").isString()
                    && candidate.path("phone").isString() && "0".equals(candidate.path("is_system").asString("")))
            {
                if(!draft.firstName().equals(candidate.path("name").asString()) || !draft.lastName().equals(candidate.path("surname").asString())
                        || !Objects.equals(CrmPhone.normalize(draft.phone()),CrmPhone.normalize(candidate.path("phone").asString())))invalid();
                if(confirmed!=null && !confirmed.externalId().equals(id))invalid();
                confirmed=new CrmClient(id,"PROJECT",(draft.firstName()+" "+draft.lastName()).strip(),Map.of("phone",candidate.path("phone").asString()),Map.of(),Set.of("displayName","phone"));
            }
        }
        // A save may contain both a structured client and refreshed rows. Both must agree.
        if(confirmed!=null && !response.has("html") && !response.has("script"))return confirmed;
        var rows=DikidiClientsHtml.parse(rowsHtml(response));
        if(rows.size()!=1)invalid();var row=rows.getFirst();
        if(!"PROJECT".equals(row.identityKind()) || !Objects.equals(CrmPhone.normalize(draft.phone()),CrmPhone.normalize(row.contacts().get("phone")))
                || !(draft.firstName()+" "+draft.lastName()).strip().equals(row.displayName()))invalid();
        if(confirmed!=null && !confirmed.externalId().equals(row.externalId()))invalid();
        return row;
    }
    private static void invalid(){throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);}
}

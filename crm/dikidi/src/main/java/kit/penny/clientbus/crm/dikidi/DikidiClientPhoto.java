package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.util.*;

/** Serialize only the selected owner profile form, preserving its current successful controls. */
final class DikidiClientPhoto {
    static List<Map.Entry<String,String>> profile(String html,String project,String client) {
        var fields=new ArrayList<Map.Entry<String,String>>();int[] forms={0};boolean[] invalidForm={false};
        try {new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback(){
            boolean active;String select,option,textarea;boolean selected,hasSelected;String first,chosen;StringBuilder text=new StringBuilder();
            // Swing's parser catches exceptions thrown by callbacks. Remember rejection
            // explicitly and throw only after parsing, outside the callback boundary.
            void invalid(){invalidForm[0]=true;}
            String attr(MutableAttributeSet a,Object key){Object value=a.getAttribute(key);return value==null?null:value.toString();}
            void add(String name,String value){if(name!=null){if(name.length()>128 || value.length()>65536 || fields.size()>500)invalid();fields.add(Map.entry(name,value));}}
            // ParserDelegator does not model inherited disabled state (fieldset/optgroup).
            // Refuse these forms rather than silently changing other profile fields.
            void checkDisabledContainer(HTML.Tag tag,MutableAttributeSet a){
                if(active && Set.of("fieldset","optgroup","option").contains(tag.toString()) && a.getAttribute("disabled")!=null)invalid();
            }
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet a,int pos){
                if(invalidForm[0])return;
                if(tag==HTML.Tag.FORM){active=("/ru/owner/ajax/clients/save/"+client+"/?company="+project).equals(attr(a,HTML.Attribute.ACTION));if(active){forms[0]++;if(!"post".equalsIgnoreCase(attr(a,HTML.Attribute.METHOD)))invalid();}}
                if(!active)return;
                checkDisabledContainer(tag,a);
                if(tag==HTML.Tag.SELECT){if(a.getAttribute(HTML.Attribute.MULTIPLE)!=null)invalid();select=a.getAttribute("disabled")==null?attr(a,HTML.Attribute.NAME):null;first=null;chosen=null;hasSelected=false;}
                if(tag==HTML.Tag.OPTION){option=attr(a,HTML.Attribute.VALUE);selected=a.getAttribute(HTML.Attribute.SELECTED)!=null;text.setLength(0);}
                if(tag==HTML.Tag.TEXTAREA){textarea=a.getAttribute("disabled")==null?attr(a,HTML.Attribute.NAME):null;text.setLength(0);}
            }
            @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet a,int pos){
                if(invalidForm[0])return;
                checkDisabledContainer(tag,a);
                if(!active || tag!=HTML.Tag.INPUT || a.getAttribute("disabled")!=null)return;
                String type=Objects.toString(attr(a,HTML.Attribute.TYPE),"text").toLowerCase(Locale.ROOT);
                if(Set.of("submit","button","reset").contains(type))return;
                if(type.equals("file"))invalid();
                if(Set.of("checkbox","radio").contains(type) && a.getAttribute(HTML.Attribute.CHECKED)==null)return;
                add(attr(a,HTML.Attribute.NAME),Objects.toString(attr(a,HTML.Attribute.VALUE),Set.of("checkbox","radio").contains(type)?"on":""));
            }
            @Override public void handleText(char[] data,int pos){if(active && !invalidForm[0])text.append(data);}
            @Override public void handleEndTag(HTML.Tag tag,int pos){
                if(!active || invalidForm[0])return;
                if(tag==HTML.Tag.OPTION){String value=option==null?text.toString():option;if(first==null)first=value;if(selected){if(hasSelected)invalid();chosen=value;hasSelected=true;}}
                if(tag==HTML.Tag.SELECT){add(select,Objects.toString(hasSelected?chosen:first,""));select=null;}
                if(tag==HTML.Tag.TEXTAREA){add(textarea,text.toString());textarea=null;}
                if(tag==HTML.Tag.FORM)active=false;
            }
        },true);}catch(IOException impossible){invalid();}
        if(invalidForm[0] || forms[0]!=1 || !fields.stream().map(Map.Entry::getKey).toList().containsAll(Set.of("name","surname","number")))invalid();
        if(fields.stream().filter(e->Set.of("name","surname","number").contains(e.getKey())).count()!=3)invalid();
        return List.copyOf(fields);
    }
    static String value(List<Map.Entry<String,String>> fields,String name){return fields.stream().filter(e->e.getKey().equals(name)).map(Map.Entry::getValue).findFirst().orElseThrow(()->new CrmFailure(CrmFailure.Code.INVALID_RESPONSE));}
    private static void invalid(){throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE);}
}

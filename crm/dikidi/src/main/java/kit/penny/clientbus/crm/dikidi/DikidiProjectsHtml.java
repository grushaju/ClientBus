package kit.penny.clientbus.crm.dikidi;

import kit.penny.clientbus.crm.api.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.net.URI;
import java.util.*;

/** Account-owned business list, never the public company catalog. Links are data, not requests. */
final class DikidiProjectsHtml extends HTMLEditorKit.ParserCallback {
    private final List<CrmProjectInfo> projects = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private final StringBuilder name = new StringBuilder();
    private int depth, rowDepth=-1, nameDepth=-1;
    private int emptyCount, emptyDepth=-1, emptyHeadingDepth=-1;
    private final StringBuilder emptyHeading=new StringBuilder();
    private String id;
    private String source;
    private boolean invalid;
    static boolean businessPage(String body) {
        final boolean[] found={false};
        parse(body,new HTMLEditorKit.ParserCallback() {
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
                if (tag==HTML.Tag.FORM && classes(attrs).containsAll(Set.of("new-elements-list","projects"))
                        && "/ru/business/ajax/projects/list/".equals(attrs.getAttribute(HTML.Attribute.ACTION))
                        && "post".equalsIgnoreCase(String.valueOf(attrs.getAttribute("data-method")))) found[0]=true;
            }
        });
        return found[0];
    }
    static List<CrmProjectInfo> projects(String body) {
        if (!body.stripTrailing().toLowerCase(Locale.ROOT).endsWith("</div>")) throw invalid();
        var parser=new DikidiProjectsHtml(); parser.source=body; parse(body,parser);
        if (parser.invalid || parser.id!=null || parser.projects.isEmpty() && !(parser.emptyCount==1
                && "По вашему запросу ничего не найдено".equals(parser.emptyHeading.toString().strip()))
                || !parser.projects.isEmpty() && parser.emptyCount>0) throw invalid();
        return List.copyOf(parser.projects);
    }
    private static void parse(String body,HTMLEditorKit.ParserCallback callback) {
        try { new ParserDelegator().parse(new StringReader(body),callback,true); }
        catch (IOException error) { throw invalid(); }
    }
    private static Set<String> classes(MutableAttributeSet attrs) {
        return new HashSet<>(Arrays.asList(String.valueOf(attrs.getAttribute(HTML.Attribute.CLASS)).split("\\s+")));
    }
    @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
        depth++;
        var classes=classes(attrs);
        if (tag==HTML.Tag.DIV && classes.containsAll(Set.of("new-elements-content-block","stub-custom"))) {
            emptyCount++; emptyDepth=depth;
        }
        if (emptyDepth>=0 && tag==HTML.Tag.DIV && classes.contains("stub-header")) emptyHeadingDepth=depth;
        if (tag==HTML.Tag.DIV && classes.containsAll(Set.of("new-elements-content-block","list-item","project"))) {
            if (id!=null) invalid=true;
            id=String.valueOf(attrs.getAttribute("data-id")); rowDepth=depth; name.setLength(0);
            if (!id.matches("[1-9][0-9]{0,19}") || !ids.add(id)) invalid=true;
        }
        if (id!=null && tag==HTML.Tag.A && classes.contains("name")) {
            if (nameDepth>=0 || !name.isEmpty()) invalid=true;
            nameDepth=depth;
            try {
                URI link=URI.create(String.valueOf(attrs.getAttribute(HTML.Attribute.HREF)));
                if (link.isAbsolute() && !("https".equals(link.getScheme()) && "dikidi.ru".equals(link.getHost()))
                        || link.getRawAuthority()!=null && !link.isAbsolute() || link.getUserInfo()!=null || link.getPort()!=-1
                        || !"/ru/owner/".equals(link.getRawPath()) || !("company="+id).equals(link.getRawQuery()) || link.getRawFragment()!=null) invalid=true;
            } catch (IllegalArgumentException error) { invalid=true; }
        }
    }
    @Override public void handleText(char[] value,int pos) {
        if (nameDepth>=0) { name.append(value); if (name.length()>4096) invalid=true; }
        if (emptyHeadingDepth>=0) { emptyHeading.append(value); if (emptyHeading.length()>200) invalid=true; }
    }
    @Override public void handleEndTag(HTML.Tag tag,int pos) {
        if (depth==nameDepth) nameDepth=-1;
        if (depth==emptyHeadingDepth) emptyHeadingDepth=-1;
        if (depth==emptyDepth) { if (!source.regionMatches(true,pos,"</div",0,5)) invalid=true; emptyDepth=-1; }
        if (depth==rowDepth) {
            if (!source.regionMatches(true,pos,"</div",0,5)) invalid=true;
            String title=name.toString().strip().replaceAll("\\s+"," ");
            if (title.isBlank() || nameDepth>=0) invalid=true;
            projects.add(new CrmProjectInfo(id,title,null,null)); id=null; rowDepth=-1;
        }
        depth--;
    }
    private static CrmFailure invalid() { return new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); }
}

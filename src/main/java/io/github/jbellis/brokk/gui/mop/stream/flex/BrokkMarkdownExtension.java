package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.DataKey;
import com.vladsch.flexmark.util.data.MutableDataHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Flexmark extension for Brokk-specific Markdown syntax.
 * Registers custom block parsers for edit blocks and code fences.
 */
public class BrokkMarkdownExtension implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    
    /**
     * DataKey to control whether edit blocks should be enabled.
     * Default is true (enabled).
     */
    public static final DataKey<Boolean> ENABLE_EDIT_BLOCK = 
            new DataKey<>("BROKK_ENABLE_EDIT_BLOCK", Boolean.TRUE);
    
    private BrokkMarkdownExtension() {
    }
    
    public static BrokkMarkdownExtension create() {
        return new BrokkMarkdownExtension();
    }

    @Override
    public void extend(Parser.Builder parserBuilder) {
        if (parserBuilder.get(ENABLE_EDIT_BLOCK)) {
            parserBuilder.customBlockParserFactory(new EditBlockParser.Factory());
        }
    }

    @Override
    public void extend(HtmlRenderer.Builder rendererBuilder, String rendererType) {
        if (rendererBuilder.get(ENABLE_EDIT_BLOCK)) {
            rendererBuilder.nodeRendererFactory(new EditBlockRenderer.Factory());
            rendererBuilder.nodeRendererFactory(new RawHtmlWhitelistRenderer.Factory());
        }
        rendererBuilder.nodeRendererFactory(new CodeFenceRenderer.Factory());
    }

    @Override
    public void parserOptions(MutableDataHolder options) {
        // No parser options to configure
    }

    @Override
    public void rendererOptions(MutableDataHolder options) {
        // No renderer options to configure
    }
}

package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.MessageBubble;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.io.IOException;
import io.github.jbellis.brokk.gui.mop.util.ComponentUtils;
import java.util.List;

/**
 * Represents a code fence block with syntax highlighting.
 */
public record CodeBlockComponentData(int id, String body, String lang) implements ComponentData {

    private static final Logger logger = LogManager.getLogger(CodeBlockComponentData.class);
    
    @Override
    public String fp() {
        return body.length() + ":" + body.hashCode();
    }

    /**
     * Creates an RSyntaxTextArea for a code block, setting the syntax style and theme.
     */
    private RSyntaxTextArea createConfiguredCodeArea(String fenceInfo, String content, boolean isDarkTheme) {
        var codeArea = new RSyntaxTextArea(content);
        codeArea.setEditable(false);
        codeArea.setLineWrap(true);
        codeArea.setWrapStyleWord(true);
        DefaultCaret caret = (DefaultCaret) codeArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        codeArea.setSyntaxEditingStyle(getSyntaxStyle(fenceInfo));
        codeArea.setHighlightCurrentLine(false);

        try {
            if (isDarkTheme) {
                var darkTheme = Theme.load(CodeBlockComponentData.class.getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
                darkTheme.apply(codeArea);
            } else {
                var lightTheme = Theme.load(CodeBlockComponentData.class.getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/default.xml"));
                lightTheme.apply(codeArea);
            }
        } catch (IOException e) {
            logger.error("Failed to load theme", e);
        }

        return codeArea;
    }
    
    @Override
    public JComponent createComponent(boolean darkTheme) {
        var textArea = createConfiguredCodeArea(lang, body, darkTheme);
        return createMessageBubble(textArea, lang, darkTheme);
    }
    
    @Override
    public void updateComponent(JComponent component) {
        // Find the RSyntaxTextArea within the panel
        var textAreas = ComponentUtils.findComponentsOfType(component, org.fife.ui.rsyntaxtextarea.RSyntaxTextArea.class);
        if (!textAreas.isEmpty()) {
            var textArea = textAreas.getFirst();
            // Record caret position
            textArea.setText(body);
            // Set syntax style if changed
            textArea.setSyntaxEditingStyle(getSyntaxStyle(lang));
        }
    }

    /**
     * Creates a code block panel using BaseChatMessagePanel.
     * This provides consistent styling with other message components.
     *
     * @param textArea    The RSyntaxTextArea containing the code
     * @param fenceInfo   The language identifier from the code fence
     * @param isDarkTheme Whether dark theme is active
     * @return A panel containing the styled code block
     */
    private JPanel createMessageBubble(RSyntaxTextArea textArea, String fenceInfo, boolean isDarkTheme) {
        // Format the title based on fence info
        String title = fenceInfo.isEmpty() ? "Code" :
                       fenceInfo.substring(0, 1).toUpperCase() + fenceInfo.substring(1);

        // Use code icon
        String iconText = "\uD83D\uDCDD"; // Unicode for memo/pencil emoji

        // Create the panel using BaseChatMessagePanel
        return new MessageBubble(
                title,
                iconText,
                textArea,
                isDarkTheme,
                ThemeColors.getColor(isDarkTheme, "codeHighlight"),
                ThemeColors.getColor(isDarkTheme, "rsyntax_background"),
                ThemeColors.getColor(isDarkTheme, "message_background")
        );
    }
    
    /**
     * Gets the appropriate syntax style constant for a language.
     */
    private String getSyntaxStyle(String lang) {
        if (lang == null || lang.isEmpty()) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }

        // Map common markdown language identifiers (lowercase) to RSyntaxTextArea style constants
        return switch(lang.toLowerCase()) {
            // Existing + common aliases
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python", "py" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript", "js" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "json" -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case "html" -> SyntaxConstants.SYNTAX_STYLE_HTML;
            case "xml" -> SyntaxConstants.SYNTAX_STYLE_XML;
            case "css" -> SyntaxConstants.SYNTAX_STYLE_CSS;
            case "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL;
            case "bash", "sh", "shell", "zsh", "unix" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL; // Expanded aliases

            // Added based on SyntaxConstants
            case "actionscript" -> SyntaxConstants.SYNTAX_STYLE_ACTIONSCRIPT;
            case "asm", "asm-x86", "x86asm" -> SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86;
            case "asm6502", "6502asm" -> SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_6502;
            case "bbcode" -> SyntaxConstants.SYNTAX_STYLE_BBCODE;
            case "c" -> SyntaxConstants.SYNTAX_STYLE_C;
            case "clojure", "clj" -> SyntaxConstants.SYNTAX_STYLE_CLOJURE;
            case "c++", "cpp" -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
            case "c#", "csharp", "cs" -> SyntaxConstants.SYNTAX_STYLE_CSHARP;
            case "csv" -> SyntaxConstants.SYNTAX_STYLE_CSV;
            case "d" -> SyntaxConstants.SYNTAX_STYLE_D;
            case "dockerfile", "docker" -> SyntaxConstants.SYNTAX_STYLE_DOCKERFILE;
            case "dart" -> SyntaxConstants.SYNTAX_STYLE_DART;
            case "delphi", "pascal", "pas" -> SyntaxConstants.SYNTAX_STYLE_DELPHI;
            case "dtd" -> SyntaxConstants.SYNTAX_STYLE_DTD;
            case "fortran", "f", "f90" -> SyntaxConstants.SYNTAX_STYLE_FORTRAN;
            case "go", "golang" -> SyntaxConstants.SYNTAX_STYLE_GO;
            case "groovy" -> SyntaxConstants.SYNTAX_STYLE_GROOVY;
            case "handlebars", "hbs" -> SyntaxConstants.SYNTAX_STYLE_HANDLEBARS;
            case "hosts" -> SyntaxConstants.SYNTAX_STYLE_HOSTS;
            case "htaccess" -> SyntaxConstants.SYNTAX_STYLE_HTACCESS;
            case "ini" -> SyntaxConstants.SYNTAX_STYLE_INI;
            case "jsonc", "jshintrc" -> SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS; // JSON with comments
            case "jsp" -> SyntaxConstants.SYNTAX_STYLE_JSP;
            case "kotlin", "kt" -> SyntaxConstants.SYNTAX_STYLE_KOTLIN;
            case "latex", "tex" -> SyntaxConstants.SYNTAX_STYLE_LATEX;
            case "less" -> SyntaxConstants.SYNTAX_STYLE_LESS;
            case "lisp" -> SyntaxConstants.SYNTAX_STYLE_LISP;
            case "lua" -> SyntaxConstants.SYNTAX_STYLE_LUA;
            case "makefile", "make" -> SyntaxConstants.SYNTAX_STYLE_MAKEFILE;
            case "markdown", "md" -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            case "mxml" -> SyntaxConstants.SYNTAX_STYLE_MXML;
            case "nsis", "nsi" -> SyntaxConstants.SYNTAX_STYLE_NSIS;
            case "perl", "pl" -> SyntaxConstants.SYNTAX_STYLE_PERL;
            case "php" -> SyntaxConstants.SYNTAX_STYLE_PHP;
            case "proto", "protobuf" -> SyntaxConstants.SYNTAX_STYLE_PROTO;
            case "properties" -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
            case "ruby", "rb" -> SyntaxConstants.SYNTAX_STYLE_RUBY;
            case "rust", "rs" -> SyntaxConstants.SYNTAX_STYLE_RUST;
            case "sas" -> SyntaxConstants.SYNTAX_STYLE_SAS;
            case "scala" -> SyntaxConstants.SYNTAX_STYLE_SCALA;
            case "tcl" -> SyntaxConstants.SYNTAX_STYLE_TCL;
            case "typescript", "ts" -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
            case "vb", "vb.net", "visualbasic" -> SyntaxConstants.SYNTAX_STYLE_VISUAL_BASIC;
            case "bat", "cmd", "batch" -> SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH;
            case "yaml", "yml" -> SyntaxConstants.SYNTAX_STYLE_YAML;

            // Default case for unmatched or empty language strings
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }

}

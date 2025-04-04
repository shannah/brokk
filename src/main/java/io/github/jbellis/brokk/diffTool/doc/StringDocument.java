package io.github.jbellis.brokk.diffTool.doc;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;

public class StringDocument extends AbstractBufferDocument {
    private String content;
    
    public StringDocument(String content, String name) {
        this.content = (content != null) ? content : "";
        setName(name); // Set a default name
        setShortName(name);
    }

    @Override
    public int getBufferSize() {
        return content.length();
    }

    @Override
    public Reader getReader() {
        return new StringReader(content);
    }

    @Override
    public Writer getWriter() {
        return new StringWriter() {
            @Override
            public void close() {
                content = this.toString(); // Store written data back into content
            }
        };
    }

    public String getContent() {
        return content;
    }

    public void setContent(String newContent) {
        this.content = (newContent != null) ? newContent : "";
        reset(); // Reset internal data structures
    }

    @Override
    public String toString() {
        return "StringDocument[name=" + getName() + ", content=" + content + "]";
    }
}

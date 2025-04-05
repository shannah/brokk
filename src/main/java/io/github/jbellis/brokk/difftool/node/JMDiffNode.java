package io.github.jbellis.brokk.difftool.node;

import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;

/**
 * Represents the pair of inputs for a diff operation.
 * Holds the left and right documents and a descriptive name.
 */
public class JMDiffNode {
    private final String name;
    private BufferDocumentIF documentLeft;
    private BufferDocumentIF documentRight;

    public JMDiffNode(String name, BufferDocumentIF documentLeft, BufferDocumentIF documentRight) {
        this.name = name;
        this.documentLeft = documentLeft;
        this.documentRight = documentRight; 
    }

    public void setDocumentLeft(BufferDocumentIF documentLeft) {
        this.documentLeft = documentLeft;
    }

    public BufferDocumentIF getDocumentLeft() {
        return documentLeft;
    }

    public void setDocumentRight(BufferDocumentIF documentRight) {
        this.documentRight = documentRight;
    }

    public BufferDocumentIF getDocumentRight() {
        return documentRight;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}

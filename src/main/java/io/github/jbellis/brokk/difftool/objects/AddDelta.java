package io.github.jbellis.brokk.difftool.objects;


import io.github.jbellis.brokk.difftool.diff.Diff;

/**
 * Holds an add-delta between to revisions of a text.
 */
public class AddDelta
        extends Delta {

    public void toString(StringBuffer s) {
        s.append(original.anchor());
        s.append("a");
        s.append(revised.rangeString());
        s.append(Diff.NL);
        revised.toString(s, "> ", Diff.NL);
    }

}

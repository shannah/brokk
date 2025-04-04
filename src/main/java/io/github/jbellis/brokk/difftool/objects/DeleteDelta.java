
package io.github.jbellis.brokk.difftool.objects;


import io.github.jbellis.brokk.difftool.diff.Diff;

/**
 * Holds a delete-delta between to revisions of a text.

 */
public class DeleteDelta
        extends Delta {

    public DeleteDelta(Chunk orig) {
        init(orig, null);
    }

    public void toString(StringBuffer s) {
        s.append(original.rangeString());
        s.append("d");
        s.append(revised.rcsto());
        s.append(Diff.NL);
        original.toString(s, "< ", Diff.NL);
    }
}

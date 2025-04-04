
package io.github.jbellis.brokk.diffTool.doc;


import com.ibm.icu.text.CharsetMatch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Map;

public class FileDocument
        extends AbstractBufferDocument {
    // instance variables:
    private final File file;
    private Charset charset;
    private final Map<String, Charset> charsetMap;

    public FileDocument(File file, String name) {
        this.file = file;
        charsetMap = Charset.availableCharsets();
        try {
            setName(name);
        } catch (Exception ex) {
            ex.printStackTrace();
            setName(name);
        }

        setShortName(file.getName());
    }

    public int getBufferSize() {
        return (int) file.length();
    }


    public Reader getReader() {
        BufferedInputStream bis;

        try {
            // Try to create a reader that has the right charset.
            // If you use new FileReader(file) you get a reader
            //   with the default charset.
            bis = new BufferedInputStream(new FileInputStream(file));

            charset = detectCharset(bis);
            return new BufferedReader(new InputStreamReader(bis, charset));
        } catch (Exception ex) {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream("".getBytes())));
        }
    }


    private Charset detectCharset(BufferedInputStream bis) {
        try {
            com.ibm.icu.text.CharsetDetector detector;
            CharsetMatch match;
            Charset foundCharset;

            detector = new com.ibm.icu.text.CharsetDetector();
            detector.setText(bis);

            match = detector.detect();
            if (match != null) {
                foundCharset = charsetMap.get(match.getName());
                if (foundCharset != null) {
                    return foundCharset;
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return null;
    }

    public Writer getWriter() throws Exception {
        BufferedOutputStream bos;

        try {
            bos = new BufferedOutputStream(new FileOutputStream(file));
            return new BufferedWriter(new OutputStreamWriter(bos, charset));
        } catch (IOException ex) {
            throw new Exception("Cannot create FileWriter for file: "
                                        + file.getName(), ex);
        }
    }
}

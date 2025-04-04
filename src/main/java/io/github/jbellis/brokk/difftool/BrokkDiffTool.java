package io.github.jbellis.brokk.difftool;

import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.JMHighlightPainter;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Objects;

public class BrokkDiffTool
        implements Runnable {

    public BrokkDiffTool() {
    }

    public void run() {
        var leftSource = """
                public class AccountManager {
                    private String name;
                    private double outstanding;
                    private int unused;
                
                    void printOwing() {
                        // Print banner
                        System.out.println("Details of account");
                        System.out.println("-----");
                        System.out.println("");
                
                        // Print details
                        System.out.println("name: " + name);
                        System.out.println("amount: " + outstanding);
                    }
                }
                """.stripIndent();

        var rightSource = """
                public class AccountManager {
                    private String name;
                    private double outstanding;
                
                    void printOwing() {
                        // Print banner
                        System.out.println("***");
                        System.out.println("");
                
                        // Print details
                        printDetails();
                    }
                
                    void printDetails() {
                        System.out.println("name: " + name);
                        System.out.println("amount: " + outstanding);
                    }
                }
                """.stripIndent();

        var gplHacked = """
                 Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
                 Everyone is permitted to copy and distribute verbatim copies
                 of this license document, but changing it is not allowed.
                
                Jonathan's version is much better than Richard's.
                """;

        JMHighlightPainter.initializePainters();
        JFrame frame = new JFrame("BrokkDiffTool");
        // Creating a new BrokkDiffPanel instance for file comparison mode.
        BrokkDiffPanel brokkPanel = new BrokkDiffPanel.Builder()
                //if you want to compare two files
//                .compareFiles(new File("NOTICE.txt"),"left-source",new File("LICENSE.txt"),"right-source")
                //if you want to compare a file and String
                .compareStringAndFile(gplHacked, "left-source", new File("LICENSE.txt"), "right-source")
//                .compareStringAndFileStringOnTheRight( new File("LICENSE.txt"), "right-source",gplHacked, "left-source")
                .build();
//        BrokkDiffPanel brokkPanel = new BrokkDiffPanel(
//                false,  // Enable file comparison mode
//                "", leftSource, // Titles for the left and right content (empty in this case)
//                "", rightSource, // Content for direct text comparison (not used here)
//                null,  // Left sample file to compare
//                null   // Right sample file to compare
//        );
        frame.add(brokkPanel);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImage(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/compare.png"))).getImage());
        frame.setSize(900, 600); // Initial size
        frame.setMinimumSize(new Dimension(800, 600)); // Enforce minimum size
        frame.setVisible(true);
        frame.toFront();
    }

    public static void main2(String[] args) {
        SwingUtilities.invokeLater(new BrokkDiffTool());
    }
}
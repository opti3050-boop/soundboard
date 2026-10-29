package com.soundboard;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A read-only HTML documentation panel shown as the first (pinned) tab.
 * It cannot be deleted or renamed through the normal tab controls.
 *
 * <p>The page content (everything inside the HTML body) is NOT hardcoded: it is
 * loaded from a text file named {@value #HELP_FILE}. The look (CSS) stays in this
 * class. The file is searched for in this order:
 * <ol>
 *   <li>{@code ./help.html} in the directory the app is started from — handy for
 *       editing the help text without rebuilding;</li>
 *   <li>{@code ~/.soundboard/help.html};</li>
 *   <li>the classpath, i.e. {@code src/main/resources/help.html}, which Maven
 *       packs into the JAR.</li>
 * </ol>
 */
public class HelpPanel extends JPanel {

    public static final String TAB_TITLE = "📖 Help";

    /** Name of the file holding the help body (an HTML fragment, UTF-8). */
    public static final String HELP_FILE = "help.html";

    public HelpPanel() {
        super(new BorderLayout());

        JEditorPane editor = new JEditorPane("text/html", buildHtml());
        editor.setEditable(false);
        editor.setBackground(new Color(0xfafafa));
        editor.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                // Internal anchor navigation (href="#section") is handled
                // automatically by the HTML renderer; only external URLs need
                // explicit handling here, but we have none right now.
            }
        });
        // Ensure the document starts scrolled to the top
        SwingUtilities.invokeLater(() -> editor.setCaretPosition(0));

        JScrollPane scroll = new JScrollPane(editor);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(scroll, BorderLayout.CENTER);
    }

    // ── HTML content ──────────────────────────────────────────────────────────

    /** Full page = styling (below) + body loaded from the help file. */
    private static String buildHtml() {
        return buildHead() + loadBody() + "</body></html>";
    }

    /**
     * Reads the help body from {@value #HELP_FILE} (see the class comment for the
     * search order). Returns a short error page body if no file can be found/read,
     * so a missing file never breaks the app.
     */
    private static String loadBody() {
        String problem = null;

        // 1 + 2: external files (editable without rebuilding)
        Path[] candidates = {
            Paths.get(HELP_FILE),
            Paths.get(System.getProperty("user.home", "."), ".soundboard", HELP_FILE)
        };
        for (Path p : candidates) {
			AppLogger.info("HelpPanel local file: " + p.toString());			
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    problem = "Could not read " + p.toAbsolutePath() + ": " + ex.getMessage();
                    AppLogger.info("HelpPanel: " + problem);
                }
            }
        }

        // 3: bundled with the application (src/main/resources/help.html)
        try (InputStream in = HelpPanel.class.getResourceAsStream("/" + HELP_FILE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            problem = "Could not read bundled " + HELP_FILE + ": " + ex.getMessage();
            AppLogger.info("HelpPanel: " + problem);
        }

        if (problem == null) {
            problem = "The help file " + HELP_FILE + " was not found.";
        }
        return "<h1>Help unavailable</h1><p>" + escape(problem) + "</p>"
             + "<p>Place <code>" + HELP_FILE + "</code> next to the application, in "
             + "<code>~/.soundboard/</code>, or in <code>src/main/resources/</code> "
             + "and rebuild.</p>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Document head with the stylesheet used by the help page. */
    private static String buildHead() {
        return
            "<!DOCTYPE html><html><head><style>" +
        "  body   { font-family: Arial, sans-serif; font-size: 13px;" +
        "           color: #1a1a1a; margin: 0; padding: 24px 36px 40px 36px;" +
        "           line-height: 1.65; background: #fafafa; }" +

        // headings
        "  h1     { color: #1a3a5c; border-bottom: 3px solid #1a3a5c;" +
        "           padding-bottom: 8px; margin-top: 0; margin-bottom: 6px;" +
        "           font-size: 20px; }" +
        "  h2     { color: #1a3a5c; margin-top: 32px; margin-bottom: 6px;" +
        "           font-size: 15px; border-bottom: 1px solid #b8d0e8;" +
        "           padding-bottom: 4px; }" +
        "  h3     { color: #1f5580; margin-top: 16px; margin-bottom: 4px;" +
        "           font-size: 13px; }" +

        // table of contents
        "  .toc   { background: #eef5fb; border: 1px solid #b8d0e8;" +
        "           border-radius: 4px; padding: 12px 20px; margin: 14px 0 28px 0;" +
        "           display: inline-block; min-width: 260px; }" +
        "  .toc p { margin: 0 0 6px 0; font-weight: bold; color: #1a3a5c; }" +
        "  .toc ol { margin: 0; padding-left: 20px; }" +
        "  .toc li { margin-bottom: 3px; }" +
        "  .toc a { color: #1f5580; text-decoration: none; }" +
        "  .toc a:hover { text-decoration: underline; }" +

        // tables
        "  table  { border-collapse: collapse; width: 100%; margin: 8px 0 14px 0; }" +
        "  th     { background: #1f5580; color: #fff; padding: 7px 11px;" +
        "           text-align: left; font-size: 12px; font-weight: bold; }" +
        "  td     { border: 1px solid #c8dff0; padding: 6px 11px;" +
        "           vertical-align: top; }" +
        "  tr:nth-child(even) td { background: #f0f7fc; }" +

        // lists
        "  ul, ol { margin: 6px 0 10px 0; padding-left: 22px; }" +
        "  ul li, ol li { margin-bottom: 5px; }" +

        // inline elements
        "  code   { background: #e8ecf0; padding: 1px 5px; border-radius: 3px;" +
        "           font-family: 'Courier New', Courier, monospace; font-size: 11.5px; }" +
        "  kbd    { background: #f0f0f0; border: 1px solid #b0b0b0;" +
        "           border-bottom: 2px solid #909090; border-radius: 3px;" +
        "           padding: 1px 6px; font-size: 11px;" +
        "           font-family: Arial, sans-serif; }" +
        "  strong { color: #111; }" +

        // callout boxes
        "  .note  { background: #fffbea; border-left: 4px solid #e6b800;" +
        "           padding: 9px 14px; margin: 12px 0; border-radius: 2px;" +
        "           font-size: 12.5px; }" +
        "  .tip   { background: #edfaf3; border-left: 4px solid #27ae60;" +
        "           padding: 9px 14px; margin: 12px 0; border-radius: 2px;" +
        "           font-size: 12.5px; }" +
        "  .warn  { background: #fef0ed; border-left: 4px solid #e05c3a;" +
        "           padding: 9px 14px; margin: 12px 0; border-radius: 2px;" +
        "           font-size: 12.5px; }" +

        // section divider
        "  hr     { border: none; border-top: 1px solid #d0dce8; margin: 28px 0; }" +
        "</style></head><body>";
    }
}

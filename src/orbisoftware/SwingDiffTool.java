/*
 * SwingDiffTool.java
 *
 * Dependency:
 *   io.github.java-diff-utils:java-diff-utils:4.15
 */

package orbisoftware;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class SwingDiffTool extends JFrame {

    // ---- Ribbon actions ----
    private final Action diffAction = new AbstractAction("Diff", new DiffIcon()) {
        @Override public void actionPerformed(ActionEvent e) {
            final int lTop = getTopVisibleLine(leftArea, leftScroll);
            final int rTop = getTopVisibleLine(rightArea, rightScroll);

            resetAndRecomputeDiffFromScratch();

            restoreTopLinesLater(lTop, rTop);
        }
    };

    private final Action saveLeftAction = new AbstractAction("Save Left", new SaveArrowIcon(true)) {
        @Override public void actionPerformed(ActionEvent e) { saveSide(true); }
    };
    private final Action saveRightAction = new AbstractAction("Save Right", new SaveArrowIcon(false)) {
        @Override public void actionPerformed(ActionEvent e) { saveSide(false); }
    };
    private final Action undoAction = new AbstractAction("Undo", new UndoIcon()) {
        @Override public void actionPerformed(ActionEvent e) { undoIn(lastFocusedSide); }
    };

    // ---- Per-side path controls (above each text area) ----
    private final JTextField leftPathField = new JTextField();
    private final JTextField rightPathField = new JTextField();
    private final JButton leftBrowseBtn = new JButton("Browse...");
    private final JButton rightBrowseBtn = new JButton("Browse...");

    // ---- Editors (plain text) ----
    private final JTextArea leftArea = new JTextArea();
    private final JTextArea rightArea = new JTextArea();
    private final JScrollPane leftScroll = new JScrollPane(leftArea);
    private final JScrollPane rightScroll = new JScrollPane(rightArea);

    private final Gutter gutter = new Gutter();

    // ---- Highlighting ----
    private final Color DIFF_HL = new Color(173, 216, 230); // light blue
    private final Highlighter.HighlightPainter diffPainter =
            new DefaultHighlighter.DefaultHighlightPainter(DIFF_HL);

    // ---- Model ----
    private Path leftPath;
    private Path rightPath;
    private final Charset charset = StandardCharsets.UTF_8;

    private Patch<String> patch;
    private final java.util.List<ChunkInfo> chunks = new ArrayList<>();

    private int[] leftLineStartOffsets = new int[]{0};
    private int[] rightLineStartOffsets = new int[]{0};

    // Scroll-lock control
    private boolean adjustingScroll = false;

    // Undo (recreated on Diff reset)
    private UndoManager leftUndo = new UndoManager();
    private UndoManager rightUndo = new UndoManager();
    private UndoableEditListener leftUndoListener;
    private UndoableEditListener rightUndoListener;

    private Side lastFocusedSide = Side.LEFT;
    private enum Side { LEFT, RIGHT }

    // Dismissed triangles / suppress re-highlighting for those chunk ids (stable)
    private final Set<String> dismissedChunkIds = new HashSet<>();

    // Debounced auto-diff (no-op)
    private final Timer debounceTimer;
    private boolean suppressAutoDiff = false;

    // Listener instances to reattach after document replacement
    private DocumentListener sharedDocListener;

    public SwingDiffTool() {
        super("Swing Diff Tool");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1350, 860));

        // Ribbon / toolbar
        JToolBar ribbon = buildRibbon();
        add(ribbon, BorderLayout.NORTH);

        // Path fields read-only
        configurePathField(leftPathField);
        configurePathField(rightPathField);

        // Editors look
        applyEditorLook(leftArea);
        applyEditorLook(rightArea);

        // line numbers
        leftScroll.setRowHeaderView(new LineNumberView(leftArea));
        rightScroll.setRowHeaderView(new LineNumberView(rightArea));

        // focus tracking for undo
        leftArea.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { lastFocusedSide = Side.LEFT; updateUndoEnabled(); }
        });
        rightArea.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { lastFocusedSide = Side.RIGHT; updateUndoEnabled(); }
        });

        // Left panel: header + scroll
        JPanel leftHeader = buildSideHeader(true);
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(leftHeader, BorderLayout.NORTH);
        leftPanel.add(leftScroll, BorderLayout.CENTER);

        // Right panel: header + (gutter + scroll)
        JPanel rightHeader = buildSideHeader(false);
        JPanel rightCenter = new JPanel(new BorderLayout());
        rightCenter.add(gutter, BorderLayout.WEST);
        rightCenter.add(rightScroll, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(rightHeader, BorderLayout.NORTH);
        rightPanel.add(rightCenter, BorderLayout.CENTER);

        // Split
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        split.setLeftComponent(leftPanel);
        split.setRightComponent(rightPanel);
        add(split, BorderLayout.CENTER);

        gutter.setPreferredSize(new Dimension(54, 10));
        gutter.setBackground(new Color(245, 245, 245));

        // Debounce timer: NO diff recompute (per request)
        debounceTimer = new Timer(250, e -> { /* no-op */ });
        debounceTimer.setRepeats(false);

        // Wire
        wireActions();                // browse + gutter click
        wireUndoKeybindings();
        wireUndoManagers();
        wireDocumentListeners();
        wireLockedScrollingAndGutterTracking();

        updateUndoEnabled();
        computeDiffAndRender(); // initial diff at startup (empty buffers)
        setVisible(true);
    }

    private void configurePathField(JTextField tf) {
        tf.setEditable(false);
        tf.setFocusable(false);
        tf.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        tf.setBackground(new Color(245, 245, 245));
    }

    private JToolBar buildRibbon() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(new EmptyBorder(6, 8, 6, 8));
        tb.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));

        JButton diffBtn = new JButton(diffAction);
        diffBtn.setText("");
        diffBtn.setToolTipText("Diff (recompute from current buffers)");

        JButton saveL = new JButton(saveLeftAction);
        saveL.setText("");
        saveL.setToolTipText("Save Left");

        JButton saveR = new JButton(saveRightAction);
        saveR.setText("");
        saveR.setToolTipText("Save Right");

        JButton undo = new JButton(undoAction);
        undo.setText("");
        undo.setToolTipText("Undo (active side)");

        styleRibbonButton(diffBtn);
        styleRibbonButton(saveL);
        styleRibbonButton(saveR);
        styleRibbonButton(undo);

        tb.add(diffBtn);
        tb.addSeparator(new Dimension(10, 1));
        tb.add(saveL);
        tb.add(saveR);
        tb.addSeparator(new Dimension(10, 1));
        tb.add(undo);

        undoAction.setEnabled(false);
        return tb;
    }

    private void styleRibbonButton(AbstractButton b) {
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(42, 34));
        b.setMargin(new Insets(4, 6, 4, 6));
    }

    private JPanel buildSideHeader(boolean left) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBorder(new EmptyBorder(6, 8, 6, 8));

        JTextField tf = left ? leftPathField : rightPathField;
        JButton browse = left ? leftBrowseBtn : rightBrowseBtn;

        p.add(tf, BorderLayout.CENTER);
        p.add(browse, BorderLayout.EAST);

        return p;
    }

    private void applyEditorLook(JTextArea area) {
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setMargin(new Insets(8, 8, 8, 8));
        area.setBackground(Color.WHITE);
        area.setLineWrap(false);
        area.setWrapStyleWord(false);
        area.setTabSize(4);
    }

    // ---------------- Scroll-preserve helpers ----------------

    private int getTopVisibleLine(JTextArea area, JScrollPane sp) {
        try {
            Point vp = sp.getViewport().getViewPosition();
            int model = area.viewToModel2D(new Point(0, vp.y));
            Element root = area.getDocument().getDefaultRootElement();
            return Math.max(0, root.getElementIndex(model));
        } catch (Exception ex) {
            return 0;
        }
    }

    private void restoreTopLinesLater(int leftTopLine, int rightTopLine) {
        SwingUtilities.invokeLater(() -> {
            restoreTopLine(leftArea, leftScroll, leftTopLine);
            restoreTopLine(rightArea, rightScroll, rightTopLine);
            updateGutterMarkers();
        });
    }

    private void restoreTopLine(JTextArea area, JScrollPane sp, int topLine) {
        try {
            Element root = area.getDocument().getDefaultRootElement();
            int maxLine = Math.max(0, root.getElementCount() - 1);
            int line = Math.max(0, Math.min(topLine, maxLine));

            int offset = root.getElement(line).getStartOffset();
            Rectangle r = area.modelToView2D(offset).getBounds();
            Point vp = sp.getViewport().getViewPosition();
            sp.getViewport().setViewPosition(new Point(vp.x, Math.max(0, r.y)));
        } catch (Exception ignored) {}
    }

    // ---------------- Diff Reset (from scratch) ----------------

    private void resetAndRecomputeDiffFromScratch() {
        debounceTimer.stop();
        suppressAutoDiff = true;
        try {
            patch = null;
            chunks.clear();
            dismissedChunkIds.clear();

            gutter.setChunks(List.of());
            gutter.repaint();

            resetDocumentPreserveExact(leftArea);
            resetDocumentPreserveExact(rightArea);

            resetHighlighter(leftArea);
            resetHighlighter(rightArea);

            leftScroll.setRowHeaderView(new LineNumberView(leftArea));
            rightScroll.setRowHeaderView(new LineNumberView(rightArea));

            leftUndo = new UndoManager();
            rightUndo = new UndoManager();
            wireUndoManagers();
            updateUndoEnabled();

            wireDocumentListeners();

            computeDiffAndRender();
        } finally {
            suppressAutoDiff = false;
        }
    }

    private void resetDocumentPreserveExact(JTextArea area) {
        String text;
        try {
            Document old = area.getDocument();
            text = old.getText(0, old.getLength());
        } catch (BadLocationException e) {
            text = area.getText();
        }

        PlainDocument fresh = new PlainDocument();
        area.setDocument(fresh);

        try {
            if (!text.isEmpty()) fresh.insertString(0, text, null);
        } catch (BadLocationException ignored) {}

        applyEditorLook(area);
    }

    private void resetHighlighter(JTextArea area) {
        area.setHighlighter(new DefaultHighlighter());
        area.getHighlighter().removeAllHighlights();
        area.repaint();
    }

    // ---------------- Actions ----------------

    private void wireActions() {
        leftBrowseBtn.addActionListener(e -> chooseFile(true));
        rightBrowseBtn.addActionListener(e -> chooseFile(false));

        gutter.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Gutter.Hit hit = gutter.hitTest(e.getPoint());
                if (hit == null) return;

                final int lTop = getTopVisibleLine(leftArea, leftScroll);
                final int rTop = getTopVisibleLine(rightArea, rightScroll);

                ChunkInfo ci = hit.chunk;
                boolean applyRightToLeft = (hit.which == Gutter.Which.RIGHT_TRI);

                // Dismiss triangle immediately (single-click) by stable id
                dismissedChunkIds.add(ci.id);

                // Apply the chunk to the opposite side, and compute how many lines changed on that side
                ApplyResult ar = applyChunkWithoutRediff(ci, applyRightToLeft);

                // SHIFT remaining highlights/triangles by line delta on edited side
                // (keeps existing diff visualization aligned without recomputing)
                if (ar.lineDelta != 0) {
                    shiftChunksAfterLineEdit(ar.editedSide, ar.oldEndLineExclusive, ar.lineDelta, ci.id);
                }

                // Recompute line offsets for accurate highlighting + marker positioning
                leftLineStartOffsets = computeLineStartOffsets(leftArea.getDocument());
                rightLineStartOffsets = computeLineStartOffsets(rightArea.getDocument());

                // Update visuals (no diff recompute)
                refreshHighlightsOnly();
                updateGutterMarkers();
                updateUndoEnabled();

                restoreTopLinesLater(lTop, rTop);
            }
        });
    }

    private void chooseFile(boolean left) {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setMultiSelectionEnabled(false);

        int res = fc.showOpenDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return;

        Path p = fc.getSelectedFile().toPath();
        if (left) {
            leftPath = p;
            leftPathField.setText(p.toString());
            loadFileInto(leftArea, p);
        } else {
            rightPath = p;
            rightPathField.setText(p.toString());
            loadFileInto(rightArea, p);
        }

        dismissedChunkIds.clear();
        leftUndo.discardAllEdits();
        rightUndo.discardAllEdits();
        updateUndoEnabled();

        computeDiffAndRender();
    }

    private void loadFileInto(JTextArea area, Path p) {
        try {
            String txt = Files.readString(p, charset);
            suppressAutoDiff = true;
            try {
                area.setText(txt);
            } finally {
                suppressAutoDiff = false;
            }
            resetHighlighter(area);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to load:\n" + ex.getMessage(),
                    "Load Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveSide(boolean left) {
        JTextArea area = left ? leftArea : rightArea;
        Path currentPath = left ? leftPath : rightPath;

        if (currentPath == null) {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int res = fc.showSaveDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;
            currentPath = fc.getSelectedFile().toPath();
        }

        if (Files.exists(currentPath)) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Overwrite existing file?\n\n" + currentPath,
                    "Confirm Overwrite",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.YES_OPTION) return;
        } else {
            Path parent = currentPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                int choice = JOptionPane.showConfirmDialog(
                        this,
                        "Directory does not exist:\n\n" + parent + "\n\nCreate it?",
                        "Create Directory",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );
                if (choice != JOptionPane.YES_OPTION) return;
                try {
                    Files.createDirectories(parent);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this,
                            "Failed to create directory:\n" + ex.getMessage(),
                            "Save Error",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        }

        try {
            Files.writeString(currentPath, area.getText(), charset,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            if (left) {
                leftPath = currentPath;
                leftPathField.setText(currentPath.toString());
            } else {
                rightPath = currentPath;
                rightPathField.setText(currentPath.toString());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to save:\n" + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- Document listeners ----------------

    private void wireDocumentListeners() {
        if (sharedDocListener != null) {
            try { leftArea.getDocument().removeDocumentListener(sharedDocListener); } catch (Exception ignored) {}
            try { rightArea.getDocument().removeDocumentListener(sharedDocListener); } catch (Exception ignored) {}
        }

        sharedDocListener = new DocumentListener() {
            private void kick() {
                if (suppressAutoDiff) return;
                debounceTimer.restart(); // no-op timer
            }
            @Override public void insertUpdate(DocumentEvent e) { kick(); }
            @Override public void removeUpdate(DocumentEvent e) { kick(); }
            @Override public void changedUpdate(DocumentEvent e) { kick(); }
        };

        leftArea.getDocument().addDocumentListener(sharedDocListener);
        rightArea.getDocument().addDocumentListener(sharedDocListener);
    }

    // ---------------- Undo wiring ----------------

    private void wireUndoManagers() {
        if (leftUndoListener != null) {
            try { leftArea.getDocument().removeUndoableEditListener(leftUndoListener); } catch (Exception ignored) {}
        }
        if (rightUndoListener != null) {
            try { rightArea.getDocument().removeUndoableEditListener(rightUndoListener); } catch (Exception ignored) {}
        }

        leftUndoListener = e -> { leftUndo.addEdit(e.getEdit()); updateUndoEnabled(); };
        rightUndoListener = e -> { rightUndo.addEdit(e.getEdit()); updateUndoEnabled(); };

        leftArea.getDocument().addUndoableEditListener(leftUndoListener);
        rightArea.getDocument().addUndoableEditListener(rightUndoListener);

        updateUndoEnabled();
    }

    private void wireUndoKeybindings() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        InputMap imLeft = leftArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap amLeft = leftArea.getActionMap();
        imLeft.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "undo-left");
        amLeft.put("undo-left", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { undoIn(Side.LEFT); }
        });

        InputMap imRight = rightArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap amRight = rightArea.getActionMap();
        imRight.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "undo-right");
        amRight.put("undo-right", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { undoIn(Side.RIGHT); }
        });
    }

    private void undoIn(Side side) {
        // No computeDiffAndRender() here (per request).
        try {
            if (side == Side.LEFT) {
                if (leftUndo.canUndo()) leftUndo.undo();
            } else {
                if (rightUndo.canUndo()) rightUndo.undo();
            }
        } catch (Exception ignored) {}
        updateUndoEnabled();
    }

    private void updateUndoEnabled() {
        boolean canUndo = (lastFocusedSide == Side.LEFT) ? leftUndo.canUndo() : rightUndo.canUndo();
        undoAction.setEnabled(canUndo);
    }

    // ---------------- Diff computation & rendering ----------------

    private void computeDiffAndRender() {
        List<String> leftLines = splitLinesPreserveEmptyTrailing(leftArea.getText());
        List<String> rightLines = splitLinesPreserveEmptyTrailing(rightArea.getText());

        patch = DiffUtils.diff(leftLines, rightLines);

        chunks.clear();
        dismissedChunkIds.clear();

        for (AbstractDelta<String> d : patch.getDeltas()) {
            Chunk<String> src = d.getSource();
            Chunk<String> tgt = d.getTarget();

            ChunkInfo ci = new ChunkInfo(
                    d.getType(),
                    src.getPosition(), src.size(),
                    tgt.getPosition(), tgt.size(),
                    src.getLines(),
                    tgt.getLines()
            );
            chunks.add(ci);
        }

        leftLineStartOffsets = computeLineStartOffsets(leftArea.getDocument());
        rightLineStartOffsets = computeLineStartOffsets(rightArea.getDocument());

        refreshHighlightsOnly();
        updateGutterMarkers();
    }

    private void refreshHighlightsOnly() {
        leftArea.getHighlighter().removeAllHighlights();
        rightArea.getHighlighter().removeAllHighlights();

        for (ChunkInfo ci : filterVisibleChunks()) {
            highlightLineRange(leftArea, leftLineStartOffsets, ci.leftPos, ci.leftSize);
            highlightLineRange(rightArea, rightLineStartOffsets, ci.rightPos, ci.rightSize);
        }
    }

    private void highlightLineRange(JTextArea area, int[] starts, int startLine, int lineCount) {
        if (starts.length == 0) return;

        if (lineCount <= 0) {
            int l = Math.max(0, Math.min(startLine, starts.length - 1));
            highlightLineSpan(area, starts, l, 1);
        } else {
            int l0 = Math.max(0, startLine);
            int l1 = Math.min(starts.length - 1, startLine + lineCount - 1);
            highlightLineSpan(area, starts, l0, (l1 - l0) + 1);
        }
    }

    private void highlightLineSpan(JTextArea area, int[] starts, int startLine, int count) {
        try {
            int startOffset = starts[Math.min(startLine, starts.length - 1)];
            int endLine = Math.min(starts.length - 1, startLine + count);
            int endOffset = (endLine < starts.length) ? starts[endLine] : area.getDocument().getLength();
            if (endOffset < startOffset) endOffset = startOffset;
            area.getHighlighter().addHighlight(startOffset, endOffset, diffPainter);
        } catch (BadLocationException ignored) {}
    }

    private void updateGutterMarkers() {
        gutter.setChunks(filterVisibleChunks());
        gutter.recomputeMarkers(leftArea, rightArea, leftScroll, rightScroll, leftLineStartOffsets, rightLineStartOffsets);
        gutter.repaint();
    }

    private List<ChunkInfo> filterVisibleChunks() {
        if (dismissedChunkIds.isEmpty()) return new ArrayList<>(chunks);
        ArrayList<ChunkInfo> out = new ArrayList<>(chunks.size());
        for (ChunkInfo ci : chunks) {
            if (!dismissedChunkIds.contains(ci.id)) out.add(ci);
        }
        return out;
    }

    // ---------------- Apply chunk + shift remaining chunks ----------------

    private static class ApplyResult {
        final Side editedSide;
        final int oldEndLineExclusive; // end of replaced region in the *old* line coordinates
        final int lineDelta;           // insertedLines - removedLines on edited side
        ApplyResult(Side editedSide, int oldEndLineExclusive, int lineDelta) {
            this.editedSide = editedSide;
            this.oldEndLineExclusive = oldEndLineExclusive;
            this.lineDelta = lineDelta;
        }
    }

    private ApplyResult applyChunkWithoutRediff(ChunkInfo ci, boolean applyRightToLeft) {
        // applyRightToLeft: edit LEFT side by copying RIGHT chunk content
        JTextArea targetArea = applyRightToLeft ? leftArea : rightArea;
        Side editedSide = applyRightToLeft ? Side.LEFT : Side.RIGHT;

        List<String> insertedLines = applyRightToLeft ? ci.rightLines : ci.leftLines;

        int targetPos = applyRightToLeft ? ci.leftPos : ci.rightPos;
        int removedLines = applyRightToLeft ? ci.leftSize : ci.rightSize;

        int oldEndLineExclusive = targetPos + Math.max(removedLines, 0);
        int lineDelta = insertedLines.size() - Math.max(removedLines, 0);

        int[] starts = applyRightToLeft ? leftLineStartOffsets : rightLineStartOffsets;
        Document doc = targetArea.getDocument();

        int startOffset = offsetForLineSafe(starts, doc.getLength(), targetPos);
        int endOffset = offsetForLineSafe(starts, doc.getLength(), oldEndLineExclusive);

        String replacement = String.join("\n", insertedLines);

        boolean insertAddsLines = !insertedLines.isEmpty();
        if (insertAddsLines) {
            boolean docEndsWithNl = targetArea.getText().endsWith("\n");
            boolean insertingAtEnd = (startOffset == doc.getLength());
            if (!insertingAtEnd || docEndsWithNl) replacement = replacement + "\n";
        }

        suppressAutoDiff = true;
        try {
            if (endOffset > startOffset) doc.remove(startOffset, endOffset - startOffset);
            if (!replacement.isEmpty()) doc.insertString(startOffset, replacement, null);
        } catch (BadLocationException ignored) {
        } finally {
            suppressAutoDiff = false;
        }

        return new ApplyResult(editedSide, oldEndLineExclusive, lineDelta);
    }

    /**
     * Shift chunk positions on the edited side for all chunks that start at/after the edit end line.
     * This keeps highlights/triangles aligned without recomputing the diff.
     */
    private void shiftChunksAfterLineEdit(Side editedSide, int oldEndLineExclusive, int lineDelta, String clickedChunkId) {
        if (lineDelta == 0) return;

        for (ChunkInfo c : chunks) {
            if (c.id.equals(clickedChunkId)) continue; // clicked chunk is dismissed anyway
            if (editedSide == Side.LEFT) {
                if (c.leftPos >= oldEndLineExclusive) c.leftPos += lineDelta;
            } else {
                if (c.rightPos >= oldEndLineExclusive) c.rightPos += lineDelta;
            }
        }
    }

    // ---------------- Locked scrolling + gutter tracking ----------------

    private void wireLockedScrollingAndGutterTracking() {
        JScrollBar lbar = leftScroll.getVerticalScrollBar();
        JScrollBar rbar = rightScroll.getVerticalScrollBar();

        AdjustmentListener lockLeft = e -> {
            if (adjustingScroll) return;
            adjustingScroll = true;
            try {
                syncScrollbarsProportional(lbar, rbar);
                updateGutterMarkers();
            } finally {
                adjustingScroll = false;
            }
        };

        AdjustmentListener lockRight = e -> {
            if (adjustingScroll) return;
            adjustingScroll = true;
            try {
                syncScrollbarsProportional(rbar, lbar);
                updateGutterMarkers();
            } finally {
                adjustingScroll = false;
            }
        };

        lbar.addAdjustmentListener(lockLeft);
        rbar.addAdjustmentListener(lockRight);

        ChangeListener viewportListener = e -> {
            if (adjustingScroll) return;
            updateGutterMarkers();
        };
        leftScroll.getViewport().addChangeListener(viewportListener);
        rightScroll.getViewport().addChangeListener(viewportListener);

        gutter.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { updateGutterMarkers(); }
        });
    }

    private void syncScrollbarsProportional(JScrollBar from, JScrollBar to) {
        int fromMaxScrollable = Math.max(1, from.getMaximum() - from.getVisibleAmount());
        int toMaxScrollable = Math.max(1, to.getMaximum() - to.getVisibleAmount());

        double frac = clamp01(from.getValue() / (double) fromMaxScrollable);
        int target = (int) Math.round(frac * toMaxScrollable);
        to.setValue(target);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    // ---------------- Helpers ----------------

    private static List<String> splitLinesPreserveEmptyTrailing(String text) {
        String[] parts = text.split("\n", -1);
        return new ArrayList<>(Arrays.asList(parts));
    }

    private int[] computeLineStartOffsets(Document doc) {
        try {
            String text = doc.getText(0, doc.getLength());
            IntArrayList starts = new IntArrayList(Math.max(8, text.length() / 40));
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    int next = i + 1;
                    if (next <= text.length()) starts.add(next);
                }
            }
            return starts.toArray();
        } catch (BadLocationException e) {
            return new int[]{0};
        }
    }

    private static int offsetForLineSafe(int[] starts, int docLen, int line) {
        if (starts == null || starts.length == 0) return 0;
        if (line <= 0) return 0;
        if (line >= starts.length) return docLen;
        return Math.max(0, Math.min(starts[line], docLen));
    }

    // ---------------- Data Types ----------------

    private static class ChunkInfo {
        final String id = UUID.randomUUID().toString();
        final DeltaType type;

        int leftPos, leftSize;
        int rightPos, rightSize;

        final List<String> leftLines;
        final List<String> rightLines;

        Rectangle leftTriBounds = new Rectangle();
        Rectangle rightTriBounds = new Rectangle();

        ChunkInfo(DeltaType type,
                  int leftPos, int leftSize,
                  int rightPos, int rightSize,
                  List<String> leftLines,
                  List<String> rightLines) {
            this.type = type;
            this.leftPos = leftPos;
            this.leftSize = leftSize;
            this.rightPos = rightPos;
            this.rightSize = rightSize;
            this.leftLines = new ArrayList<>(leftLines);
            this.rightLines = new ArrayList<>(rightLines);
        }
    }

    // ---------------- Gutter ----------------

    private static class Gutter extends JComponent {
        private java.util.List<ChunkInfo> chunks = List.of();
        private static final int TRI_TOP_NUDGE_PX = 2;

        enum Which { LEFT_TRI, RIGHT_TRI }

        static class Hit {
            final ChunkInfo chunk;
            final Which which;
            Hit(ChunkInfo chunk, Which which) { this.chunk = chunk; this.which = which; }
        }

        void setChunks(java.util.List<ChunkInfo> chunks) {
            this.chunks = (chunks != null) ? chunks : List.of();
        }

        Hit hitTest(Point p) {
            for (ChunkInfo ci : chunks) {
                if (ci.leftTriBounds.contains(p)) return new Hit(ci, Which.LEFT_TRI);
                if (ci.rightTriBounds.contains(p)) return new Hit(ci, Which.RIGHT_TRI);
            }
            return null;
        }

        void recomputeMarkers(
                JTextArea leftArea,
                JTextArea rightArea,
                JScrollPane leftScroll,
                JScrollPane rightScroll,
                int[] leftStarts,
                int[] rightStarts
        ) {
            int w = getWidth();
            int h = getHeight();

            int triW = Math.min(16, Math.max(12, Math.max(1, w) / 3));
            int triH = triW;

            int cx = w / 2;
            int leftX = cx - triW - 8;
            int rightX = cx + 8;

            for (ChunkInfo ci : chunks) {
                int leftOffset = offsetForLine(leftStarts, ci.leftPos);
                int rightOffset = offsetForLine(rightStarts, ci.rightPos);

                int yLeftTop = yTopForOffset(leftArea, leftScroll, leftOffset);
                int yRightTop = yTopForOffset(rightArea, rightScroll, rightOffset);

                int leftTopY = clamp(yLeftTop + TRI_TOP_NUDGE_PX, 2, h - triH - 2);
                int rightTopY = clamp(yRightTop + TRI_TOP_NUDGE_PX, 2, h - triH - 2);

                ci.leftTriBounds = new Rectangle(leftX, leftTopY, triW, triH);     // points right
                ci.rightTriBounds = new Rectangle(rightX, rightTopY, triW, triH);  // points left
            }
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private static int offsetForLine(int[] starts, int line) {
            if (starts == null || starts.length == 0) return 0;
            line = Math.max(0, Math.min(line, starts.length - 1));
            return starts[line];
        }

        private static int yTopForOffset(JTextArea area, JScrollPane sp, int offset) {
            try {
                Rectangle r = area.modelToView2D(offset).getBounds();
                Point viewPos = sp.getViewport().getViewPosition();
                return r.y - viewPos.y;
            } catch (BadLocationException e) {
                return 0;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());

                g2.setColor(new Color(220, 220, 220));
                int cx = getWidth() / 2;
                g2.drawLine(cx, 0, cx, getHeight());

                g2.setColor(new Color(120, 120, 120));
                for (ChunkInfo ci : chunks) {
                    paintTriangleRight(g2, ci.leftTriBounds);
                    paintTriangleLeft(g2, ci.rightTriBounds);
                }
            } finally {
                g2.dispose();
            }
        }

        private static void paintTriangleRight(Graphics2D g2, Rectangle b) {
            int x = b.x, y = b.y, w = b.width, h = b.height;
            Polygon p = new Polygon(
                    new int[]{x, x + w, x},
                    new int[]{y, y + h / 2, y + h},
                    3
            );
            g2.fillPolygon(p);
        }

        private static void paintTriangleLeft(Graphics2D g2, Rectangle b) {
            int x = b.x, y = b.y, w = b.width, h = b.height;
            Polygon p = new Polygon(
                    new int[]{x + w, x, x + w},
                    new int[]{y, y + h / 2, y + h},
                    3
            );
            g2.fillPolygon(p);
        }
    }

    // ---------------- Line Numbers ----------------

    private static class LineNumberView extends JComponent implements DocumentListener, CaretListener {
        private final JTextComponent text;
        private final Font font;

        LineNumberView(JTextComponent text) {
            this.text = text;
            this.font = new Font(Font.MONOSPACED, Font.PLAIN, 12);
            setFont(font);
            setForeground(new Color(120, 120, 120));
            setBackground(new Color(250, 250, 250));
            setOpaque(true);

            text.getDocument().addDocumentListener(this);
            text.addCaretListener(this);
        }

        @Override
        public Dimension getPreferredSize() {
            int lines = Math.max(1, text.getDocument().getDefaultRootElement().getElementCount());
            int digits = Math.max(3, String.valueOf(lines).length());
            int charW = getFontMetrics(font).charWidth('0');
            int width = 8 + digits * charW + 8;
            return new Dimension(width, Integer.MAX_VALUE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setFont(font);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());

                g2.setColor(getForeground());
                FontMetrics fm = g2.getFontMetrics();

                Rectangle clip = g2.getClipBounds();
                int startOffset = text.viewToModel2D(new Point(0, clip.y));
                int endOffset = text.viewToModel2D(new Point(0, clip.y + clip.height));

                Element root = text.getDocument().getDefaultRootElement();
                int startLine = root.getElementIndex(startOffset);
                int endLine = root.getElementIndex(endOffset);

                for (int line = startLine; line <= endLine; line++) {
                    Element elem = root.getElement(line);
                    int lineStart = elem.getStartOffset();
                    try {
                        Rectangle r = text.modelToView2D(lineStart).getBounds();
                        int y = r.y + r.height - fm.getDescent();

                        String s = String.valueOf(line + 1);
                        int x = getWidth() - 8 - fm.stringWidth(s);
                        g2.drawString(s, x, y);
                    } catch (BadLocationException ignored) {}
                }

                g2.setColor(new Color(230, 230, 230));
                g2.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            } finally {
                g2.dispose();
            }
        }

        @Override public void insertUpdate(DocumentEvent e) { revalidate(); repaint(); }
        @Override public void removeUpdate(DocumentEvent e) { revalidate(); repaint(); }
        @Override public void changedUpdate(DocumentEvent e) { revalidate(); repaint(); }
        @Override public void caretUpdate(CaretEvent e) { repaint(); }
    }

    // ---------------- Small helper ----------------

    private static class IntArrayList {
        private int[] a;
        private int size;
        IntArrayList(int cap) { a = new int[Math.max(4, cap)]; }
        void add(int v) { if (size == a.length) a = Arrays.copyOf(a, a.length * 2); a[size++] = v; }
        int[] toArray() { return Arrays.copyOf(a, size); }
    }

    // ---------------- Simple ribbon icons ----------------

    private static abstract class SimpleIcon implements Icon {
        final int w, h;
        SimpleIcon(int w, int h) { this.w = w; this.h = h; }
        @Override public int getIconWidth() { return w; }
        @Override public int getIconHeight() { return h; }
        @Override public final void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                paint(g2, c.isEnabled());
            } finally {
                g2.dispose();
            }
        }
        abstract void paint(Graphics2D g2, boolean enabled);
    }

    private static class DiffIcon extends SimpleIcon {
        DiffIcon() { super(16, 16); }
        @Override void paint(Graphics2D g2, boolean enabled) {
            g2.setColor(enabled ? new Color(70, 70, 70) : new Color(160, 160, 160));
            g2.drawRect(1, 2, 5, 12);
            g2.drawRect(10, 2, 5, 12);
            g2.drawLine(7, 4, 9, 4);
            g2.drawLine(7, 8, 9, 8);
            g2.drawLine(7, 12, 9, 12);
        }
    }

    private static class SaveArrowIcon extends SimpleIcon {
        private final boolean left;
        SaveArrowIcon(boolean left) { super(24, 24); this.left = left; }

        @Override void paint(Graphics2D g2, boolean enabled) {
            Color c = enabled ? new Color(70, 70, 70) : new Color(160, 160, 160);
            g2.setColor(c);

            g2.drawRect(3, 3, 18, 18);
            g2.drawRect(6, 4, 10, 6);
            g2.drawLine(6, 15, 19, 15);
            g2.drawLine(6, 18, 19, 18);

            // big arrow (~3x)
            if (left) {
                g2.fillRect(7, 20, 12, 3);
                Polygon head = new Polygon(
                        new int[]{7, 2, 7},
                        new int[]{17, 21, 25},
                        3
                );
                g2.fillPolygon(head);
            } else {
                g2.fillRect(5, 20, 12, 3);
                Polygon head = new Polygon(
                        new int[]{17, 22, 17},
                        new int[]{17, 21, 25},
                        3
                );
                g2.fillPolygon(head);
            }
        }
    }

    private static class UndoIcon extends SimpleIcon {
        UndoIcon() { super(16, 16); }
        @Override void paint(Graphics2D g2, boolean enabled) {
            g2.setColor(enabled ? new Color(70, 70, 70) : new Color(160, 160, 160));
            Polygon arrow = new Polygon(
                    new int[]{6, 2, 6},
                    new int[]{3, 8, 13},
                    3
            );
            g2.fillPolygon(arrow);
            g2.drawArc(4, 3, 10, 10, 120, 240);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SwingDiffTool::new);
    }
}
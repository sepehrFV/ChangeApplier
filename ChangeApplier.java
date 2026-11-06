import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Change Applier - merges a zip of changed files into a project folder.
 * Never deletes project files. Every replaced file is backed up first, so everything can be rolled back.
 * Run:  java ChangeApplier.java   (JDK 11+)
 */
public class ChangeApplier extends JFrame {

    // =====================================================================
    //  CORE (no UI)
    // =====================================================================
    enum Kind { ADD, REPLACE, SAME }

    static final class Item {
        final String rel, entry; final Path target; final Kind kind;
        Item(String rel, String entry, Path target, Kind kind) { this.rel = rel; this.entry = entry; this.target = target; this.kind = kind; }
    }

    static final class Plan {
        final Path root, zip;
        final List<Item> items = new ArrayList<>();
        final Set<Path> dirs = new TreeSet<>();
        final List<String> warnings = new ArrayList<>();
        String wrapper = "";
        Plan(Path root, Path zip) { this.root = root; this.zip = zip; }
        int count(Kind k) { int n = 0; for (Item i : items) if (i.kind == k) n++; return n; }
        int changes() { return count(Kind.ADD) + count(Kind.REPLACE) + dirs.size(); }
    }

    static final class Result { int added, replaced, dirs, same; }

    static final class ApplyFailure extends Exception {
        final Session session;
        ApplyFailure(Throwable cause, Session s) { super(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause); this.session = s; }
    }

    /** One apply operation: what was added/replaced/created, plus where the backups live. */
    static final class Session {
        Path root, dir, files, manifest;
        final List<String> added = new ArrayList<>(), replaced = new ArrayList<>(), dirs = new ArrayList<>();
        String status = "";

        static Path base() {
            String la = System.getenv("LOCALAPPDATA");
            Path b = la != null ? Paths.get(la) : Paths.get(System.getProperty("user.home"), ".config");
            return b.resolve("ChangeApplier").resolve("backups");
        }

        static Session create(Path root) throws IOException {
            Session s = new Session();
            s.root = root;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
            s.dir = base().resolve(stamp + "_" + (root.getFileName() == null ? "root" : root.getFileName().toString()));
            s.files = s.dir.resolve("files");
            s.manifest = s.dir.resolve("manifest.txt");
            Files.createDirectories(s.files);
            s.log("ROOT\t" + root);
            return s;
        }

        void log(String line) throws IOException {
            Files.write(manifest, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        static Session load(Path dir) throws IOException {
            Path m = dir.resolve("manifest.txt");
            if (!Files.isRegularFile(m)) return null;
            Session s = new Session();
            s.dir = dir; s.files = dir.resolve("files"); s.manifest = m;
            for (String line : Files.readAllLines(m, StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t < 0) continue;
                String k = line.substring(0, t), v = line.substring(t + 1);
                switch (k) {
                    case "ROOT": s.root = Paths.get(v); break;
                    case "A": s.added.add(v); break;
                    case "R": s.replaced.add(v); break;
                    case "D": s.dirs.add(v); break;
                    case "STATUS": s.status = v; break;
                    default: break;
                }
            }
            return s.root == null ? null : s;
        }

        static Session lastUndoable() {
            try {
                if (!Files.isDirectory(base())) return null;
                List<Path> all = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(base())) { for (Path p : ds) all.add(p); }
                all.sort(Comparator.reverseOrder());
                for (Path p : all) {
                    Session s = load(p);
                    if (s != null) return "UNDONE".equals(s.status) ? null : s;   // only the newest one can be undone
                }
            } catch (IOException ignored) { }
            return null;
        }

        /** Restores replaced files, removes added files, removes created folders (only if empty). */
        String rollback() {
            int restored = 0, removed = 0, folders = 0; List<String> errors = new ArrayList<>();
            for (String rel : replaced) {
                try {
                    Path b = files.resolve(rel);
                    if (Files.exists(b)) { Files.copy(b, root.resolve(rel), StandardCopyOption.REPLACE_EXISTING); restored++; }
                } catch (IOException e) { errors.add(rel + ": " + e.getMessage()); }
            }
            for (String rel : added) {
                try { if (Files.deleteIfExists(root.resolve(rel))) removed++; }
                catch (IOException e) { errors.add(rel + ": " + e.getMessage()); }
            }
            for (int i = dirs.size() - 1; i >= 0; i--) {
                try { Files.delete(root.resolve(dirs.get(i))); folders++; }
                catch (DirectoryNotEmptyException | NoSuchFileException ignored) { }
                catch (IOException e) { errors.add(dirs.get(i) + ": " + e.getMessage()); }
            }
            try { log("STATUS\tUNDONE"); } catch (IOException ignored) { }
            String msg = plural(restored, "file") + " restored, " + plural(removed, "file") + " removed, " + plural(folders, "folder") + " removed";
            if (!errors.isEmpty()) msg += "  (" + errors.size() + " problem(s), first: " + errors.get(0) + ")";
            return msg;
        }
    }

    static String norm(String n) {
        n = n.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        return n;
    }

    static Plan analyze(Path rootIn, Path zip) throws IOException {
        if (!Files.isDirectory(rootIn)) throw new IOException("Project root is not a folder: " + rootIn);
        if (!Files.isRegularFile(zip)) throw new IOException("Zip file not found: " + zip);
        Path root = rootIn.toAbsolutePath().normalize();
        Plan plan = new Plan(root, zip);
        try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry ze = en.nextElement();
                String n = norm(ze.getName());
                if (n.isEmpty() || n.startsWith("__MACOSX/") || n.endsWith(".DS_Store")) continue;
                entries.add(ze);
            }
            if (entries.isEmpty()) throw new IOException("The zip is empty.");

            // A single wrapper folder named like the project (e.g. "Gold-Service/") is ignored.
            String top = norm(entries.get(0).getName());
            top = top.contains("/") ? top.substring(0, top.indexOf('/')) : null;
            boolean sharedTop = top != null;
            for (ZipEntry ze : entries) if (top == null || !norm(ze.getName()).startsWith(top + "/")) { sharedTop = false; break; }
            String prefix = "";
            if (sharedTop && root.getFileName() != null && top.equalsIgnoreCase(root.getFileName().toString())) {
                prefix = top + "/"; plan.wrapper = top;
            } else if (sharedTop && !Files.exists(root.resolve(top))) {
                plan.warnings.add("Everything in the zip is inside a folder \"" + top + "\" that doesn't exist in this project. Is this the right project root?");
            }

            for (ZipEntry ze : entries) {
                String n = norm(ze.getName());
                String rel = n.substring(prefix.length());
                while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                if (rel.isEmpty()) continue;
                Path target;
                try { target = root.resolve(rel).normalize(); }
                catch (InvalidPathException e) { throw new IOException("Invalid path in zip: " + n); }
                if (!target.startsWith(root)) throw new IOException("Unsafe path in zip (points outside the project): " + n);

                if (ze.isDirectory()) {
                    if (Files.exists(target) && !Files.isDirectory(target)) throw new IOException("A file exists where a folder is expected: " + rel);
                    addMissingDirs(plan, root, target);
                    continue;
                }
                addMissingDirs(plan, root, target.getParent());
                if (Files.isDirectory(target)) throw new IOException("A folder exists where a file is expected: " + rel);
                Kind kind = !Files.exists(target) ? Kind.ADD : (sameContent(zf, ze, target) ? Kind.SAME : Kind.REPLACE);
                plan.items.add(new Item(rel, ze.getName(), target, kind));
            }
        }
        if (plan.items.size() > 5 && plan.count(Kind.ADD) == plan.items.size() && plan.warnings.isEmpty())
            plan.warnings.add("None of the files in the zip exist in this project yet. Double-check the project root.");
        plan.items.sort(Comparator.comparing((Item i) -> i.kind == Kind.SAME ? 1 : 0).thenComparing(i -> i.rel));
        return plan;
    }

    private static void addMissingDirs(Plan plan, Path root, Path dir) throws IOException {
        Path p = dir;
        while (p != null && !p.equals(root) && !Files.exists(p)) { plan.dirs.add(p); p = p.getParent(); }
        if (p != null && Files.exists(p) && !Files.isDirectory(p)) throw new IOException("A file exists where a folder is expected: " + root.relativize(p));
    }

    private static boolean sameContent(ZipFile zf, ZipEntry ze, Path target) throws IOException {
        long size = ze.getSize();
        if (size >= 0 && size != Files.size(target)) return false;
        try (InputStream a = zf.getInputStream(ze); InputStream b = new BufferedInputStream(Files.newInputStream(target))) {
            byte[] x = new byte[65536], y = new byte[65536];
            while (true) {
                int n = readFully(a, x), m = readFully(b, y);
                if (n != m) return false;
                if (n == 0) return true;
                if (!Arrays.equals(x, 0, n, y, 0, n)) return false;
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) { int r = in.read(buf, off, buf.length - off); if (r < 0) break; off += r; }
        return off;
    }

    static String rel(Path root, Path p) { return root.relativize(p).toString().replace('\\', '/'); }

    static Result apply(Plan plan) throws ApplyFailure, IOException {
        Session s = Session.create(plan.root);
        try (ZipFile zf = new ZipFile(plan.zip.toFile(), StandardCharsets.UTF_8)) {
            for (Path d : plan.dirs) { Files.createDirectory(d); String r = rel(plan.root, d); s.dirs.add(r); s.log("D\t" + r); }
            for (Item it : plan.items) {
                if (it.kind == Kind.SAME) continue;
                if (it.kind == Kind.REPLACE) {
                    Path b = s.files.resolve(it.rel);
                    Files.createDirectories(b.getParent());
                    Files.copy(it.target, b, StandardCopyOption.REPLACE_EXISTING);   // backup first
                    s.replaced.add(it.rel); s.log("R\t" + it.rel);
                } else {
                    s.added.add(it.rel); s.log("A\t" + it.rel);
                }
                Path tmp = it.target.resolveSibling(it.target.getFileName() + ".applier-tmp");
                try (InputStream in = zf.getInputStream(zf.getEntry(it.entry))) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, it.target, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(tmp); }
            }
            s.log("STATUS\tAPPLIED");
            prune();
        } catch (Exception e) {
            try { s.log("STATUS\tPARTIAL"); } catch (IOException ignored) { }
            throw new ApplyFailure(e, s);
        }
        Result r = new Result();
        r.added = plan.count(Kind.ADD); r.replaced = plan.count(Kind.REPLACE); r.same = plan.count(Kind.SAME); r.dirs = plan.dirs.size();
        return r;
    }

    /** Keep only the 15 newest backups. */
    private static void prune() {
        try {
            List<Path> all = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Session.base())) { for (Path p : ds) all.add(p); }
            all.sort(Comparator.reverseOrder());
            for (int i = 15; i < all.size(); i++) {
                try (java.util.stream.Stream<Path> w = Files.walk(all.get(i))) {
                    w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (IOException ignored) { }
    }

    static String plural(int n, String w) { return n + " " + w + (n == 1 ? "" : "s"); }

    static String summarize(int a, int r, int d) {
        List<String> parts = new ArrayList<>();
        if (a > 0) parts.add(plural(a, "file") + " added");
        if (r > 0) parts.add(plural(r, "file") + " replaced");
        if (d > 0) parts.add(plural(d, "directory").replace("directorys", "directories") + " created");
        return parts.isEmpty() ? "no changes" : String.join(", ", parts);
    }

    // =====================================================================
    //  UI
    // =====================================================================
    private static final Color GREEN = new Color(0x2E7D32), BLUE = new Color(0x1565C0), GRAY = new Color(0x757575),
            ORANGE = new Color(0xE65100), RED = new Color(0xC62828), PURPLE = new Color(0x6A1B9A);

    private final Preferences prefs = Preferences.userNodeForPackage(ChangeApplier.class);
    private final JTextField rootField = new JTextField(), zipField = new JTextField();
    private final JLabel chipAdd = chip(GREEN), chipRep = chip(BLUE), chipSame = chip(GRAY), chipDir = chip(PURPLE);
    private final JLabel warning = new JLabel(" "), banner = new JLabel(" ");
    private final JPanel bannerBox = new JPanel(new BorderLayout());
    private final JButton applyBtn = new JButton("Apply changes"), undoBtn = new JButton("Undo last apply");
    private final List<String[]> rows = new ArrayList<>();
    private final AbstractTableModel model = new AbstractTableModel() {
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 2; }
        public String getColumnName(int c) { return c == 0 ? "Action" : "File"; }
        public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
    };
    private final javax.swing.Timer debounce = new javax.swing.Timer(350, e -> analyzeNow());
    private int seq = 0;

    ChangeApplier() {
        super("Change Applier");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        debounce.setRepeats(false);

        JLabel title = new JLabel("Change Applier");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel sub = new JLabel("Merge a zip of changed files into your project. Nothing is ever deleted.");
        sub.setForeground(GRAY);

        JPanel form = new JPanel(new GridBagLayout());
        addRow(form, 0, "Project root", rootField, browseButton(true));
        addRow(form, 1, "Changes zip", zipField, browseButton(false));
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        };
        rootField.getDocument().addDocumentListener(dl);
        zipField.getDocument().addDocumentListener(dl);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        chips.add(chipAdd); chips.add(chipRep); chips.add(chipDir); chips.add(chipSame);
        warning.setForeground(ORANGE);
        banner.setFont(banner.getFont().deriveFont(Font.BOLD, 14f));
        bannerBox.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        bannerBox.add(banner);
        bannerBox.setVisible(false);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (Component c : new Component[]{title, sub, Box.createVerticalStrut(12), form, Box.createVerticalStrut(10), chips,
                Box.createVerticalStrut(6), warning, Box.createVerticalStrut(6), bannerBox, Box.createVerticalStrut(6)}) {
            ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT); top.add(c);
        }

        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(110);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                String label = rows.get(r)[0];
                Color col = label.equals("NEW") ? GREEN : label.equals("REPLACE") ? BLUE : label.equals("NEW FOLDER") ? PURPLE : GRAY;
                if (!sel) comp.setForeground(c == 0 || label.equals("SAME") ? col : t.getForeground());
                comp.setFont(comp.getFont().deriveFont(c == 0 ? Font.BOLD : Font.PLAIN));
                return comp;
            }
        });
        JScrollPane scroll = new JScrollPane(table);

        applyBtn.setFont(applyBtn.getFont().deriveFont(Font.BOLD, 14f));
        applyBtn.setEnabled(false);
        applyBtn.addActionListener(e -> doApply());
        undoBtn.addActionListener(e -> doUndo());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        bottom.add(undoBtn, BorderLayout.WEST);
        bottom.add(applyBtn, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        content.add(top, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        TransferHandler dnd = new TransferHandler() {
            public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    for (File f : (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor)) {
                        if (f.isDirectory()) rootField.setText(f.getAbsolutePath());
                        else if (f.getName().toLowerCase().endsWith(".zip")) zipField.setText(f.getAbsolutePath());
                    }
                    return true;
                } catch (Exception e) { return false; }
            }
        };
        for (JComponent c : new JComponent[]{content, rootField, zipField, table, scroll}) c.setTransferHandler(dnd);

        rootField.setText(prefs.get("root", ""));
        refreshUndo();
        setMinimumSize(new Dimension(640, 480));
        setSize(780, 640);
        setLocationRelativeTo(null);
        showEmpty();
    }

    private static JLabel chip(Color c) {
        JLabel l = new JLabel(" ");
        l.setForeground(c);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(c, 1, true), BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        return l;
    }

    private void addRow(JPanel p, int y, String label, JTextField f, JButton b) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = y; g.insets = new Insets(3, 0, 3, 8); g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; p.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; f.setPreferredSize(new Dimension(300, 28)); p.add(f, g);
        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE; g.insets = new Insets(3, 0, 3, 0); p.add(b, g);
    }

    private JButton browseButton(boolean folder) {
        JButton b = new JButton("Browse...");
        b.addActionListener(e -> {
            JTextField f = folder ? rootField : zipField;
            JFileChooser fc = new JFileChooser(f.getText().trim().isEmpty() ? null : new File(f.getText().trim()));
            if (folder) fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            else fc.setFileFilter(new FileNameExtensionFilter("Zip files", "zip"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) f.setText(fc.getSelectedFile().getAbsolutePath());
        });
        return b;
    }

    private static String clean(String s) { return s.trim().replaceAll("^\"|\"$", "").trim(); }

    private void showBanner(String text, Color color) {
        banner.setText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>");
        banner.setForeground(Color.WHITE);
        bannerBox.setBackground(color);
        bannerBox.setOpaque(true);
        bannerBox.setVisible(true);
    }

    private void showEmpty() {
        rows.clear(); model.fireTableDataChanged();
        for (JLabel l : new JLabel[]{chipAdd, chipRep, chipDir, chipSame}) l.setVisible(false);
        warning.setText(" "); applyBtn.setEnabled(false);
        bannerBox.setVisible(false);
    }

    private void analyzeNow() {
        String r = clean(rootField.getText()), z = clean(zipField.getText());
        if (r.isEmpty() || z.isEmpty()) { showEmpty(); return; }
        final int my = ++seq;
        new SwingWorker<Plan, Void>() {
            protected Plan doInBackground() throws Exception { return analyze(Paths.get(r), Paths.get(z)); }
            protected void done() {
                if (my != seq) return;
                try { show(get()); prefs.put("root", r); }
                catch (ExecutionException ex) { showEmpty(); showBanner(ex.getCause().getMessage() == null ? ex.getCause().toString() : ex.getCause().getMessage(), RED); }
                catch (InterruptedException ignored) { }
            }
        }.execute();
    }

    private void show(Plan p) {
        bannerBox.setVisible(false);
        rows.clear();
        for (Item i : p.items) rows.add(new String[]{i.kind == Kind.ADD ? "NEW" : i.kind == Kind.REPLACE ? "REPLACE" : "SAME", i.rel});
        for (Path d : p.dirs) rows.add(new String[]{"NEW FOLDER", rel(p.root, d)});
        model.fireTableDataChanged();
        set(chipAdd, p.count(Kind.ADD) + " new");
        set(chipRep, p.count(Kind.REPLACE) + " to replace");
        set(chipDir, plural(p.dirs.size(), "new folder"));
        set(chipSame, p.count(Kind.SAME) + " identical (skipped)");
        String note = p.wrapper.isEmpty() ? "" : "Zip wrapper folder \"" + p.wrapper + "\" ignored. ";
        warning.setText(p.warnings.isEmpty() ? (note.isEmpty() ? " " : note) : "<html>\u26A0 " + String.join("<br>\u26A0 ", p.warnings) + "</html>");
        warning.setForeground(p.warnings.isEmpty() ? GRAY : ORANGE);
        applyBtn.setEnabled(p.changes() > 0);
        if (p.changes() == 0) showBanner("Nothing to do: every file in the zip is already identical in the project.", GRAY);
    }

    private void set(JLabel l, String t) { l.setText(t); l.setVisible(true); }

    private void setBusy(boolean busy) {
        applyBtn.setEnabled(false); undoBtn.setEnabled(!busy);
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void doApply() {
        String r = clean(rootField.getText()), z = clean(zipField.getText());
        setBusy(true);
        new SwingWorker<Result, Void>() {
            protected Result doInBackground() throws Exception { return apply(analyze(Paths.get(r), Paths.get(z))); }
            protected void done() {
                setBusy(false);
                try {
                    Result res = get();
                    String msg = "\u2714 Done: " + summarize(res.added, res.replaced, res.dirs) + ".";
                    if (res.same > 0) msg += "  (" + plural(res.same, "identical file") + " skipped.)";
                    showBanner(msg, GREEN);
                    applyBtn.setEnabled(false);
                } catch (ExecutionException ex) {
                    Throwable c = ex.getCause();
                    if (c instanceof ApplyFailure) askRollback((ApplyFailure) c);
                    else { showBanner(String.valueOf(c.getMessage()), RED); applyBtn.setEnabled(true); }
                } catch (InterruptedException ignored) { }
                refreshUndo();
            }
        }.execute();
    }

    private void askRollback(ApplyFailure f) {
        Object[] opts = {"Roll back", "Keep as is"};
        int c = JOptionPane.showOptionDialog(this,
                "Something went wrong while applying the changes:\n\n" + f.getMessage() + "\n\nRoll back to how the project was before?",
                "Problem", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, opts, opts[0]);
        if (c == 0) showBanner("Rolled back: " + f.session.rollback() + ".", ORANGE);
        else { showBanner("Stopped with an error; the project is partly updated. Use \"Undo last apply\" to roll back later.", RED); applyBtn.setEnabled(true); }
    }

    private void doUndo() {
        Session s = Session.lastUndoable();
        if (s == null) { refreshUndo(); return; }
        int c = JOptionPane.showConfirmDialog(this,
                "Undo the last apply on:\n" + s.root + "\n\nThis restores " + plural(s.replaced.size(), "replaced file") + " from backup and removes "
                        + plural(s.added.size(), "added file") + ".\nAny edits made to those files since will be lost.",
                "Undo last apply", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (c != JOptionPane.OK_OPTION) return;
        String msg = s.rollback();
        showEmpty();
        showBanner("Undone: " + msg + ".", ORANGE);
        refreshUndo();
    }

    private void refreshUndo() {
        Session s = Session.lastUndoable();
        undoBtn.setEnabled(s != null);
        undoBtn.setToolTipText(s == null ? "Nothing to undo" : "Undo the last apply on " + s.root);
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new ChangeApplier().setVisible(true));
    }
}

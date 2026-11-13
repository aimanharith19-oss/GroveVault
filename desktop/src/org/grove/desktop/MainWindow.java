package org.grove.desktop;

import org.grove.vault.core.*;
import org.grove.vault.core.Label;
import org.grove.vault.core.kdbx.Kdbx;
import org.grove.vault.core.kdbx.KeePassMapper;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Main window: filters, card list, card details, menus for import/export/backup. */
public class MainWindow extends JFrame {
    private final DesktopVault dv;
    private final DefaultListModel<String[]> filterModel = new DefaultListModel<>();
    private final JList<String[]> filters = new JList<>(filterModel);
    private final DefaultListModel<Card> cardModel = new DefaultListModel<>();
    private final JList<Card> cards = new JList<>(cardModel);
    private final JPanel detail = new JPanel();
    private final JTextField search = new JTextField();
    private String filter = "all";
    private final List<Runnable> tickers = new ArrayList<>();
    private long lastInput = System.currentTimeMillis();
    private boolean refreshing;

    public MainWindow(DesktopVault dv) {
        super("Grove Vault — " + dv.file.getName());
        this.dv = dv;
        setIconImages(GroveDesktop.icons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(Ux.getInt("win_w", 1100), Ux.getInt("win_h", 700));
        setLocationRelativeTo(null);
        setJMenuBar(menus());

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(new EmptyBorder(8, 8, 8, 8));
        search.putClientProperty("JTextField.placeholderText", "Search");
        search.setToolTipText("Search titles, logins, websites, notes (Ctrl+F)");
        top.add(new JLabel("🔍 Search:"), BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        JPanel tb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        tb.add(Ux.button("+ New card", e -> newCard()));
        tb.add(Ux.button("Generator", e -> GeneratorDialog.show(this, null)));
        tb.add(Ux.button("Lock", e -> lock()));
        top.add(tb, BorderLayout.EAST);

        filters.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, ((String[]) v)[1], i, sel, f);
                c.setBorder(new EmptyBorder(6, 10, 6, 10));
                return c;
            }
        });
        filters.addListSelectionListener(e -> {
            if (refreshing || e.getValueIsAdjusting() || filters.getSelectedValue() == null) return;
            filter = filters.getSelectedValue()[0];
            refresh();
        });
        cards.setCellRenderer(new CardRenderer());
        cards.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) showCard(cards.getSelectedValue()); });
        cards.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && cards.getSelectedValue() != null) editCard(cards.getSelectedValue());
            }
            public void mousePressed(MouseEvent e) { popup(e); }
            public void mouseReleased(MouseEvent e) { popup(e); }
            void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int i = cards.locationToIndex(e.getPoint());
                if (i < 0) return;
                cards.setSelectedIndex(i);
                cardMenu(cards.getSelectedValue()).show(cards, e.getX(), e.getY());
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });

        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setBorder(new EmptyBorder(16, 18, 16, 18));
        JScrollPane fs = new JScrollPane(filters);
        fs.setBorder(new MatteBorder(0, 0, 0, 1, new Color(0xDDDDDD)));
        JScrollPane cs = new JScrollPane(cards);
        cs.setBorder(new MatteBorder(0, 0, 0, 1, new Color(0xDDDDDD)));
        JScrollPane ds = new JScrollPane(detail);
        ds.setBorder(null);
        ds.getVerticalScrollBar().setUnitIncrement(16);
        JSplitPane right = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cs, ds);
        right.setDividerLocation(340);
        right.setBorder(null);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fs, right);
        split.setDividerLocation(200);
        split.setBorder(null);

        Ux.statusBar = new JLabel(" Unlocked: " + dv.file.getAbsolutePath());
        Ux.statusBar.setBorder(new EmptyBorder(4, 6, 4, 6));
        Ux.statusBar.setForeground(Ux.SUB);
        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(Ux.statusBar, BorderLayout.SOUTH);
        setContentPane(root);

        // keyboard: Ctrl+F search, Ctrl+N new, Ctrl+L lock, Ctrl+B / Ctrl+C copy login / password
        bind(KeyEvent.VK_F, () -> search.requestFocusInWindow());
        bind(KeyEvent.VK_N, this::newCard);
        bind(KeyEvent.VK_L, this::lock);
        bind(KeyEvent.VK_B, () -> { Card c = cards.getSelectedValue(); if (c != null && !c.login().isEmpty()) Ux.copy(this, "Login", c.login()); });
        bind(KeyEvent.VK_P, () -> { Card c = cards.getSelectedValue(); if (c != null && !c.password().isEmpty()) Ux.copy(this, "Password", c.password()); });

        // live one-time codes, idle auto-lock
        new Timer(1000, e -> {
            for (Runnable r : tickers) r.run();
            int mins = Ux.getInt("autolock_min", 5);
            if (mins > 0 && System.currentTimeMillis() - lastInput > mins * 60000L && dv.vault != null) lock();
        }).start();
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> lastInput = System.currentTimeMillis(),
                AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        addWindowListener(new WindowAdapter() {
            public void windowActivated(WindowEvent e) { checkDisk(); }
            public void windowClosed(WindowEvent e) {
                Ux.put("win_w", getWidth());
                Ux.put("win_h", getHeight());
                dv.lock();
                System.exit(0);
            }
            public void windowIconified(WindowEvent e) {
                if ("1".equals(Ux.get("lock_minimize", "0"))) lock();
            }
        });
        refresh();
        showCard(null);
        SwingUtilities.invokeLater(this::expiryReminder);
    }

    private void expiryReminder() {
        long last = 0;
        try { last = Long.parseLong(Ux.get("exp_warned", "0")); } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        if (now - last < 86400000L || dv.vault == null) return;
        Ux.put("exp_warned", now);
        int n = 0;
        for (Card c : dv.vault.activeCards()) if (Audit.isExpiring(c, Ux.getInt("expiry_days", 30), now)) n++;
        if (n > 0 && Ux.confirm(this, n + (n == 1 ? " card is" : " cards are") + " expired or expiring soon. Show them?")) {
            filter = "expiring";
            refresh();
        }
    }

    private void bind(int key, Runnable r) {
        getRootPane().registerKeyboardAction(e -> r.run(), KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    // ---------------------------------------------------------------- menus

    private JMenuBar menus() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("New card", this::newCard));
        file.addSeparator();
        file.add(item("Save a backup copy…", this::backupCopy));
        file.add(item("Merge from another .grove file…", this::mergeGrove));
        file.add(item("Reload from disk", () -> checkDiskForce(true)));
        file.addSeparator();
        file.add(item("Change master password…", this::changePassword));
        file.add(item("Security key & key file…", this::changePassword));
        file.add(item("Lock", this::lock));
        file.add(item("Exit", this::dispose));
        mb.add(file);

        JMenu imp = new JMenu("Import");
        imp.add(item("From SafeInCloud (.db or .xml)…", this::importSic));
        imp.add(item("From KeePass (.kdbx)…", this::importKdbx));
        imp.add(item("From CSV (Chrome, Bitwarden, LastPass…)…", this::importCsv));
        mb.add(imp);

        JMenu exp = new JMenu("Export");
        exp.add(item("To SafeInCloud (.db)…", this::exportSic));
        exp.add(item("To KeePass (.kdbx)…", this::exportKdbx));
        exp.add(item("To CSV (not encrypted)…", this::exportCsv));
        mb.add(exp);

        JMenu tools = new JMenu("Tools");
        tools.add(item("Password generator", () -> GeneratorDialog.show(this, null)));
        tools.add(item("Manage labels…", this::manageLabels));
        tools.add(item("Security key & key file…", this::changePassword));
        tools.add(item("Settings…", this::settings));
        mb.add(tools);

        JMenu help = new JMenu("Help");
        help.add(item("Keyboard shortcuts", () -> Ux.info(this, "Ctrl+F  Search\nCtrl+N  New card\nCtrl+B  Copy login\nCtrl+P  Copy password\nCtrl+L  Lock\nDouble-click a card to edit it.")));
        help.add(item("About Grove Vault", () -> Ux.info(this, "Grove Vault " + GroveDesktop.VERSION + " for Windows\n\n"
                + "Open source (GPL-3.0). Network is used only for website icons (can be turned off).\n"
                + "Vault: AES-256-GCM, Argon2id (64 MiB) key derivation, optional key file and YubiKey.\n"
                + "Opens the same .grove files as the Android app.")));
        mb.add(help);
        return mb;
    }

    private JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        return i;
    }

    // ---------------------------------------------------------------- list

    private void refresh() {
        if (dv.vault == null) return;
        refreshing = true;
        try {
            Vault v = dv.vault;
            List<Card> active = v.activeCards();
            Map<String, List<Card>> reused = Audit.reused(active);
            long now = System.currentTimeMillis();
            int days = Ux.getInt("expiry_days", 30);
            int weak = 0, re = 0, exp = 0, fav = 0, pk = 0;
            for (Card c : active) {
                if (Audit.hasWeakPassword(c)) weak++;
                if (Audit.isReused(c, reused)) re++;
                if (Audit.isExpiring(c, days, now)) exp++;
                if (c.favorite) fav++;
                if (!c.passkeys().isEmpty()) pk++;
            }
            filterModel.clear();
            filterModel.addElement(new String[]{"all", "All cards (" + active.size() + ")"});
            filterModel.addElement(new String[]{"fav", "★ Favourites (" + fav + ")"});
            v.sortLabels();
            for (Label l : v.labels) {
                int n = 0;
                for (Card c : active) if (c.labelIds.contains(l.id)) n++;
                filterModel.addElement(new String[]{"label:" + l.id, "🏷 " + l.name + " (" + n + ")"});
            }
            if (pk > 0) filterModel.addElement(new String[]{"passkeys", "🔐 Passkeys (" + pk + ")"});
            filterModel.addElement(new String[]{"weak", "⚠ Weak passwords (" + weak + ")"});
            filterModel.addElement(new String[]{"reused", "♻ Reused passwords (" + re + ")"});
            filterModel.addElement(new String[]{"expiring", "⏰ Expiring (" + exp + ")"});
            filterModel.addElement(new String[]{"trash", "🗑 Trash (" + v.trash().size() + ")"});
            filterModel.addElement(new String[]{"templates", "📋 Templates (" + v.templates().size() + ")"});
            for (int i = 0; i < filterModel.size(); i++) if (filterModel.get(i)[0].equals(filter)) filters.setSelectedIndex(i);
            if (filters.getSelectedIndex() < 0) { filter = "all"; filters.setSelectedIndex(0); }

            String q = search.getText().trim();
            List<Card> source = filter.equals("trash") ? v.trash() : filter.equals("templates") ? v.templates() : active;
            List<Card> shown = new ArrayList<>();
            for (Card c : source) {
                if (!c.matches(q)) continue;
                if (filter.equals("fav") && !c.favorite) continue;
                if (filter.startsWith("label:") && !c.labelIds.contains(filter.substring(6))) continue;
                if (filter.equals("weak") && !Audit.hasWeakPassword(c)) continue;
                if (filter.equals("reused") && !Audit.isReused(c, reused)) continue;
                if (filter.equals("expiring") && !Audit.isExpiring(c, days, now)) continue;
                if (filter.equals("passkeys") && c.passkeys().isEmpty()) continue;
                shown.add(c);
            }
            shown.sort((a, b) -> {
                if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
                return a.title.compareToIgnoreCase(b.title);
            });
            Card sel = cards.getSelectedValue();
            cardModel.clear();
            for (Card c : shown) cardModel.addElement(c);
            if (sel != null) {
                for (int i = 0; i < cardModel.size(); i++) if (cardModel.get(i).id.equals(sel.id)) cards.setSelectedIndex(i);
            }
        } finally {
            refreshing = false;
        }
        Card s = cards.getSelectedValue();
        showCard(s);
    }

    private class CardRenderer extends JPanel implements ListCellRenderer<Card> {
        private final JLabel icon = new JLabel(), title = new JLabel(), sub = new JLabel(), badge = new JLabel();

        CardRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(6, 10, 6, 10));
            JPanel t = new JPanel(new GridLayout(2, 1));
            t.setOpaque(false);
            title.setFont(title.getFont().deriveFont(Font.PLAIN, 14f));
            sub.setForeground(Ux.SUB);
            t.add(title);
            t.add(sub);
            add(icon, BorderLayout.WEST);
            add(t, BorderLayout.CENTER);
            add(badge, BorderLayout.EAST);
        }

        public Component getListCellRendererComponent(JList<? extends Card> l, Card c, int i, boolean sel, boolean f) {
            Image img = DesktopIcons.cached(c);
            if (img != null) icon.setIcon(Ux.roundIcon(img, 34));
            else {
                icon.setIcon(Ux.avatar(c.title, c.color, 34));
                DesktopIcons.load(c, x -> cards.repaint());
            }
            title.setText(c.title.isEmpty() ? "(untitled)" : c.title);
            sub.setText(c.template ? c.fields.size() + " fields" : c.subtitle());
            String b = "";
            if (!c.passkeys().isEmpty()) b += "🔐";
            if (Audit.hasWeakPassword(c)) b += " ⚠";
            if (c.favorite) b += " ★";
            badge.setText(b);
            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            title.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            sub.setForeground(sel ? l.getSelectionForeground() : Ux.SUB);
            return this;
        }
    }

    private JPopupMenu cardMenu(Card c) {
        JPopupMenu m = new JPopupMenu();
        if (!c.login().isEmpty()) m.add(item("Copy login", () -> Ux.copy(this, "Login", c.login())));
        if (!c.password().isEmpty()) m.add(item("Copy password", () -> Ux.copy(this, "Password", c.password())));
        Field otp = c.first(Field.OTP);
        if (otp != null) m.add(item("Copy one-time code", () -> { Totp t = Totp.parse(otp.value); if (t != null) Ux.copy(this, "One-time code", t.code(System.currentTimeMillis())); }));
        if (!c.website().isEmpty()) m.add(item("Open website", () -> openUrl(c.website())));
        m.addSeparator();
        if (!c.password().isEmpty() || !c.login().isEmpty()) m.add(item("Auto-type into the previous window", () -> autoType(c)));
        m.add(item("Edit…", () -> editCard(c)));
        m.add(item(c.favorite ? "Remove from favourites" : "Add to favourites", () -> { c.favorite = !c.favorite; save(); }));
        if (c.deleted) {
            m.add(item("Restore from trash", () -> { c.deleted = false; save(); }));
            m.add(item("Delete forever", () -> { if (Ux.confirm(this, "Delete “" + c.title + "” forever?")) { dv.vault.cards.remove(c); save(); } }));
        } else if (c.template) {
            m.add(item("Delete template", () -> { dv.vault.cards.remove(c); save(); }));
        } else {
            m.add(item("Duplicate", () -> {
                Card d = c.copy();
                d.id = Vault.newId();
                d.title = c.title + " (copy)";
                d.created = d.modified = System.currentTimeMillis();
                dv.vault.cards.add(d);
                save();
            }));
            m.add(item("Move to trash", () -> { c.deleted = true; save(); }));
        }
        return m;
    }

    // ---------------------------------------------------------------- details

    private void showCard(Card c) {
        tickers.clear();
        detail.removeAll();
        if (c == null) {
            JLabel l = new JLabel("<html><div style='color:#5B6A61'>Select a card to see it here.<br><br>"
                    + "Click a value to copy it. Double-click a card in the list to edit it.</div></html>");
            detail.add(l);
            detail.revalidate();
            detail.repaint();
            return;
        }
        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setAlignmentX(LEFT_ALIGNMENT);
        JLabel headIcon = new JLabel(Ux.avatar(c.title, c.color, 48));
        DesktopIcons.load(c, img -> headIcon.setIcon(Ux.roundIcon(img, 48)));
        head.add(headIcon, BorderLayout.WEST);
        JLabel t = new JLabel(c.title.isEmpty() ? "(untitled)" : c.title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 20f));
        JPanel ht = new JPanel(new GridLayout(0, 1));
        ht.add(t);
        StringBuilder labels = new StringBuilder();
        for (String id : c.labelIds) {
            Label l = dv.vault.findLabel(id);
            if (l != null) labels.append(labels.length() > 0 ? "  ·  " : "").append(l.name);
        }
        if (labels.length() > 0) {
            JLabel ll = new JLabel("🏷 " + labels);
            ll.setForeground(Ux.SUB);
            ht.add(ll);
        }
        head.add(ht, BorderLayout.CENTER);
        JPanel hb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        hb.add(Ux.button(c.favorite ? "★" : "☆", e -> { c.favorite = !c.favorite; save(); }));
        if (!c.password().isEmpty() || !c.login().isEmpty()) hb.add(Ux.button("Auto-type", e -> autoType(c)));
        hb.add(Ux.button("Edit…", e -> editCard(c)));
        head.add(hb, BorderLayout.EAST);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
        detail.add(head);
        detail.add(Box.createVerticalStrut(10));

        long now = System.currentTimeMillis();
        if (c.deleted) warn("This card is in the trash.");
        if (Audit.isExpired(c, now)) warn("⏰ This card has expired.");
        else if (Audit.isExpiring(c, Ux.getInt("expiry_days", 30), now)) warn("⏰ Expires in " + ((Audit.expiry(c) - now) / 86400000L) + " days.");
        if (Audit.hasWeakPassword(c)) warn("⚠ Weak password: easy to guess.");
        Map<String, List<Card>> reused = Audit.reused(dv.vault.activeCards());
        for (List<Card> l : reused.values()) {
            if (!l.contains(c)) continue;
            List<String> others = new ArrayList<>();
            for (Card o : l) if (o != c) others.add(o.title);
            warn("♻ Same password is used for: " + String.join(", ", others));
        }

        for (Field f : c.fields) {
            if (f.value.isEmpty()) continue;
            detail.add(fieldRow(c, f));
        }
        if (!c.notes.isEmpty()) {
            detail.add(section("Notes"));
            JTextArea n = new JTextArea(c.notes);
            n.setEditable(false);
            n.setLineWrap(true);
            n.setWrapStyleWord(true);
            n.setBorder(new EmptyBorder(6, 6, 6, 6));
            n.setAlignmentX(LEFT_ALIGNMENT);
            n.setSize(new Dimension(500, Short.MAX_VALUE));
            n.setMaximumSize(new Dimension(Integer.MAX_VALUE, n.getPreferredSize().height + 8));
            detail.add(n);
        }
        if (!c.attachments.isEmpty()) {
            detail.add(section("Attachments"));
            for (Attachment a : c.attachments) {
                JPanel r = new JPanel(new BorderLayout(8, 0));
                r.setAlignmentX(LEFT_ALIGNMENT);
                r.add(new JLabel("📎 " + a.name + "  (" + (a.data.length / 1024 + 1) + " KB)"), BorderLayout.CENTER);
                r.add(Ux.button("Save as…", e -> saveAttachment(a)), BorderLayout.EAST);
                r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height + 4));
                detail.add(r);
            }
        }
        if (!c.apps.isEmpty()) {
            detail.add(section("Android apps"));
            for (String app : c.apps) {
                JLabel l = new JLabel(app);
                l.setForeground(Ux.SUB);
                detail.add(l);
            }
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        JLabel meta = new JLabel("<html>Created " + df.format(new Date(c.created)) + "<br>Modified " + df.format(new Date(c.modified)) + "</html>");
        meta.setForeground(Ux.SUB);
        meta.setBorder(new EmptyBorder(16, 0, 0, 0));
        detail.add(meta);
        detail.revalidate();
        detail.repaint();
    }

    private void warn(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(Ux.WARN);
        l.setBorder(new EmptyBorder(2, 0, 4, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        detail.add(l);
    }

    private JComponent section(String s) {
        JLabel l = new JLabel(s.toUpperCase());
        l.setForeground(Ux.ACCENT);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setBorder(new EmptyBorder(14, 0, 4, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private JComponent fieldRow(Card c, Field f) {
        JPanel r = new JPanel(new BorderLayout(8, 0));
        r.setAlignmentX(LEFT_ALIGNMENT);
        r.setBorder(new EmptyBorder(5, 0, 5, 0));
        JLabel name = new JLabel(f.name);
        name.setForeground(Ux.SUB);
        name.setFont(name.getFont().deriveFont(11f));
        JPanel col = new JPanel(new GridLayout(2, 1));
        col.add(name);
        JLabel value = new JLabel();
        value.setFont(new Font(f.isSecret() ? Font.MONOSPACED : value.getFont().getName(), Font.PLAIN, 15));
        col.add(value);
        r.add(col, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        r.add(btns, BorderLayout.EAST);
        value.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        value.setToolTipText("Click to copy");

        if (Field.PASSKEY.equals(f.type)) {
            Passkey p = Passkey.decode(f.value);
            value.setText(p == null ? "Damaged passkey" : "🔐 " + p.rpId + (p.userName.isEmpty() ? "" : " · " + p.userName));
            value.setToolTipText("Passkeys are used for sign-in on the phone (Android 14+). They're kept here and in KeePass exports.");
            value.setCursor(Cursor.getDefaultCursor());
        } else if (Field.OTP.equals(f.type)) {
            Totp t = Totp.parse(f.value);
            JLabel left = new JLabel();
            btns.add(left);
            Runnable upd = () -> {
                if (t == null) { value.setText("Invalid secret"); return; }
                long now = System.currentTimeMillis();
                String code = t.code(now);
                value.setText(code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code);
                int s = t.secondsLeft(now);
                left.setText(s + "s");
                left.setForeground(s <= 5 ? Ux.DANGER : Ux.SUB);
            };
            upd.run();
            tickers.add(upd);
            value.addMouseListener(click(() -> { if (t != null) Ux.copy(this, "One-time code", t.code(System.currentTimeMillis())); }));
            btns.add(Ux.button("Copy", e -> { if (t != null) Ux.copy(this, "One-time code", t.code(System.currentTimeMillis())); }));
        } else {
            boolean[] shown = {!f.isSecret()};
            Runnable show = () -> value.setText(shown[0] ? f.value.replace("\n", " ⏎ ") : "••••••••••");
            show.run();
            value.addMouseListener(click(() -> Ux.copy(this, f.name, f.value)));
            if (f.isSecret()) btns.add(Ux.button("Show", e -> { shown[0] = !shown[0]; show.run(); ((JButton) e.getSource()).setText(shown[0] ? "Hide" : "Show"); }));
            if (Field.WEBSITE.equals(f.type)) btns.add(Ux.button("Open", e -> openUrl(f.value)));
            if (!f.history.isEmpty()) btns.add(Ux.button("History", e -> history(f)));
            btns.add(Ux.button("Copy", e -> Ux.copy(this, f.name, f.value)));
        }
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
        return r;
    }

    /**
     * Auto-type: minimises Grove Vault so the previous window (e.g. a browser login form) gets focus, then pastes
     * login, Tab, password, Enter. Pasting avoids keyboard-layout problems; the clipboard is cleared afterwards.
     */
    private void autoType(Card c) {
        if (!Ux.confirm(this, "Grove Vault will minimise and type into the window you used before it:\n\n"
                + "login → Tab → password → Enter\n\nClick OK, and make sure that window's username field was the last thing you clicked.")) return;
        String login = c.login(), pass = c.password();
        setState(Frame.ICONIFIED);
        Thread t = new Thread(() -> {
            try {
                Robot r = new Robot();
                r.setAutoDelay(40);
                Thread.sleep(900);
                java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (!login.isEmpty()) {
                    paste(r, cb, login);
                    r.keyPress(KeyEvent.VK_TAB);
                    r.keyRelease(KeyEvent.VK_TAB);
                }
                if (!pass.isEmpty()) {
                    paste(r, cb, pass);
                    r.keyPress(KeyEvent.VK_ENTER);
                    r.keyRelease(KeyEvent.VK_ENTER);
                }
                Thread.sleep(300);
                SwingUtilities.invokeAndWait(() -> cb.setContents(new java.awt.datatransfer.StringSelection(""), null));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> Ux.error(this, "Auto-type failed: " + ex.getMessage()));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void paste(Robot r, java.awt.datatransfer.Clipboard cb, String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> cb.setContents(new java.awt.datatransfer.StringSelection(text), null));
        Thread.sleep(80);
        r.keyPress(KeyEvent.VK_CONTROL);
        r.keyPress(KeyEvent.VK_V);
        r.keyRelease(KeyEvent.VK_V);
        r.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(120);
    }

    private MouseAdapter click(Runnable r) {
        return new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { r.run(); }
        };
    }

    private void history(Field f) {
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        StringBuilder b = new StringBuilder("<html><table>");
        for (Field.HistoryEntry h : f.history)
            b.append("<tr><td style='color:#5B6A61'>").append(df.format(new Date(h.time))).append("</td><td><tt>")
                    .append(h.value.replace("&", "&amp;").replace("<", "&lt;")).append("</tt></td></tr>");
        JOptionPane.showMessageDialog(this, new JLabel(b.append("</table></html>").toString()), "Previous values of " + f.name, JOptionPane.PLAIN_MESSAGE);
    }

    private void openUrl(String url) {
        String u = url.trim();
        if (!u.contains("://")) u = "https://" + u;
        try {
            Desktop.getDesktop().browse(new java.net.URI(u));
        } catch (Exception e) {
            Ux.status("Can't open " + u);
        }
    }

    private void saveAttachment(Attachment a) {
        JFileChooser ch = new JFileChooser();
        ch.setSelectedFile(new File(a.name));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.write(ch.getSelectedFile().toPath(), a.data);
            Ux.status("Saved " + ch.getSelectedFile().getName());
        } catch (Exception e) {
            Ux.error(this, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- editing

    private void newCard() {
        List<Card> t = dv.vault.templates();
        t.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        if (filter.equals("templates")) {
            Card c = new Card();
            c.template = true;
            c.fields.add(new Field("Login", Field.LOGIN, ""));
            c.fields.add(new Field("Password", Field.PASSWORD, ""));
            openEditor(c, true);
            return;
        }
        String[] names = new String[t.size()];
        for (int i = 0; i < names.length; i++) names[i] = t.get(i).title;
        Object pick = JOptionPane.showInputDialog(this, "Template:", "New card", JOptionPane.PLAIN_MESSAGE, null, names, names.length > 0 ? names[0] : null);
        if (pick == null) return;
        Card c = t.get(Arrays.asList(names).indexOf(pick)).instantiate();
        if (filter.startsWith("label:")) c.labelIds.add(filter.substring(6));
        openEditor(c, true);
    }

    private void editCard(Card c) {
        openEditor(c.copy(), false);
    }

    private void openEditor(Card work, boolean isNew) {
        if (CardEditor.edit(this, dv.vault, work, isNew)) {
            Card existing = dv.vault.findCard(work.id);
            work.modified = System.currentTimeMillis();
            if (existing == null) dv.vault.cards.add(work);
            else {
                for (Field nf : work.fields)
                    for (Field of : existing.fields)
                        if (of.name.equals(nf.name) && of.type.equals(nf.type) && !of.value.equals(nf.value) && nf.isSecret()
                                && !Field.PASSKEY.equals(nf.type) && !of.value.isEmpty()
                                && (nf.history.isEmpty() || !nf.history.get(0).value.equals(of.value)))
                            nf.history.add(0, new Field.HistoryEntry(of.value, System.currentTimeMillis()));
                dv.vault.cards.set(dv.vault.cards.indexOf(existing), work);
            }
            save();
            for (int i = 0; i < cardModel.size(); i++) if (cardModel.get(i).id.equals(work.id)) cards.setSelectedIndex(i);
        }
    }

    private void save() {
        try {
            dv.save();
            Ux.status("Saved " + DateFormat.getTimeInstance().format(new Date()));
        } catch (Exception e) {
            Ux.error(this, "Couldn't save: " + e.getMessage());
        }
        refresh();
    }

    private void manageLabels() {
        Vault v = dv.vault;
        v.sortLabels();
        String[] opts = {"Add", "Rename", "Delete", "Close"};
        while (true) {
            JList<String> list = new JList<>(v.labels.stream().map(l -> l.name).toArray(String[]::new));
            list.setVisibleRowCount(10);
            int r = JOptionPane.showOptionDialog(this, new JScrollPane(list), "Labels", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[3]);
            int i = list.getSelectedIndex();
            if (r == 0) {
                String n = JOptionPane.showInputDialog(this, "New label name:");
                if (n != null && !n.trim().isEmpty()) v.labelNamed(n.trim());
            } else if (r == 1 && i >= 0) {
                String n = JOptionPane.showInputDialog(this, "New name:", v.labels.get(i).name);
                if (n != null && !n.trim().isEmpty()) v.labels.get(i).name = n.trim();
            } else if (r == 2 && i >= 0) {
                if (Ux.confirm(this, "Delete label “" + v.labels.get(i).name + "”? Cards are kept.")) v.deleteLabel(v.labels.get(i));
            } else break;
            v.sortLabels();
            save();
        }
    }

    private void settings() {
        JComboBox<String> lock = new JComboBox<>(new String[]{"Never", "1 minute", "5 minutes", "15 minutes", "30 minutes", "60 minutes"});
        int[] lockVals = {0, 1, 5, 15, 30, 60};
        JComboBox<String> clip = new JComboBox<>(new String[]{"Never", "15 seconds", "30 seconds", "60 seconds", "2 minutes"});
        int[] clipVals = {0, 15, 30, 60, 120};
        JComboBox<String> exp = new JComboBox<>(new String[]{"7 days", "14 days", "30 days", "60 days", "90 days"});
        int[] expVals = {7, 14, 30, 60, 90};
        JComboBox<String> erase = new JComboBox<>(new String[]{"Never", "After 5 wrong attempts", "After 10 wrong attempts", "After 20 wrong attempts"});
        int[] eraseVals = {0, 5, 10, 20};
        JCheckBox min = new JCheckBox("Lock when the window is minimised", "1".equals(Ux.get("lock_minimize", "0")));
        JCheckBox icons = new JCheckBox("Download website icons (straight from each site; only the address is used)", DesktopIcons.enabled());
        JButton clearIcons = Ux.button("Clear downloaded icons", e -> { DesktopIcons.clear(); Ux.status("Icons cleared"); });
        JCheckBox pin = new JCheckBox("Quick unlock with a PIN (Windows-protected)", PinUnlock.enabled(dv.file));
        if (!PinUnlock.allowed()) {
            pin.setEnabled(false);
            pin.setText(WinHelper.available() ? "Quick unlock with a PIN: off because this vault needs a key file or security key" : "Quick unlock with a PIN: Windows only");
        }
        JTextField backupDir = new JTextField(Ux.get("backup_dir", ""), 22);
        JButton pickDir = Ux.button("Choose…", e -> {
            JFileChooser ch = new JFileChooser(backupDir.getText().isEmpty() ? null : new File(backupDir.getText()));
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) backupDir.setText(ch.getSelectedFile().getAbsolutePath());
        });
        JPanel bp = new JPanel(new BorderLayout(4, 0));
        bp.add(backupDir, BorderLayout.CENTER);
        bp.add(pickDir, BorderLayout.EAST);
        JSpinner keep = new JSpinner(new SpinnerNumberModel(Ux.getInt("backup_keep", 10), 1, 100, 1));
        lock.setSelectedIndex(Math.max(0, idx(lockVals, Ux.getInt("autolock_min", 5))));
        clip.setSelectedIndex(Math.max(0, idx(clipVals, Ux.getInt("clip_secs", 30))));
        exp.setSelectedIndex(Math.max(0, idx(expVals, Ux.getInt("expiry_days", 30))));
        erase.setSelectedIndex(Math.max(0, idx(eraseVals, Ux.getInt("erase_after", 0))));
        JPanel p = Ux.form(new String[]{"Lock after inactivity", "", "Clear clipboard after", "Erase vault file", "", "Website icons", "", "Daily backup folder", "Keep backups", "Warn about expiring cards"},
                new JComponent[]{lock, min, clip, erase, pin, icons, clearIcons, bp, keep, exp});
        if (JOptionPane.showConfirmDialog(this, p, "Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        Ux.put("autolock_min", lockVals[lock.getSelectedIndex()]);
        Ux.put("clip_secs", clipVals[clip.getSelectedIndex()]);
        Ux.put("expiry_days", expVals[exp.getSelectedIndex()]);
        Ux.put("lock_minimize", min.isSelected() ? "1" : "0");
        Ux.put("icons", icons.isSelected() ? "1" : "0");
        int er = eraseVals[erase.getSelectedIndex()];
        if (er > 0 && er != Ux.getInt("erase_after", 0) && !Ux.confirm(this, "After " + er + " wrong attempts in a row, the vault file on this PC (and its .bak) will be deleted. Make sure you have backups. Turn on?")) er = Ux.getInt("erase_after", 0);
        Ux.put("erase_after", er);
        Ux.put("backup_dir", backupDir.getText().trim().isEmpty() ? null : backupDir.getText().trim());
        Ux.put("backup_keep", keep.getValue());
        if (pin.isEnabled()) {
            if (pin.isSelected() && !PinUnlock.enabled(dv.file)) {
                JPasswordField a = new JPasswordField(12), b = new JPasswordField(12);
                if (JOptionPane.showConfirmDialog(this, Ux.form(new String[]{"PIN (6–12 digits)", "Repeat"}, new JComponent[]{a, b}), "Set PIN", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    String x = new String(a.getPassword());
                    if (!x.matches("\\d{6,12}") || x.matches("(\\d)\\1+") || "0123456789012".contains(x) || "9876543210987".contains(x)) Ux.error(this, "Use 6–12 digits, not repeated or sequential.");
                    else if (!x.equals(new String(b.getPassword()))) Ux.error(this, "The PINs don't match.");
                    else {
                        try {
                            PinUnlock.enable(dv.file, x, dv.key.key);
                            Ux.status("PIN unlock is on");
                        } catch (Exception ex) {
                            Ux.error(this, "Couldn't set the PIN: " + ex.getMessage());
                        }
                    }
                }
            } else if (!pin.isSelected()) PinUnlock.disable(dv.file);
        }
        refresh();
    }

    private static int idx(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    // ---------------------------------------------------------------- lock / sync

    private void lock() {
        dv.lock();
        dispose0();
        GroveDesktop.start(dv.file);
    }

    private boolean disposing;

    private void dispose0() {
        disposing = true;
        for (WindowListener l : getWindowListeners()) removeWindowListener(l);
        setVisible(false);
        super.dispose();
    }

    private void checkDisk() {
        checkDiskForce(false);
    }

    private void checkDiskForce(boolean force) {
        if (dv.vault == null || disposing) return;
        if (!force && !dv.changedOnDisk()) return;
        try {
            int[] r = dv.mergeFromDisk();
            if (r == null) {
                Ux.info(this, "The vault file was changed elsewhere with a different password or unlock method. Unlock it to merge the changes.");
                DesktopUnlock.Result u = DesktopUnlock.unlock(this, Files.readAllBytes(dv.file.toPath()), "Vault changed");
                if (u == null) return;
                java.util.Arrays.fill(u.password, '\0');
                r = dv.mergeFromDisk(u.key);
            }
            if (r[0] + r[1] > 0) {
                dv.save();
                Ux.status("Merged changes from disk: " + r[0] + " added, " + r[1] + " updated");
            } else Ux.status("Up to date with " + dv.file.getName());
            refresh();
        } catch (VaultCrypto.WrongPasswordException e) {
            Ux.error(this, e.getMessage());
        } catch (Exception e) {
            Ux.status("Couldn't read changes from disk: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- backup / import / export

    private File chooseOpen(String desc, String... ext) {
        JFileChooser ch = new JFileChooser(Ux.get("last_dir", null));
        if (ext.length > 0) ch.setFileFilter(new FileNameExtensionFilter(desc, ext));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        Ux.put("last_dir", ch.getSelectedFile().getParent());
        return ch.getSelectedFile();
    }

    private File chooseSave(String name) {
        JFileChooser ch = new JFileChooser(Ux.get("last_dir", null));
        ch.setSelectedFile(new File(ch.getCurrentDirectory(), name));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        File f = ch.getSelectedFile();
        Ux.put("last_dir", f.getParent());
        if (f.exists() && !Ux.confirm(this, f.getName() + " exists. Replace it?")) return null;
        return f;
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    private void backupCopy() {
        File f = chooseSave("grove-backup-" + stamp() + ".grove");
        if (f == null) return;
        try {
            Files.copy(dv.file.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Ux.info(this, "Backup saved. It opens with your current master password.");
        } catch (Exception e) {
            Ux.error(this, e.getMessage());
        }
    }

    private void mergeGrove() {
        File f = chooseOpen("Grove vault (*.grove)", "grove");
        if (f == null) return;
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            VaultCrypto.Key k;
            if (VaultCrypto.sameKey(data, dv.key)) k = dv.key;
            else {
                DesktopUnlock.Result u = DesktopUnlock.unlock(this, data, "Merge " + f.getName());
                if (u == null) return;
                java.util.Arrays.fill(u.password, '\0');
                k = u.key;
            }
            int[] r = dv.vault.mergeFrom(VaultCrypto.decryptVault(data, k));
            save();
            Ux.info(this, r[0] + " cards added, " + r[1] + " updated.");
        } catch (VaultCrypto.WrongPasswordException e) {
            Ux.error(this, e.getMessage());
        } catch (Exception e) {
            Ux.error(this, e.getMessage());
        }
    }

    private void importSic() {
        File f = chooseOpen("SafeInCloud database or XML export (*.db, *.xml)", "db", "xml");
        if (f == null) return;
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            byte[] xml;
            if (SafeInCloud.isXml(data)) xml = data;
            else if (SafeInCloud.isDb(data)) {
                while (true) {
                    char[] pw = Ux.askPassword(this, "SafeInCloud", "SafeInCloud master password:");
                    if (pw == null) return;
                    try {
                        xml = SafeInCloud.decrypt(data, new String(pw));
                        break;
                    } catch (VaultCrypto.WrongPasswordException e) {
                        Ux.error(this, "Wrong password.");
                    }
                }
            } else {
                Ux.error(this, "This isn't a SafeInCloud database or XML export.");
                return;
            }
            SafeInCloud.Result r = SafeInCloud.importInto(dv.vault, xml);
            save();
            Ux.info(this, r.cards + " cards, " + r.templates + " templates, " + r.trashed + " deleted cards and " + r.attachments + " attachments imported from SafeInCloud.");
        } catch (Exception e) {
            Ux.error(this, "Can't import: " + e.getMessage());
        }
    }

    private void importKdbx() {
        File f = chooseOpen("KeePass database (*.kdbx)", "kdbx");
        if (f == null) return;
        JPasswordField pw = new JPasswordField(24);
        JTextField keyPath = new JTextField(24);
        JButton browse = Ux.button("Browse…", e -> { File k = chooseOpen("Key file"); if (k != null) keyPath.setText(k.getPath()); });
        JPanel kp = new JPanel(new BorderLayout(4, 0));
        kp.add(keyPath, BorderLayout.CENTER);
        kp.add(browse, BorderLayout.EAST);
        JCheckBox bin = new JCheckBox("Also import the Recycle Bin (into Trash)");
        JPanel p = Ux.form(new String[]{"Password", "Key file (optional)", ""}, new JComponent[]{pw, kp, bin});
        while (true) {
            if (JOptionPane.showConfirmDialog(this, p, "Open " + f.getName(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                byte[] key = keyPath.getText().trim().isEmpty() ? null : Files.readAllBytes(new File(keyPath.getText().trim()).toPath());
                String pass = new String(pw.getPassword());
                Kdbx.Database db;
                setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                try {
                    if (pass.isEmpty()) {
                        try { db = Kdbx.read(data, null, key); } catch (VaultCrypto.WrongPasswordException e) { db = Kdbx.read(data, "", key); }
                    } else db = Kdbx.read(data, pass, key);
                } finally {
                    setCursor(Cursor.getDefaultCursor());
                }
                KeePassMapper.Result r = KeePassMapper.importInto(dv.vault, db, bin.isSelected());
                save();
                Ux.info(this, r.imported + " entries imported. KeePass groups and tags became labels.");
                return;
            } catch (VaultCrypto.WrongPasswordException e) {
                Ux.error(this, "Wrong password or key file.");
            } catch (Exception e) {
                Ux.error(this, "Can't import: " + e.getMessage());
                return;
            }
        }
    }

    private void importCsv() {
        File f = chooseOpen("CSV file (*.csv)", "csv", "txt");
        if (f == null) return;
        try {
            int n = Csv.importInto(dv.vault, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            save();
            Ux.info(this, n == 0 ? "No entries found. The first line must be a header (name,url,username,password…)."
                    : n + " entries imported. Delete the CSV file now: it contains your passwords in plain text.");
        } catch (Exception e) {
            Ux.error(this, e.getMessage());
        }
    }

    private void exportSic() {
        char[] pw = Ux.askNewPassword(this, "Password for the SafeInCloud file", 1);
        if (pw == null) return;
        File f = chooseSave("SafeInCloud.db");
        if (f == null) return;
        try {
            Files.write(f.toPath(), SafeInCloud.encrypt(SafeInCloud.export(dv.vault, false), new String(pw)));
            Ux.info(this, "Exported. Open it in SafeInCloud with the password you just chose.");
        } catch (Exception e) {
            Ux.error(this, e.getMessage());
        }
    }

    private void exportKdbx() {
        char[] pw = Ux.askNewPassword(this, "Password for the KeePass file", 4);
        if (pw == null) return;
        String[] kdfs = {"Argon2d (KeePassXC default)", "Argon2id", "AES-KDF (most compatible)"};
        Object k = JOptionPane.showInputDialog(this, "Key derivation:", "Export to KeePass", JOptionPane.PLAIN_MESSAGE, null, kdfs, kdfs[0]);
        if (k == null) return;
        File f = chooseSave("grove-vault-" + stamp() + ".kdbx");
        if (f == null) return;
        try {
            Kdbx.Params p = new Kdbx.Params();
            p.kdf = Arrays.asList(kdfs).indexOf(k);
            p.argonMemoryKiB = 64 * 1024;
            Files.write(f.toPath(), Kdbx.write(KeePassMapper.export(dv.vault, false), new String(pw), null, p));
            Ux.info(this, "Exported. Passkeys are included in KeePassXC's format.");
        } catch (Exception e) {
            Ux.error(this, e.getMessage());
        }
    }

    private void exportCsv() {
        if (!Ux.confirm(this, "The CSV file will contain all your passwords in plain text. Delete it as soon as you're done. Continue?")) return;
        File f = chooseSave("grove-export-" + stamp() + ".csv");
        if (f == null) return;
        try {
            Files.write(f.toPath(), Csv.export(dv.vault).getBytes(StandardCharsets.UTF_8));
            Ux.status("Exported " + f.getName());
        } catch (Exception e) {
            Ux.error(this, e.getMessage());
        }
    }

    private void changePassword() {
        SecurityDialog.show(this, dv);
        refresh();
    }

}
